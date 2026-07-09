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

    /** A package's foreground time today, in minutes. */
    data class AppUsage(val packageName: String, val minutes: Int)

    // usedMinutesToday cache: pkg -> (elapsedRealtime of the query, minutes). The blocking
    // check runs on the main thread per app switch; a 15s TTL turns that into a map read.
    private val usedTodayCache = ConcurrentHashMap<String, Pair<Long, Int>>()

    // Past days' totals can't change during the day — memoized per day-stamp, both in memory
    // and in SharedPreferences so a fresh app launch later the same day skips the slow queries.
    @Volatile private var pastDaysCache: Triple<Int, Int, IntArray>? = null // (dayStamp, days, values)
    @Volatile private var lastWeekCache: Pair<Int, Map<String, Int>>? = null // (dayStamp, per-app mins)

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

    /** [packageName]'s foreground minutes today. Cached ~15s: this sits on the blocking
     *  hot path (every app switch, main thread), so at the exact minute a limit is crossed
     *  the block may fire up to 15s late — imperceptible for a minutes-based daily limit. */
    fun usedMinutesToday(context: Context, packageName: String): Int {
        val now = SystemClock.elapsedRealtime()
        usedTodayCache[packageName]?.let { (at, minutes) ->
            if (now - at < USED_TODAY_TTL_MS) return minutes
        }
        // No usage access -> 0, deliberately uncached so a grant takes effect immediately.
        val stats = todaySnapshot(context) ?: return 0
        val totalMs = stats.filter { it.packageName == packageName }
            .sumOf { it.totalTimeInForeground }
        val minutes = (totalMs / 60_000L).toInt()
        usedTodayCache[packageName] = now to minutes
        return minutes
    }

    /** Minutes used per package today, summed across entries, biggest first. */
    fun topAppsToday(context: Context, limit: Int = 5): List<AppUsage> =
        topAppsToday(todaySnapshot(context), limit)

    fun topAppsToday(stats: List<UsageStats>?, limit: Int = 5): List<AppUsage> {
        stats ?: return emptyList()
        return stats.groupBy { it.packageName }
            .mapValues { (_, list) -> list.sumOf { it.totalTimeInForeground } / 60_000L }
            .filter { it.value > 0 && it.key != OWN_PACKAGE }
            .map { AppUsage(it.key, it.value.toInt()) }
            .sortedByDescending { it.minutes }
            .take(limit)
    }

    /** Foreground minutes today keyed by package (only apps used > 0 min). */
    fun minutesByPackageToday(context: Context): Map<String, Int> {
        val stats = todaySnapshot(context) ?: return emptyMap()
        return stats.groupBy { it.packageName }
            .mapValues { (_, list) -> (list.sumOf { it.totalTimeInForeground } / 60_000L).toInt() }
            .filter { it.value > 0 }
    }

    // ---- Today, totals & breakdowns ----

    /** Total foreground time across all apps today, in minutes. */
    fun totalMinutesToday(context: Context): Int = totalMinutesToday(todaySnapshot(context))

    fun totalMinutesToday(stats: List<UsageStats>?): Int {
        stats ?: return 0
        return (stats.sumOf { it.totalTimeInForeground } / 60_000L).toInt()
    }

    /** Minutes per app category today (keyed by AppCategory.name), biggest first. */
    fun categoryMinutesToday(context: Context): Map<String, Int> =
        categoryMinutesToday(todaySnapshot(context))

    fun categoryMinutesToday(stats: List<UsageStats>?): Map<String, Int> {
        stats ?: return emptyMap()
        val byCat = HashMap<String, Long>()
        stats.forEach { s ->
            if (s.packageName == OWN_PACKAGE) return@forEach
            val cat = AppCategories.categoryOf(s.packageName).name
            byCat[cat] = (byCat[cat] ?: 0L) + s.totalTimeInForeground
        }
        return byCat.mapValues { (it.value / 60_000L).toInt() }
            .filter { it.value > 0 }
            .toList().sortedByDescending { it.second }.toMap()
    }

    /** Foreground minutes bucketed into the 24 hours of today (for the Day chart). */
    fun hourlyMinutesToday(context: Context): IntArray {
        val usm = usageStatsManager(context) ?: return IntArray(24)
        // Same event values pre/post API 29; the constants were just renamed.
        val fgEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            UsageEvents.Event.ACTIVITY_RESUMED
        else @Suppress("DEPRECATION") UsageEvents.Event.MOVE_TO_FOREGROUND
        val bgEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            UsageEvents.Event.ACTIVITY_PAUSED
        else @Suppress("DEPRECATION") UsageEvents.Event.MOVE_TO_BACKGROUND
        val dayStart = startOfToday()
        val now = System.currentTimeMillis()
        val buckets = LongArray(24)
        val fgStart = HashMap<String, Long>()
        val events = usm.queryEvents(dayStart, now)
        val e = UsageEvents.Event()
        while (events.getNextEvent(e)) {
            when (e.eventType) {
                fgEvent -> fgStart[e.packageName] = e.timeStamp
                bgEvent -> {
                    val s = fgStart.remove(e.packageName) ?: continue
                    addInterval(buckets, s, e.timeStamp, dayStart)
                }
            }
        }
        for ((_, s) in fgStart) addInterval(buckets, s, now, dayStart)
        return IntArray(24) { (buckets[it] / 60_000L).toInt() }
    }

    private fun addInterval(buckets: LongArray, from: Long, to: Long, dayStart: Long) {
        var s = max(from, dayStart)
        while (s < to) {
            val hour = ((s - dayStart) / 3_600_000L).toInt().coerceIn(0, 23)
            val hourEnd = dayStart + (hour + 1) * 3_600_000L
            val seg = min(to, hourEnd) - s
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
        val usm = usageStatsManager(context) ?: return 0 to 0
        val fgEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            UsageEvents.Event.ACTIVITY_RESUMED
        else @Suppress("DEPRECATION") UsageEvents.Event.MOVE_TO_FOREGROUND
        val bgEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            UsageEvents.Event.ACTIVITY_PAUSED
        else @Suppress("DEPRECATION") UsageEvents.Event.MOVE_TO_BACKGROUND
        val dayStart = startOfToday()
        val now = System.currentTimeMillis()
        val fgStart = HashMap<String, Long>()
        val intervals = ArrayList<LongArray>() // each = [start, end]
        val events = usm.queryEvents(dayStart, now)
        val e = UsageEvents.Event()
        while (events.getNextEvent(e)) {
            when (e.eventType) {
                fgEvent -> fgStart[e.packageName] = e.timeStamp
                bgEvent -> {
                    val s = fgStart.remove(e.packageName) ?: continue
                    intervals.add(longArrayOf(max(s, dayStart), e.timeStamp))
                }
            }
        }
        for ((_, s) in fgStart) intervals.add(longArrayOf(max(s, dayStart), now))
        if (intervals.isEmpty()) return 0 to 0

        // Merge into a phone-level union timeline.
        intervals.sortBy { it[0] }
        val merged = ArrayList<LongArray>()
        for (iv in intervals) {
            val last = merged.lastOrNull()
            if (last != null && iv[0] <= last[1]) {
                last[1] = max(last[1], iv[1])
            } else {
                merged.add(longArrayOf(iv[0], iv[1]))
            }
        }

        val longestUse = merged.maxOf { it[1] - it[0] }
        var longestGap = 0L
        for (i in 1 until merged.size) {
            longestGap = max(longestGap, merged[i][0] - merged[i - 1][1])
        }
        return (longestUse / 60_000L).toInt() to (longestGap / 60_000L).toInt()
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
        result[days - 1] = queryDayMinutes(usm, daysAgo = 0)
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
