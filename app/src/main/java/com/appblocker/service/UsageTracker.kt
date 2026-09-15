package com.appblocker.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.appblocker.data.AppCategories
import com.appblocker.data.todayStamp
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * Reads how long an app has been used *today* via Android's UsageStats.
 * Querying from midnight means the count naturally resets each day — no
 * separate reset job needed. Requires the PACKAGE_USAGE_STATS special access.
 *
 * Each query is an IPC into the system server, so the hot paths are cached:
 * [usedMinutesToday] (called from the blocking check on every app switch) keeps a
 * short-TTL per-package cache, and the multi-day history (which can't change until
 * midnight) is memoized per calendar day.
 */
object UsageTracker {

    private const val OWN_PACKAGE = "com.appblocker"
    private const val USED_TODAY_TTL_MS = 15_000L
    private const val CACHE_PREFS = "usage_stats_cache"

    /**
     * **How far before a window the usage walk starts reading.**
     *
     * An app already in front when a window opens was resumed before it, so a walk that reads from
     * the window's start never sees that resume and counts the app for nothing until it is left and
     * opened again: the app he was in when a stoppage began, a film still playing at midnight. Every
     * unlock resumes whatever is in front, so this only has to reach back past one unbroken stretch of
     * use, and two hours covers a film. The cost is two more hours of events per walk, a few hundred
     * on a busy phone, and every stretch read from before a window is clipped to its start
     * (invariant 75).
     */
    private const val LEAD_IN_MS = 2 * 3_600_000L

    /** A package's foreground time today, in minutes. */
    data class AppUsage(val packageName: String, val minutes: Int)

    /** One package's last known foreground minutes: which day it is for, when it was read
     *  (monotonic), and the figure itself. */
    private data class UsedToday(val dayStamp: Int, val atRt: Long, val minutes: Int)

    // usedMinutesToday cache. The blocking check runs on the main thread per app switch; a 15s
    // TTL turns that into a map read. The day stamp is what lets a stale figure be *kept* rather
    // than trusted forever — see usedMinutesToday.
    private val usedTodayCache = ConcurrentHashMap<String, UsedToday>()

    // Past days' totals can't change during the day — memoized per day-stamp, both in memory
    // and in SharedPreferences so a fresh app launch later the same day skips the slow queries.
    @Volatile private var pastDaysCache: Triple<Int, Int, IntArray>? = null // (dayStamp, days, values)
    @Volatile private var lastWeekCache: Pair<Int, Map<String, Int>>? = null // (dayStamp, per-app mins)

    // Today's per-app minutes, walked from events once per TTL rather than per package: the
    // blocking check asks for one package at a time, and a full event walk per app switch would
    // be far heavier than the bucket query it replaces. (dayStamp, elapsedRealtime, map)
    @Volatile private var byPackageCache: Triple<Int, Long, Map<String, Int>>? = null

    private fun cachePrefs(context: Context) =
        context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)

    private fun usageStatsManager(context: Context): UsageStatsManager? =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    /** Today's raw UsageStats entries, or null when unavailable (e.g. no usage access).
     *  Fetch this once and pass it to the snapshot overloads to avoid duplicate queries. */
    fun todaySnapshot(context: Context): List<UsageStats>? =
        usageStatsManager(context)
            ?.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfToday(), System.currentTimeMillis())

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Midnight (start of day) [daysAgo] days before today, in epoch millis. */
    fun startOfDayAgo(daysAgo: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, -daysAgo)
    }.timeInMillis

    // ---- Today, per app ----

    /**
     * [packageName]'s foreground minutes today. Cached ~15s: this sits on the blocking hot path
     * (every app switch, main thread), so at the exact minute a limit is crossed the block may
     * fire up to 15s late — imperceptible for a minutes-based daily limit.
     *
     * **A failed or empty read is not "zero minutes."** This is the number a daily limit is
     * compared against, so answering 0 means "carry on, you've used nothing today" — the limit
     * silently stops existing, and under-blocking is invisible to the owner
     * (docs/BLOCKING_INVARIANTS.md, invariant 10). `queryUsageStats` can come back empty for
     * reasons that have nothing to do with the truth: access revoked mid-day, the stats database
     * rotating around midnight, an OEM throttling the query. The old code took whatever came back
     * and cached it for 15 seconds.
     *
     * The fix leans on a fact about the quantity itself: **time used today only ever goes up**,
     * until the day ends. So a fresh reading is adopted only if it is at least the last one for
     * the same day; anything lower is a failed read wearing a number, and the previous figure
     * stands. The day stamp is what stops that becoming its own bug — without it, yesterday's
     * total would stick forever and block the app all day, which is the opposite mistake.
     */
    fun usedMinutesToday(context: Context, packageName: String): Int {
        val now = SystemClock.elapsedRealtime()
        val today = todayStamp()
        val known = usedTodayCache[packageName]?.takeIf { it.dayStamp == today }
        if (known != null && now - known.atRt < USED_TODAY_TTL_MS) return known.minutes
        // Unreadable: keep whatever today's best figure was (nothing, for a phone that never had
        // access) and don't cache, so a grant takes effect immediately.
        val fresh = minutesByPackageToday(context)[packageName] ?: run {
            if (todaySnapshot(context) == null) return known?.minutes ?: 0 else 0
        }
        val minutes = mergeUsedToday(known?.minutes, fresh)
        usedTodayCache[packageName] = UsedToday(today, now, minutes)
        return minutes
    }

    /** Today's minutes never go down. Split out so the rule has a test — the read around it
     *  needs a live system service and cannot have one. */
    internal fun mergeUsedToday(previous: Int?, fresh: Int): Int = max(previous ?: 0, fresh)

    /** Minutes used per app today, biggest first. Excludes AppBlocker — the question is which of
     *  *your* apps took the time, and the app you opened to ask isn't an interesting answer. */
    fun topAppsToday(context: Context, limit: Int = 5): List<AppUsage> =
        minutesByPackageToday(context)
            .filter { it.key != OWN_PACKAGE && it.value > 0 }
            .map { AppUsage(it.key, it.value) }
            .sortedByDescending { it.minutes }
            .take(limit)

    /**
     * Foreground minutes today keyed by package, **from the event stream** — the same source as
     * the screen-time headline, so the app list and the total no longer disagree.
     *
     * This mattered well beyond the stats screen: [usedMinutesToday] reads it, and that is what a
     * **daily limit** is compared against. On buckets, yesterday's time could sit in today's
     * figure, so an app could be blocked in the morning against a limit the owner had not spent —
     * over-blocking, on the blocking path. (And the monotonic guard above, which exists to stop a
     * failed read resetting the count, would then hold that inflated figure for the rest of the
     * day. A safeguard is only as good as the number it is protecting.)
     *
     * Falls back to the buckets when the events can't be read at all — see [screenMinutesToday]
     * for why "nothing readable" must not become "zero".
     */
    fun minutesByPackageToday(context: Context): Map<String, Int> {
        val now = SystemClock.elapsedRealtime()
        val today = todayStamp()
        byPackageCache?.let { (stamp, at, map) ->
            if (stamp == today && now - at < USED_TODAY_TTL_MS) return map
        }
        val walk = walkForeground(context, startOfToday(), System.currentTimeMillis())
        val map = if (walk == null || !walk.sawAnyEvent) bucketMinutesByPackage(context)
        else walk.minutesByPackage().filterValues { it > 0 }
        byPackageCache = Triple(today, now, map)
        return map
    }

    /** The pre-aggregated fallback. Not to be used for a partial day on its own — see
     *  [totalMinutesToday] for what these buckets do to "today". */
    private fun bucketMinutesByPackage(context: Context): Map<String, Int> {
        val stats = todaySnapshot(context) ?: return emptyMap()
        return stats.groupBy { it.packageName }
            .mapValues { (_, list) -> (list.sumOf { it.totalTimeInForeground } / 60_000L).toInt() }
            .filter { it.value > 0 }
    }

    // ---- Today, totals & breakdowns ----

    /** Total foreground time across all apps today, in minutes. */
    fun totalMinutesToday(context: Context): Int = totalMinutesToday(todaySnapshot(context))

    /**
     * Today's total from Android's **pre-aggregated buckets** — kept only as the fallback behind
     * [screenMinutesToday], and not to be used for a partial day.
     *
     * `queryUsageStats` returns whole buckets that merely *overlap* the requested range, each
     * carrying its full period's total, so "midnight → now" can include time from before
     * midnight. That is what showed the owner five hours of screen time on a day he had not been
     * awake five hours, while the Day chart beside it — built from events — looked right.
     */
    fun totalMinutesToday(stats: List<UsageStats>?): Int {
        stats ?: return 0
        return (stats.sumOf { it.totalTimeInForeground } / 60_000L).toInt()
    }

    /**
     * Today's screen time, reconstructed from the **event stream** and merged so overlapping apps
     * count once. This is the honest number: the same source the Day chart uses, scoped to today,
     * which is exactly what the bucket query above cannot do.
     *
     * Counts every package **including AppBlocker itself** — time spent in this app is still time
     * on the phone. (The top-apps list below deliberately hides us, so the *ranking* isn't
     * cluttered by the app you opened to look at the ranking. Different questions.)
     *
     * **No events at all is not zero.** Android keeps only a few days of events and access can be
     * missing, so an empty read falls back to the bucket figure — rough beats false. Events that
     * *were* read and yielded nothing is a true zero: it is early and the phone hasn't been used.
     */
    fun screenMinutesToday(context: Context): Int {
        val dayStart = startOfToday()
        val walk = walkForeground(context, dayStart, System.currentTimeMillis())
        if (walk == null || !walk.sawAnyEvent) return totalMinutesToday(context)
        return (walk.union().sumOf { it[1] - it[0] } / 60_000L).toInt()
    }

    /** Minutes per app category today (keyed by AppCategory.name), biggest first. Same per-app
     *  source as everything else on the Today screen, so the slices agree with the rows above. */
    fun categoryMinutesToday(context: Context): Map<String, Int> {
        val byCat = HashMap<String, Int>()
        minutesByPackageToday(context).forEach { (pkg, mins) ->
            if (pkg == OWN_PACKAGE) return@forEach
            val cat = AppCategories.categoryOf(pkg).name
            byCat[cat] = (byCat[cat] ?: 0) + mins
        }
        return byCat.filter { it.value > 0 }
            .toList().sortedByDescending { it.second }.toMap()
    }

    /** One app's stretch in the foreground. */
    internal class Session(val pkg: String, val from: Long, val to: Long)

    /**
     * **The walk's rules, apart from Android so a test can reach them.**
     *
     * A stretch opens when an app resumes and closes when it pauses. Until 14 Sep 2026 that was the
     * whole rule — so an app whose pause never arrived stayed "in use" until the end of the window,
     * and on a six-hour window one missed pause was six hours of use. This is the figure the
     * stoppage log calls an outage's cost, the stalled detector waits for, and the screen-time
     * headline and daily limits add up (invariant 75).
     *
     * So a stretch also closes when use demonstrably ended:
     * - `ACTIVITY_STOPPED` from the screen that opened the stretch, when that screen never paused;
     * - the screen going dark, the keyguard coming up, or the phone shutting down — for every open
     *   app at once, because nobody uses a phone through any of those.
     *
     * ⚠️ **A stop belongs to a SCREEN, not to an app.** Android writes a screen's stop after the next
     * screen's resume — pause, resume, stop — so the first version of this rule, which matched a stop
     * to its package, ended the new screen's stretch within a second of every move inside an app.
     * Recorded on the API 35 emulator on 15 Sep 2026: 23 seconds in Settings counted as 8. A stop
     * that follows its own pause is the tail of a stretch already closed, and changes nothing.
     *
     * A pause arriving after one of those finds nothing open and adds nothing. A stretch still open
     * when the window closes counts to the end: that is the app in front right now.
     *
     * Events from before [start] are expected: [walkForeground] reads [LEAD_IN_MS] of them, because
     * they are how the app already in front when the window opened is found. A stretch is clipped to
     * [start], and one that ended before it adds nothing, not even an empty stretch at the window's
     * edge, which anything looking for the gaps between uses would read as a use.
     */
    internal class StretchWalker(
        private val start: Long,
        private val fgEvent: Int,
        private val bgEvent: Int,
    ) {
        /** An app's open stretch: when it opened, and which of the app's screens opened it. */
        private class Open(val at: Long, val screen: String?)

        private val open = HashMap<String, Open>()

        /** Screens that have paused and not stopped yet, counted per app and screen. Their stops are
         *  still to come, and when one arrives it is not the end of anything. */
        private val awaitingStop = HashMap<String, Int>()

        private val out = ArrayList<Session>()

        /** True once any event at all was read — "nothing readable" is not "not used". */
        var sawAnyEvent = false
            private set

        /** @param screen the activity class the event is about; null for a device-wide event. */
        fun onEvent(type: Int, pkg: String, screen: String?, at: Long) {
            sawAnyEvent = true
            when (type) {
                fgEvent -> open[pkg] = Open(at, screen)
                bgEvent -> {
                    val key = screenKey(pkg, screen)
                    awaitingStop[key] = (awaitingStop[key] ?: 0) + 1
                    open.remove(pkg)?.let { close(pkg, it.at, at) }
                }
                UsageEvents.Event.ACTIVITY_STOPPED -> stopped(pkg, screen, at)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.KEYGUARD_SHOWN,
                UsageEvents.Event.DEVICE_SHUTDOWN -> {
                    open.forEach { (p, o) -> close(p, o.at, at) }
                    open.clear()
                }
            }
        }

        /** A stop ends the open stretch only when its own screen opened it and never paused. */
        private fun stopped(pkg: String, screen: String?, at: Long) {
            val key = screenKey(pkg, screen)
            val waiting = awaitingStop[key] ?: 0
            if (waiting > 0) {
                if (waiting == 1) awaitingStop.remove(key) else awaitingStop[key] = waiting - 1
                return
            }
            val o = open[pkg] ?: return
            if (o.screen != screen) return
            open.remove(pkg)
            close(pkg, o.at, at)
        }

        private fun screenKey(pkg: String, screen: String?) = "$pkg/${screen.orEmpty()}"

        private fun close(pkg: String, openedAt: Long, at: Long) {
            val from = max(openedAt, start)
            // Nothing of it inside the window: it ended before the window began, or it has no length.
            if (at <= from) return
            out.add(Session(pkg, from, at))
        }

        /** Every stretch, with those still open counted up to [end]. */
        fun finish(end: Long): List<Session> {
            open.forEach { (p, o) -> close(p, o.at, end) }
            open.clear()
            return out
        }
    }

    /** What one walk of the event stream found. [sawAnyEvent] separates "the phone wasn't used"
     *  from "we couldn't read anything", which are opposite facts that both yield no sessions. */
    private class Walk(val sessions: List<Session>, val sawAnyEvent: Boolean) {
        /** The phone-level timeline: who was in front doesn't matter, only that it was on. */
        fun union(): List<LongArray> =
            mergeIntervals(sessions.map { longArrayOf(it.from, it.to) })

        /** Minutes per package, each app's own stretches merged. Note these can sum to MORE than
         *  [union] — two apps overlapping is one minute of screen time but a minute for each of
         *  them, which is the right answer to two different questions. */
        fun minutesByPackage(): Map<String, Int> = sessions
            .groupBy { it.pkg }
            .mapValues { (_, s) ->
                (mergeIntervals(s.map { longArrayOf(it.from, it.to) })
                    .sumOf { it[1] - it[0] } / 60_000L).toInt()
            }
    }

    /**
     * Every app's foreground stretches within [start]..[end], as `[from, to]` pairs — **unmerged**,
     * because two callers want the union and one wants to know they came from different apps.
     *
     * One implementation of this walk, where there were three: the hourly chart, the session stats
     * and the range total each had their own copy, and the copies had drifted — two of them summed
     * overlapping stretches while the third merged them. Returns null when there is no
     * UsageStatsManager at all.
     */
    /**
     * **What is in front right now, asked of Android instead of of our own watcher.**
     *
     * The blocker learns the foreground app from accessibility events. When the framework stops
     * sending them — the `aliveButDeaf` case, four of six stoppages on 2 Sep 2026 — our process is
     * still running and still perfectly able to draw a cover; it has simply gone blind. Every
     * instrument in this app agrees the watcher is healthy, because from the inside it is.
     *
     * This is the second pair of eyes. Usage stats come from the system, not from our binding, so
     * they keep answering through a deaf spell, through the seconds after a rebind before the
     * first event lands, and through the window where the rule flow has not emitted yet.
     *
     * ⚠️ **Only ever called while the watcher is already known to be silent** — see the heartbeat.
     * A query per minute on a healthy phone would be a real battery cost for nothing, and "keep
     * the battery as it is" is the owner's standing constraint.
     *
     * Returns null when usage access is off, when nothing resumed inside the window, or when the
     * query failed — never a guess. [lookBackMs] is deliberately short: this asks *what is on
     * screen*, and an hour-old answer would be a different question.
     */
    fun currentForegroundPackage(
        context: Context,
        lookBackMs: Long = 2 * 60_000L,
        now: Long = System.currentTimeMillis(),
    ): String? = runCatching {
        val usm = usageStatsManager(context) ?: return@runCatching null
        val fgEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            UsageEvents.Event.ACTIVITY_RESUMED
        else @Suppress("DEPRECATION") UsageEvents.Event.MOVE_TO_FOREGROUND
        val events = usm.queryEvents(now - lookBackMs, now)
        val e = UsageEvents.Event()
        var newest: String? = null
        var newestAt = Long.MIN_VALUE
        while (events.getNextEvent(e)) {
            // The LAST resume wins, and the events are not guaranteed to arrive in order, so this
            // compares timestamps rather than trusting the iteration.
            if (e.eventType == fgEvent && e.timeStamp >= newestAt) {
                newestAt = e.timeStamp
                newest = e.packageName
            }
        }
        newest
    }.getOrNull()

    private fun walkForeground(context: Context, start: Long, end: Long): Walk? {
        val usm = usageStatsManager(context) ?: return null
        // Same event values pre/post API 29; the constants were just renamed.
        val fgEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            UsageEvents.Event.ACTIVITY_RESUMED
        else @Suppress("DEPRECATION") UsageEvents.Event.MOVE_TO_FOREGROUND
        val bgEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            UsageEvents.Event.ACTIVITY_PAUSED
        else @Suppress("DEPRECATION") UsageEvents.Event.MOVE_TO_BACKGROUND
        // The rules for when a stretch opens and closes live in StretchWalker, where a test can
        // reach them — including the ones this loop used to lack (invariant 75).
        val walker = StretchWalker(start, fgEvent, bgEvent)
        // ⚠️ From before the window: the app already in front when it opens was resumed earlier, and
        // without that resume it counts nothing until it is left (LEAD_IN_MS, invariant 75).
        val events = usm.queryEvents(start - LEAD_IN_MS, end)
        val e = UsageEvents.Event()
        // Each event carries its screen: a stop is matched to the screen that paused, never to
        // whichever of the app's screens happens to be open (invariant 75).
        while (events.getNextEvent(e)) walker.onEvent(e.eventType, e.packageName, e.className, e.timeStamp)
        return Walk(walker.finish(end), walker.sawAnyEvent)
    }

    /**
     * Overlapping and touching intervals collapsed into a union timeline.
     *
     * **This is the double-count.** Foreground stretches from different apps overlap routinely —
     * one app's *paused* lands after the next app's *resumed* on every switch — so adding them up
     * counts the same seconds twice. Small per switch, and it compounds over a day; more to the
     * point it made an hour of the chart able to hold more than sixty minutes, which is the kind
     * of impossible number that should be unrepresentable rather than merely rare.
     *
     * Pure, and separate, because it is the one part of this file a test can reach: everything
     * around it needs a live UsageStatsManager.
     */
    internal fun mergeIntervals(intervals: List<LongArray>): List<LongArray> {
        if (intervals.isEmpty()) return emptyList()
        val sorted = intervals.sortedBy { it[0] }
        val merged = ArrayList<LongArray>()
        for (iv in sorted) {
            val last = merged.lastOrNull()
            // <= so touching intervals join: "ended at 10:00, started at 10:00" is one stretch.
            if (last != null && iv[0] <= last[1]) last[1] = max(last[1], iv[1])
            else merged.add(longArrayOf(iv[0], iv[1]))
        }
        return merged
    }

    /** Foreground minutes bucketed into the 24 hours of today (for the Day chart). Merged first,
     *  so no hour can report more than sixty minutes and the bars add up to
     *  [screenMinutesToday] — the disagreement between chart and headline is what made the
     *  inflated total hard to spot. */
    fun hourlyMinutesToday(context: Context): IntArray {
        val dayStart = startOfToday()
        val walk = walkForeground(context, dayStart, System.currentTimeMillis())
            ?: return IntArray(24)
        val buckets = LongArray(24)
        walk.union().forEach { addInterval(buckets, it[0], it[1], dayStart) }
        return IntArray(24) { (buckets[it] / 60_000L).toInt() }
    }

    /**
     * Adds [from]..[to] into the 24 hourly [buckets] measured from [dayStart].
     *
     * Internal (not private) so [UsageTrackerTest] can pin the termination rule below.
     *
     * **A local day is not always 24 hours long.** On the DST fall-back day it is 25, so
     * `now - startOfToday()` — and therefore an interval's ends — can legitimately sit past
     * `dayStart + 24h`. The hour index used to be `coerceIn(0, 23)` while `hourEnd` was computed
     * from the *coerced* hour, so once past that point `hourEnd` was behind `s`: the step went
     * negative (corrupting a bucket), then exactly zero, and the loop spun forever. Once a year,
     * the Insights screen and the coach would hang on a pegged core.
     *
     * So: clamp the interval to the buckets that exist, and never take a non-positive step. The
     * 25th hour's usage is dropped — there is nowhere in a 24-bucket chart to put it.
     */
    internal fun addInterval(buckets: LongArray, from: Long, to: Long, dayStart: Long) {
        val end = min(to, dayStart + 24 * 3_600_000L)
        var s = max(from, dayStart)
        while (s < end) {
            val hour = ((s - dayStart) / 3_600_000L).toInt()
            if (hour !in buckets.indices) break
            val hourEnd = dayStart + (hour + 1) * 3_600_000L
            val seg = min(end, hourEnd) - s
            if (seg <= 0L) break // the step must always make progress
            buckets[hour] += seg
            s += seg
        }
    }

    /** Today's phone-use session stats as (longestContinuousUseMin, longestFocusGapMin).
     *  Reconstructs foreground sessions from the same event stream as [hourlyMinutesToday],
     *  but merges every app's intervals into one phone-level timeline:
     *   - continuous use = the longest single stretch the phone was in use,
     *   - longest focus  = the longest gap between two uses (0 if fewer than 2 sessions;
     *     the pre-first-use and after-last-use periods are ignored so sleep doesn't count). */
    fun sessionStatsToday(context: Context): Pair<Int, Int> {
        val walk = walkForeground(context, startOfToday(), System.currentTimeMillis())
            ?: return 0 to 0
        // This function's own inline copy of the merge is what became [mergeIntervals]: it was
        // the only one of the three event walks that got this right, so it became the shared one.
        val merged = walk.union()
        if (merged.isEmpty()) return 0 to 0
        val longestUse = merged.maxOf { it[1] - it[0] }
        var longestGap = 0L
        for (i in 1 until merged.size) {
            longestGap = max(longestGap, merged[i][0] - merged[i - 1][1])
        }
        return (longestUse / 60_000L).toInt() to (longestGap / 60_000L).toInt()
    }

    /** Total foreground minutes across exactly [start]..[end], reconstructed from events —
     *  the bucket-based queries can't trim a partial day, which is what the coach's
     *  "by this same time yesterday" comparison needs. A session already in progress at
     *  [start] is found by reading [LEAD_IN_MS] earlier and counted from [start]; one that had
     *  already run longer than that is still missed, and events older than a few days may be
     *  gone, so this can read low. */
    fun totalMinutesInRange(context: Context, start: Long, end: Long): Int {
        val walk = walkForeground(context, start, end) ?: return 0
        // Merged, like every other total here. Unmerged it double-counted overlapping apps, which
        // mattered in two places that are not charts: the coach's "by this time yesterday"
        // comparison, and ProtectionWatchdog's "minutes of active use with no events" — where an
        // inflated figure declares blocking STALLED sooner than it should.
        return (walk.union().sumOf { it[1] - it[0] } / 60_000L).toInt()
    }

    // ---- History (multi-day) ----

    /** Total foreground minutes for each of the last [days] days (last index = today).
     *  Past days are memoized per calendar day, so after the first call of the day this
     *  costs a single query (today's) instead of one per day. */
    fun dailyMinutes(context: Context, days: Int): IntArray {
        val usm = usageStatsManager(context) ?: return IntArray(days)
        val today = todayStamp()
        val past = cachedPastDays(context, today, days)
            ?: IntArray(days - 1) { i -> queryDayMinutes(usm, daysAgo = days - 1 - i) }
                .also { storePastDays(context, today, days, it) }
        val result = IntArray(days)
        past.copyInto(result)
        // Today from the event stream, so the last bar of this chart agrees with the headline
        // figure. Past days stay on bucket queries: a query covering a WHOLE day is roughly what
        // the buckets hold anyway, and the events don't reach back thirty days to do better. It
        // is the *partial* day the buckets cannot express — which is precisely today.
        result[days - 1] = screenMinutesToday(context)
        return result
    }

    private fun cachedPastDays(context: Context, stamp: Int, days: Int): IntArray? {
        pastDaysCache?.let { (s, d, v) -> if (s == stamp && d == days) return v }
        val raw = cachePrefs(context).getString("past_days", null) ?: return null
        val parts = raw.split("|")
        if (parts.size != 3 || parts[0].toIntOrNull() != stamp || parts[1].toIntOrNull() != days) return null
        val values = parts[2].split(",").mapNotNull { it.toIntOrNull() }
        if (values.size != days - 1) return null
        return values.toIntArray().also { pastDaysCache = Triple(stamp, days, it) }
    }

    private fun storePastDays(context: Context, stamp: Int, days: Int, values: IntArray) {
        // An all-zero history usually means usage access was missing — don't cache it, so
        // granting access mid-day shows real history immediately instead of after midnight.
        if (values.all { it == 0 }) return
        pastDaysCache = Triple(stamp, days, values)
        cachePrefs(context).edit()
            .putString("past_days", "$stamp|$days|${values.joinToString(",")}")
            .apply()
    }

    private fun queryDayMinutes(usm: UsageStatsManager, daysAgo: Int): Int {
        val start = startOfDayAgo(daysAgo)
        val end = start + 24 * 3_600_000L
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end) ?: return 0
        return (stats.sumOf { it.totalTimeInForeground } / 60_000L).toInt()
    }

    /** Foreground minutes per package across [start]..[end] (skips our own app). */
    fun appMinutesInRange(context: Context, start: Long, end: Long): Map<String, Int> {
        val usm = usageStatsManager(context) ?: return emptyMap()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end) ?: return emptyMap()
        val byPkg = HashMap<String, Long>()
        stats.forEach { s ->
            if (s.packageName == OWN_PACKAGE) return@forEach
            byPkg[s.packageName] = (byPkg[s.packageName] ?: 0L) + s.totalTimeInForeground
        }
        return byPkg.mapValues { (it.value / 60_000L).toInt() }.filter { it.value > 0 }
    }

    /** Per-app minutes for LAST week (7–13 days ago). That whole range is in the past,
     *  so the result is memoized per calendar day (in memory + prefs). */
    fun lastWeekAppMinutes(context: Context): Map<String, Int> {
        val today = todayStamp()
        lastWeekCache?.let { (stamp, map) -> if (stamp == today) return map }
        cachePrefs(context).getString("last_week", null)?.let { raw ->
            val sep = raw.indexOf('|')
            if (sep > 0 && raw.substring(0, sep).toIntOrNull() == today) {
                val map = raw.substring(sep + 1).split(",")
                    .mapNotNull { entry ->
                        val eq = entry.indexOf('=')
                        if (eq <= 0) null
                        else entry.substring(eq + 1).toIntOrNull()?.let { entry.substring(0, eq) to it }
                    }.toMap()
                lastWeekCache = today to map
                return map
            }
        }
        val map = appMinutesInRange(context, startOfDayAgo(13), startOfDayAgo(6))
        if (map.isNotEmpty()) { // empty likely = no usage access yet; don't cache
            lastWeekCache = today to map
            cachePrefs(context).edit()
                .putString("last_week", "$today|" + map.entries.joinToString(",") { "${it.key}=${it.value}" })
                .apply()
        }
        return map
    }
}
