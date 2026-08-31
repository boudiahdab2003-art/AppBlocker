package com.appblocker.data

import android.content.Context
import android.os.Process
import android.os.SystemClock

/**
 * **How long blocking was actually off, and what happened just before it stopped.**
 *
 * [ServiceHealth.recordFoundDead] counts outages. That number answered the question it was built
 * for — "has this happened once or fourteen times" — and then stopped, because a count is not an
 * interval. When the owner said blocking stops "frequently, more than my other blockers", there
 * was no value anywhere on the phone that could say how often, how long, or what preceded it. That
 * is invariant 26 a second time: *an instrument that thresholds an interval has thrown the interval
 * away* — the same mistake [BlockLatency] was written to correct for block speed, repeated for the
 * failure that costs more than slowness ever did.
 *
 * ## What it is really for
 *
 * The app's written explanation for this failure has always been Xiaomi's Second Space (see
 * `ui/RepairScreen.kt` and `DeviceVendor.spacesWarning`). The owner ruled that out on 28 Aug 2026:
 * he rarely uses it now and blocking still stops. So the cause is **unknown**, and the three
 * candidates left — our own updates killing the process, HyperOS killing it anyway, or the watcher
 * being dropped for being slow — cannot be told apart by reasoning. Each leaves a different
 * fingerprint, and this records the fingerprint:
 *
 * - [Episode.aliveButDeaf] — did our process outlive the last event it saw? **This is the whole
 *   discriminator.** False means the process was killed and came back without being rebound
 *   (a battery manager, or our own install). True means the process was running the entire time
 *   and Android simply stopped delivering to it — a different bug with a different fix.
 * - [Episode.precededBy] — an update, a reboot, or nothing at all. The owner installs releases
 *   several times a week; nobody installs a normal app that often, which is the only difference
 *   between this blocker and the others he compares it to. That hypothesis is either true or it
 *   is not, and one field settles it.
 * - [Episode.detectedAfterMs] — how long blocking was off **before the app noticed**. Every other
 *   number here is about the app; this one is about him. It is the protection that was actually
 *   lost.
 *
 * ## Rules it follows
 *
 * **Counts, states and durations only — never content.** Same rule as every other instrument that
 * can leave this phone: no package, no host, no word. An outage has no content to leak, and this
 * keeps none.
 *
 * **The episode is written when it ENDS.** A record opened and never closed would be a phone that
 * is still broken, and the duration is the point.
 *
 * **Elapsed time is measured monotonically** (invariant 9), from [SystemClock.elapsedRealtime].
 * Wall clock is kept alongside it only to say *when* — and because a reboot resets the monotonic
 * clock, which is itself recorded rather than silently producing a nonsense duration.
 */
object OutageLog {

    private const val PREFS = "outage_log"

    /** The finished episodes, oldest first, `;`-separated — same shape as [BlockLog]. */
    private const val KEY_EPISODES = "episodes"

    /** The open episode's fields, present only while blocking is down. */
    private const val KEY_OPEN_STARTED = "open_started_at"
    private const val KEY_OPEN_STARTED_RT = "open_started_rt"
    private const val KEY_OPEN_DETECTED = "open_detected_at"
    private const val KEY_OPEN_DEAF = "open_alive_but_deaf"
    private const val KEY_OPEN_PRECEDED = "open_preceded_by"
    private const val KEY_OPEN_BOOT = "open_boot_count"
    private const val KEY_OPEN_VERSION = "open_version"
    private const val KEY_OPEN_DETECTED_BY = "open_detected_by"

    /** When a version change was last noticed, stamped by [UpdatePause.checkVersionChange]. */
    private const val KEY_LAST_UPDATE_AT = "last_update_at"

    /** Lifetime totals, kept separately so trimming the ring never loses them. */
    private const val KEY_TOTAL_COUNT = "total_count"
    private const val KEY_TOTAL_MS = "total_ms"
    private const val KEY_LONGEST_MS = "longest_ms"

    /**
     * How many finished episodes are kept. Twenty is enough to see a pattern in a week of them
     * and small enough that the whole log fits in a report without crowding out the block log.
     */
    private const val MAX = 20

    /**
     * How close to an update or a reboot an outage has to start before that gets the blame.
     *
     * Fifteen minutes because a rebind that is going to happen happens in seconds, and anything
     * still dead a quarter of an hour later is dead for its own reasons. Being wrong here costs a
     * mislabelled field, never a blocking decision.
     */
    internal const val BLAME_WINDOW_MS = 15 * 60_000L

    /** What had just happened when blocking stopped. */
    object Preceded {
        const val UPDATE = "update"
        const val BOOT = "boot"
        const val NOTHING = "nothing"
        val ALL = setOf(UPDATE, BOOT, NOTHING)
    }

    /**
     * **Which detector noticed** — without this, a shorter `detectedAfterMs` cannot be attributed
     * to anything.
     *
     * The app now has three ways to conclude blocking has stopped, and they answer at wildly
     * different speeds: [UNBOUND] in about twenty seconds, [PROBE] in about a quarter of an hour,
     * [STALE] in two hours plus fifteen measured minutes of use. If the average detection gap
     * improves after this release, the only way to know *why* is to have recorded which arm fired
     * — otherwise it is exactly the mistake invariant 31 is about, measuring the crossing and
     * throwing away what caused it.
     */
    object DetectedBy {
        /** `serviceConnected == false` past the bind grace: the watcher is not there at all. */
        const val UNBOUND = "unbound"

        /** Bound, but it could not read a lit unlocked screen — see `PROBE_FAIL_LIMIT`. */
        const val PROBE = "probe"

        /** Hours of silence with the phone measurably in use — the original, slowest detector. */
        const val STALE = "stale"

        /** An episode recorded before this field existed. Never guessed at. */
        const val UNKNOWN = "unknown"

        val ALL = setOf(UNBOUND, PROBE, STALE, UNKNOWN)
    }

    /** One finished outage. */
    data class Episode(
        /** Wall clock, roughly when blocking stopped — the last event the watcher saw. */
        val startedAt: Long,
        /** How long it was down, monotonically, or -1 when a reboot broke the measurement. */
        val durationMs: Long,
        /** How long it was down before the app noticed, or -1 when it could not be worked out. */
        val detectedAfterMs: Long,
        /** True when our process outlived the last event — bound but deaf, rather than killed. */
        val aliveButDeaf: Boolean,
        /** One of [Preceded]. */
        val precededBy: String,
        /** The phone was restarted while blocking was down. */
        val rebooted: Boolean,
        /** The version that was running when it stopped. */
        val versionCode: Long,
        /** Which arm concluded blocking had stopped — one of [DetectedBy]. */
        val detectedBy: String = DetectedBy.UNKNOWN,
    ) {
        /**
         * A report-ready line. No content, by construction — every field here is a number.
         *
         * **`at=` is the day and hour it began**, and it is the only place in the whole app that
         * question can be answered. Every other instrument stores `today_` and `total_` and a day
         * stamp, so there is no per-hour history anywhere; these lines are the sole record of
         * *when* blocking has failed. The stoppage history exists to show whether episodes cluster
         * — after updates, after reboots, at one time of the evening — and without the hour it
         * could answer only two thirds of that. Local time on purpose: the phone's own evening is
         * the thing being asked about, and the issue's UTC timestamp is when the report was sent,
         * which for a queued report can be days later.
         */
        fun render(): String {
            val mins = if (durationMs < 0) "?" else "${durationMs / 60_000}"
            val detect = if (detectedAfterMs < 0) "?" else "${detectedAfterMs / 60_000}"
            return "at=${startedLabel()}  down=${mins}min  noticedAfter=${detect}min  " +
                "deaf=$aliveButDeaf  after=$precededBy  rebooted=$rebooted  v=$versionCode  " +
                "by=$detectedBy"
        }

        /** `Sun 21:40`, or `?` when the stamp was never recorded. Never a date-with-year: the day
         *  of the week and the hour are the pattern, and the rest is noise in a fixed-width line. */
        private fun startedLabel(): String =
            if (startedAt <= 0L) "?" else runCatching {
                java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.US)
                    .format(java.util.Date(startedAt))
            }.getOrDefault("?")
    }

    /**
     * Works out an episode from raw readings. **Pure, so it can be tested** — the prefs around it
     * cannot be reached from a JVM test, which is the same split [protectionState] uses and for
     * the same reason.
     *
     * @param startedAt wall clock of the last event seen before the outage (0 = never).
     * @param startedRt the monotonic clock at that moment, as recorded when the episode opened.
     * @param nowRt the monotonic clock now.
     * @param bootAtOpen / [bootNow] Android's boot counter then and now — different means the
     *   phone restarted mid-outage, which resets [SystemClock.elapsedRealtime] and makes the
     *   monotonic difference meaningless. An unreadable counter is -1 on both sides and compares
     *   equal, which is right: "can't tell" must not invent a reboot (invariant 11).
     */
    internal fun shape(
        startedAt: Long,
        startedRt: Long,
        detectedAt: Long,
        nowRt: Long,
        bootAtOpen: Int,
        bootNow: Int,
        aliveButDeaf: Boolean,
        precededBy: String,
        versionCode: Long,
        detectedBy: String = DetectedBy.UNKNOWN,
    ): Episode {
        val rebooted = bootAtOpen != bootNow
        // A reboot resets the monotonic clock, so the difference across one is not a duration.
        // Reporting "?" is the honest answer; a wall-clock fallback would look like a measurement
        // while being at the mercy of a clock the user can change (invariant 9).
        val duration = if (rebooted) -1L else (nowRt - startedRt).coerceAtLeast(0L)
        val detectedAfter =
            if (startedAt <= 0L || detectedAt <= 0L) -1L
            else (detectedAt - startedAt).coerceAtLeast(0L)
        return Episode(
            startedAt = startedAt,
            durationMs = duration,
            detectedAfterMs = detectedAfter,
            aliveButDeaf = aliveButDeaf,
            precededBy = if (precededBy in Preceded.ALL) precededBy else Preceded.NOTHING,
            rebooted = rebooted,
            versionCode = versionCode,
            detectedBy = if (detectedBy in DetectedBy.ALL) detectedBy else DetectedBy.UNKNOWN,
        )
    }

    /**
     * Decides what to blame. Pure for the same reason [shape] is.
     *
     * Order matters: an update installed just after a reboot is still an update, and the update is
     * the more specific answer. Both windows are measured against when blocking *stopped*, not
     * when the app noticed — the gap between those two is exactly what this file exists to expose.
     */
    internal fun blame(startedAt: Long, lastUpdateAt: Long, bootedAt: Long): String = when {
        lastUpdateAt > 0L && startedAt - lastUpdateAt in 0..BLAME_WINDOW_MS -> Preceded.UPDATE
        bootedAt > 0L && startedAt - bootedAt in 0..BLAME_WINDOW_MS -> Preceded.BOOT
        else -> Preceded.NOTHING
    }

    /**
     * Was our process already running when the watcher last saw something?
     *
     * [Process.getStartElapsedRealtime] is monotonic and the last-event stamp is wall clock, so
     * one has to be carried onto the other's scale. The conversion is only as good as the wall
     * clock, which is why the answer is used as a hint in a report and never as a gate on
     * anything that blocks.
     */
    internal fun aliveButDeaf(
        lastEventAt: Long,
        nowWall: Long,
        nowRt: Long,
        procStartRt: Long,
    ): Boolean {
        if (lastEventAt <= 0L) return false
        val procStartedWall = nowWall - (nowRt - procStartRt)
        return procStartedWall < lastEventAt
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Stamped when a version change is noticed, so an outage that follows one can say so.
     *
     * Written to disk rather than kept in memory because the thing it describes — our own install
     * — is Android killing this process (invariant 21). Knowledge with a lifetime shorter than the
     * thing it describes is no knowledge at all.
     */
    fun noteVersionChange(context: Context, now: Long = System.currentTimeMillis()) {
        runCatching { prefs(context).edit().putLong(KEY_LAST_UPDATE_AT, now).apply() }
    }

    /**
     * Blocking has just been found down. Opens an episode if one isn't already open.
     *
     * Called from the same transition that increments [ServiceHealth.recordFoundDead], so the two
     * can never disagree about how many outages there have been.
     *
     * Never throws: this runs from the watchdog, which runs from the app's own resume path.
     */
    fun begin(
        context: Context,
        lastEventAt: Long,
        now: Long = System.currentTimeMillis(),
        detectedBy: String = DetectedBy.UNKNOWN,
    ) {
        runCatching {
            val p = prefs(context)
            if (p.contains(KEY_OPEN_STARTED)) return@runCatching
            val nowRt = SystemClock.elapsedRealtime()
            // When blocking stopped, as opposed to when we noticed: the last event the watcher
            // saw. It is up to a minute stale (ServiceHealth throttles its writes) and it is
            // still far closer than the moment a 15-minute worker happened to look.
            val startedAt = if (lastEventAt > 0L) lastEventAt else now
            val startedRt = nowRt - (now - startedAt).coerceAtLeast(0L)
            val bootedAt = now - nowRt
            p.edit()
                .putLong(KEY_OPEN_STARTED, startedAt)
                .putLong(KEY_OPEN_STARTED_RT, startedRt)
                .putLong(KEY_OPEN_DETECTED, now)
                .putBoolean(
                    KEY_OPEN_DEAF,
                    aliveButDeaf(lastEventAt, now, nowRt, Process.getStartElapsedRealtime()),
                )
                .putString(
                    KEY_OPEN_PRECEDED,
                    blame(startedAt, p.getLong(KEY_LAST_UPDATE_AT, 0L), bootedAt),
                )
                .putInt(KEY_OPEN_BOOT, DeviceBoot.count(context))
                .putLong(KEY_OPEN_VERSION, AppVersion.code(context))
                // Recorded when the episode OPENS, because that is the only moment anyone knows
                // which arm fired. By the time it closes the state has moved on.
                .putString(
                    KEY_OPEN_DETECTED_BY,
                    if (detectedBy in DetectedBy.ALL) detectedBy else DetectedBy.UNKNOWN,
                )
                .apply()
        }
    }

    /**
     * Blocking is back. Closes the open episode and returns it, or null when none was open —
     * which is the ordinary case, since the watchdog reports OK far more often than it reports
     * anything else.
     *
     * The returned episode is what gets reported off the phone; the caller decides that, so this
     * stays a store rather than becoming a reporter.
     */
    fun end(context: Context, now: Long = System.currentTimeMillis()): Episode? = runCatching {
        val p = prefs(context)
        val startedAt = p.getLong(KEY_OPEN_STARTED, 0L)
        if (!p.contains(KEY_OPEN_STARTED)) return@runCatching null
        val episode = shape(
            startedAt = startedAt,
            startedRt = p.getLong(KEY_OPEN_STARTED_RT, 0L),
            detectedAt = p.getLong(KEY_OPEN_DETECTED, 0L),
            nowRt = SystemClock.elapsedRealtime(),
            bootAtOpen = p.getInt(KEY_OPEN_BOOT, -1),
            bootNow = DeviceBoot.count(context),
            aliveButDeaf = p.getBoolean(KEY_OPEN_DEAF, false),
            precededBy = p.getString(KEY_OPEN_PRECEDED, Preceded.NOTHING) ?: Preceded.NOTHING,
            versionCode = p.getLong(KEY_OPEN_VERSION, -1L),
            detectedBy = p.getString(KEY_OPEN_DETECTED_BY, DetectedBy.UNKNOWN) ?: DetectedBy.UNKNOWN,
        )
        val existing = p.getString(KEY_EPISODES, "").orEmpty()
            .split(';').filter { it.isNotBlank() }
        val trimmed = (existing + encode(episode)).takeLast(MAX)
        val known = episode.durationMs.coerceAtLeast(0L)
        p.edit()
            .putString(KEY_EPISODES, trimmed.joinToString(";"))
            // Totals live outside the ring so twenty-one outages don't erase the first one's cost.
            .putInt(KEY_TOTAL_COUNT, p.getInt(KEY_TOTAL_COUNT, 0) + 1)
            .putLong(KEY_TOTAL_MS, p.getLong(KEY_TOTAL_MS, 0L) + known)
            .putLong(KEY_LONGEST_MS, maxOf(p.getLong(KEY_LONGEST_MS, 0L), known))
            .remove(KEY_OPEN_STARTED)
            .remove(KEY_OPEN_STARTED_RT)
            .remove(KEY_OPEN_DETECTED)
            .remove(KEY_OPEN_DEAF)
            .remove(KEY_OPEN_PRECEDED)
            .remove(KEY_OPEN_BOOT)
            .remove(KEY_OPEN_VERSION)
            .remove(KEY_OPEN_DETECTED_BY)
            .apply()
        episode
    }.getOrNull()

    /** True while blocking is down and an episode is open. */
    fun isOpen(context: Context): Boolean =
        runCatching { prefs(context).contains(KEY_OPEN_STARTED) }.getOrDefault(false)

    /** Every finished episode, newest first, as report-ready lines. */
    fun recent(context: Context): List<String> = runCatching {
        prefs(context).getString(KEY_EPISODES, "").orEmpty()
            .split(';').filter { it.isNotBlank() }
            .mapNotNull { decode(it) }
            .reversed()
            .map { it.render() }
    }.getOrDefault(emptyList())

    /** The most recent finished episode, or null before there has been one. */
    fun last(context: Context): Episode? = runCatching {
        prefs(context).getString(KEY_EPISODES, "").orEmpty()
            .split(';').filter { it.isNotBlank() }
            .lastOrNull()?.let { decode(it) }
    }.getOrNull()

    /** Lifetime totals: how many, how long in all, and the worst one — all in millis. */
    data class Totals(val count: Int, val totalMs: Long, val longestMs: Long)

    fun totals(context: Context): Totals = runCatching {
        val p = prefs(context)
        Totals(
            count = p.getInt(KEY_TOTAL_COUNT, 0),
            totalMs = p.getLong(KEY_TOTAL_MS, 0L),
            longestMs = p.getLong(KEY_LONGEST_MS, 0L),
        )
    }.getOrDefault(Totals(0, 0L, 0L))

    internal fun encode(e: Episode): String = listOf(
        e.startedAt, e.durationMs, e.detectedAfterMs, e.aliveButDeaf,
        e.precededBy, e.rebooted, e.versionCode, e.detectedBy,
    ).joinToString("|")

    /**
     * ⚠️ **Both shapes decode.** The seven-field form is what is already stored on his phone, and
     * it is the only outage data that has ever existed — the instrument shipped in v1.143 and the
     * first reports have not come back yet. A strict `p.size != 8` here would silently discard
     * every episode recorded so far at the moment the app that could finally read them installed.
     *
     * A legacy row genuinely does not know which arm found it, and `UNKNOWN` says so rather than
     * guessing a plausible one — the same rule as a rebooted duration reporting `-1`.
     */
    internal fun decode(raw: String): Episode? {
        val p = raw.split('|')
        if (p.size != 7 && p.size != 8) return null
        return Episode(
            startedAt = p[0].toLongOrNull() ?: return null,
            durationMs = p[1].toLongOrNull() ?: return null,
            detectedAfterMs = p[2].toLongOrNull() ?: return null,
            aliveButDeaf = p[3].toBoolean(),
            precededBy = if (p[4] in Preceded.ALL) p[4] else Preceded.NOTHING,
            rebooted = p[5].toBoolean(),
            versionCode = p[6].toLongOrNull() ?: -1L,
            detectedBy = p.getOrNull(7)?.takeIf { it in DetectedBy.ALL } ?: DetectedBy.UNKNOWN,
        )
    }
}
