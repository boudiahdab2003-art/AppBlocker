package com.appblocker.data

import android.content.Context
import android.os.SystemClock

/**
 * **How long the accessibility switch itself read OFF, and the one clue to who switched it.**
 *
 * [OutageLog] times the failure where the switch reads ON and nothing is running behind it. The
 * watchdog has always treated the other failure — the switch reading OFF — as a *choice*: it posts
 * the "blocking is off" notification and records nothing. So on 10 Sep 2026 (report #118) the switch
 * was found OFF after fourteen hours with no sign of the watcher and six restarts in a day, and not
 * one line of that reached the stoppage history. If the phone is what switches blocking off, that is
 * the largest gap of the week, and the app could neither measure it nor say it was not him.
 *
 * Kept apart from [OutageLog] on purpose. Those totals judge the app's self-repair, and a switch
 * that is off is not something the app can repair — averaging the two would make both numbers mean
 * nothing, which is the lesson the stoppage list already prints.
 *
 * ## The clue: [Episode.how]
 *
 * `onDestroy` runs when the binding is taken down in an orderly way, and it now records what was
 * in front at that moment ([ServiceHealth.recordUnbind]). That separates:
 * - [How.SETTINGS_OPEN] — Settings (or Security) was in front with the screen on: looks like a hand;
 * - [How.SCREEN_OFF] / [How.ELSEWHERE] — unbound with the screen dark, or from somewhere else: not
 *   the toggle, whatever it was;
 * - [How.NOT_RUNNING] — no unbind belongs to this period at all: the switch went OFF while the
 *   watcher was not running, which is what a restart that comes back with it off looks like.
 *
 * Same rules as [OutageLog]: counts, states and durations only; the period is written when it
 * ENDS; lengths are monotonic, and a restart in the middle is reported as a floor rather than
 * guessed.
 *
 * ⚠️ **It only sees what a check sees.** A period opens when something calls the watchdog while
 * the switch reads OFF — resume, worker, alarm, tile, boot, the notification listener. The
 * few-second off/on toggle the repair screen asks for usually happens between two checks and leaves
 * no line, which is correct: that is not blocking being off in any sense that costs anything.
 */
object SwitchOffLog {

    private const val PREFS = "switch_off_log"

    private const val KEY_EPISODES = "episodes"

    private const val KEY_OPEN_STARTED = "open_started_at"
    private const val KEY_OPEN_STARTED_RT = "open_started_rt"
    private const val KEY_OPEN_FROM_BOOT = "open_from_boot"
    private const val KEY_OPEN_DETECTED = "open_detected_at"
    private const val KEY_OPEN_HOW = "open_how"
    private const val KEY_OPEN_GUARD = "open_guard_armed"
    private const val KEY_OPEN_PRECEDED = "open_preceded_by"
    private const val KEY_OPEN_BOOT = "open_boot_count"
    private const val KEY_OPEN_VERSION = "open_version"
    private const val KEY_OPEN_KILLED_BY = "open_killed_by"

    private const val KEY_TOTAL_COUNT = "total_count"
    private const val KEY_TOTAL_MS = "total_ms"
    private const val KEY_LONGEST_MS = "longest_ms"
    private const val KEY_TOTAL_USED_MIN = "total_used_min"
    private const val KEY_TOTAL_USED_COUNT = "total_used_count"

    /** Same ring size as [OutageLog], so the merged history can hold a week of both. */
    private const val MAX = 20

    /** How the switch came to be off, as far as the watcher could tell. */
    object How {
        /** Unbound with Settings or Security in front and the screen on — looks like a hand. */
        const val SETTINGS_OPEN = "settings-open"

        /** Unbound with the screen dark. Whatever did it, it was not someone at the toggle. */
        const val SCREEN_OFF = "screen-off"

        /** Unbound with the screen on and some other app in front. Not the toggle. */
        const val ELSEWHERE = "elsewhere"

        /** No unbind belongs to this period: it went OFF while the watcher was not running. */
        const val NOT_RUNNING = "not-running"

        /** An unbind recorded by a build that did not yet note what was in front. Never guessed. */
        const val UNKNOWN = "unknown"

        /** ⚠️ Every constant above. `decode` maps anything else to [UNKNOWN]. */
        val ALL = setOf(SETTINGS_OPEN, SCREEN_OFF, ELSEWHERE, NOT_RUNNING, UNKNOWN)
    }

    /**
     * What `onDestroy` saw the last time the watcher was unbound in an orderly way. Null fields
     * mean the stamp predates the build that records them — "not recorded", never "no".
     */
    data class Unbind(
        val at: Long,
        val settingsInFront: Boolean?,
        val screenOn: Boolean?,
        val guardArmed: Boolean?,
    )

    /** One finished period. */
    data class Episode(
        /** Wall clock, the best estimate of when it went off — see [startEstimate]. */
        val startedAt: Long,
        /** How long it was off, monotonically. After a restart mid-period this is the time since
         *  the boot, and [fromBoot] says so. */
        val durationMs: Long,
        /** How long before a check noticed, or -1 when that could not be worked out. */
        val detectedAfterMs: Long,
        /** One of [How]. */
        val how: String,
        /** Whether the off-switch guard was up at the unbind — null when no unbind belongs here. */
        val guardArmed: Boolean?,
        /** One of [OutageLog.Preceded]. */
        val precededBy: String,
        /** The phone restarted while the switch was off. */
        val rebooted: Boolean,
        /** [durationMs] is a floor measured from a boot, not the whole length. */
        val fromBoot: Boolean,
        /** Minutes of real use while it was off, or [OutageLog.UNKNOWN_USE]. */
        val usedDuringMin: Int,
        val versionCode: Long,
        /** Which check saw the switch back on — one of [OutageLog.EndedBy]. */
        val endedBy: String,
        /** What Android says closed the process — [ProcessExits.killedBy], taken when the period
         *  opened. For `how=not-running` it is the only witness there is. */
        val killedBy: String = ProcessExits.UNREAD,
        /** Wall-clock span from [startedAt] to the close — the window [usedDuringMin] is counted
         *  over — or -1 when it was not recorded. See [render]. */
        val spanMs: Long = -1L,
    ) {
        /**
         * A report-ready line that can never pass for an outage: it names itself, and it says
         * `off=` where an outage says `down=`.
         */
        fun render(): String {
            val mins = if (durationMs < 0) "?" else "${durationMs / 60_000}"
            val span = if (fromBoot) "${mins}min+fromBoot" else "${mins}min"
            val detect = if (detectedAfterMs < 0) "?" else "${detectedAfterMs / 60_000}"
            val used = if (usedDuringMin < 0) "?" else "$usedDuringMin"
            val guard = guardArmed?.toString() ?: "?"
            // ⚠️ On 13 Sep 2026 this line read `off=890min+fromBoot  used=772min`: the length ran from
            // a restart early that morning and the use from the last sign of life two days before,
            // and side by side the second read as a share of the first (invariant 73).
            val window = if (fromBoot && spanMs >= 0L) "  usedWindow=${spanMs / 60_000}min" else ""
            return "at=${StoppageHistory.label(startedAt)}  SWITCHED-OFF  off=$span  " +
                "used=${used}min$window  noticedAfter=${detect}min  how=$how  " +
                "killedBy=$killedBy  guard=$guard  after=$precededBy  rebooted=$rebooted  " +
                "build=$versionCode  backBy=$endedBy"
        }
    }

    /**
     * Who switched it off, as far as the last unbind can say. Pure, so every branch is tested.
     *
     * An unbind older than the watcher's last sign of life belongs to an earlier period — the
     * watcher came back after it — so it says nothing about this one.
     */
    internal fun classify(lastSeenAt: Long, unbind: Unbind?): String = when {
        unbind == null || unbind.at <= 0L || unbind.at < lastSeenAt -> How.NOT_RUNNING
        unbind.screenOn == false -> How.SCREEN_OFF
        unbind.settingsInFront == true -> How.SETTINGS_OPEN
        unbind.settingsInFront == false -> How.ELSEWHERE
        else -> How.UNKNOWN
    }

    /**
     * When it most likely went off: the later of the last sign of life and the last unbind.
     *
     * ⚠️ **Never before [notBefore].** When the same check has just closed a STALLED outage, that
     * outage already accounts for the time up to now; starting this period earlier would count the
     * same minutes twice. And never in the future, which a stamp written before a backward clock
     * change can be.
     */
    internal fun startEstimate(lastSeenAt: Long, unbindAt: Long, notBefore: Long, now: Long): Long {
        val best = maxOf(lastSeenAt, unbindAt, notBefore)
        return if (best <= 0L) now else minOf(best, now)
    }

    /**
     * Works out a period from raw readings. Pure for the reason [OutageLog.shape] is.
     *
     * **A restart in the middle does not make the length unknown here**, unlike an outage. The
     * switch was OFF when the period opened and is still OFF-or-just-back now, and a watcher that
     * had come back in between would have closed the period itself — so "at least since the boot"
     * is a true statement, and it is the one the owner's question turns on.
     */
    internal fun shape(
        startedAt: Long,
        startedRt: Long,
        fromBoot: Boolean,
        detectedAt: Long,
        nowRt: Long,
        bootAtOpen: Int,
        bootNow: Int,
        how: String,
        guardArmed: Boolean?,
        precededBy: String,
        versionCode: Long,
        endedBy: String,
        usedDuringMin: Int = OutageLog.UNKNOWN_USE,
        killedBy: String = ProcessExits.UNREAD,
        spanMs: Long = -1L,
    ): Episode {
        val rebooted = bootAtOpen != bootNow
        val duration = if (rebooted) nowRt.coerceAtLeast(0L) else (nowRt - startedRt).coerceAtLeast(0L)
        val detectedAfter =
            if (startedAt <= 0L || detectedAt <= 0L) -1L
            else (detectedAt - startedAt).coerceAtLeast(0L)
        return Episode(
            startedAt = startedAt,
            durationMs = duration,
            detectedAfterMs = detectedAfter,
            how = if (how in How.ALL) how else How.UNKNOWN,
            guardArmed = guardArmed,
            precededBy = if (precededBy in OutageLog.Preceded.ALL) precededBy else OutageLog.Preceded.NOTHING,
            rebooted = rebooted,
            fromBoot = fromBoot || rebooted,
            usedDuringMin = usedDuringMin.coerceAtLeast(OutageLog.UNKNOWN_USE),
            versionCode = versionCode,
            endedBy = if (endedBy in OutageLog.EndedBy.ALL) endedBy else OutageLog.EndedBy.UNKNOWN,
            killedBy = ProcessExits.safeToken(killedBy),
            spanMs = spanMs,
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The switch has just been found OFF. Opens a period if one isn't already open.
     *
     * @param lastSeenAt the watcher's last sign of life (alive or event stamp, whichever is later).
     * @param unbind the last orderly unbind, or null when there has never been one.
     * @param notBefore see [startEstimate]; 0 when no outage was closed by this same check.
     */
    fun begin(
        context: Context,
        lastSeenAt: Long,
        unbind: Unbind?,
        notBefore: Long = 0L,
        now: Long = System.currentTimeMillis(),
        /** Who closed the process — see [OutageLog.begin]'s parameter of the same name. */
        killedBy: () -> String = { ProcessExits.UNREAD },
    ) {
        runCatching {
            // Invariant 37: the same check-then-act as OutageLog.begin, reached by the same seven
            // callers of checkAndNotify.
            synchronized(this) {
                val p = prefs(context)
                if (p.contains(KEY_OPEN_STARTED)) return@runCatching
                val nowRt = SystemClock.elapsedRealtime()
                val startedAt = startEstimate(lastSeenAt, unbind?.at ?: 0L, notBefore, now)
                val anchor = OutageLog.startAnchor(startedAt, now, nowRt)
                val how = classify(lastSeenAt, unbind)
                // The guard reading belongs to the unbind; with no unbind it describes nothing.
                val guard = if (how == How.NOT_RUNNING) null else unbind?.guardArmed
                p.edit()
                    .putLong(KEY_OPEN_STARTED, startedAt)
                    .putLong(KEY_OPEN_STARTED_RT, anchor.startedRt)
                    .putBoolean(KEY_OPEN_FROM_BOOT, anchor.fromBoot)
                    .putLong(KEY_OPEN_DETECTED, now)
                    .putString(KEY_OPEN_HOW, how)
                    .putString(KEY_OPEN_GUARD, guard?.toString())
                    .putString(
                        KEY_OPEN_PRECEDED,
                        OutageLog.blame(startedAt, OutageLog.lastUpdateAt(context), now - nowRt),
                    )
                    .putInt(KEY_OPEN_BOOT, DeviceBoot.count(context))
                    .putLong(KEY_OPEN_VERSION, AppVersion.code(context))
                    .putString(
                        KEY_OPEN_KILLED_BY,
                        ProcessExits.safeToken(runCatching(killedBy).getOrNull()),
                    )
                    .apply()
            }
        }
    }

    /**
     * The switch is back on. Closes the open period and returns it, or null when none was open —
     * the ordinary case.
     *
     * @param usedMinutes minutes of use between the two instants, or [OutageLog.UNKNOWN_USE] —
     *   supplied by the caller at the close, for the reason [OutageLog.end] gives.
     */
    fun end(
        context: Context,
        endedBy: String,
        now: Long = System.currentTimeMillis(),
        usedMinutes: (from: Long, to: Long) -> Int = { _, _ -> OutageLog.UNKNOWN_USE },
    ): Episode? = runCatching {
        synchronized(this) {
            val p = prefs(context)
            if (!p.contains(KEY_OPEN_STARTED)) return@runCatching null
            val startedAt = p.getLong(KEY_OPEN_STARTED, 0L)
            val episode = shape(
                startedAt = startedAt,
                startedRt = p.getLong(KEY_OPEN_STARTED_RT, 0L),
                fromBoot = p.getBoolean(KEY_OPEN_FROM_BOOT, false),
                detectedAt = p.getLong(KEY_OPEN_DETECTED, 0L),
                nowRt = SystemClock.elapsedRealtime(),
                bootAtOpen = p.getInt(KEY_OPEN_BOOT, -1),
                bootNow = DeviceBoot.count(context),
                how = p.getString(KEY_OPEN_HOW, How.UNKNOWN) ?: How.UNKNOWN,
                guardArmed = p.getString(KEY_OPEN_GUARD, null)?.toBooleanStrictOrNull(),
                precededBy = p.getString(KEY_OPEN_PRECEDED, OutageLog.Preceded.NOTHING)
                    ?: OutageLog.Preceded.NOTHING,
                versionCode = p.getLong(KEY_OPEN_VERSION, -1L),
                endedBy = endedBy,
                usedDuringMin = runCatching { usedMinutes(startedAt, now) }
                    .getOrDefault(OutageLog.UNKNOWN_USE),
                killedBy = p.getString(KEY_OPEN_KILLED_BY, null) ?: ProcessExits.UNREAD,
                spanMs = if (startedAt > 0L) (now - startedAt).coerceAtLeast(0L) else -1L,
            )
            val existing = p.getString(KEY_EPISODES, "").orEmpty()
                .split(';').filter { it.isNotBlank() }
            val known = episode.durationMs.coerceAtLeast(0L)
            p.edit()
                .putString(KEY_EPISODES, (existing + encode(episode)).takeLast(MAX).joinToString(";"))
                .putInt(KEY_TOTAL_COUNT, p.getInt(KEY_TOTAL_COUNT, 0) + 1)
                .putLong(KEY_TOTAL_MS, p.getLong(KEY_TOTAL_MS, 0L) + known)
                .putLong(KEY_LONGEST_MS, maxOf(p.getLong(KEY_LONGEST_MS, 0L), known))
                .putInt(
                    KEY_TOTAL_USED_MIN,
                    p.getInt(KEY_TOTAL_USED_MIN, 0) + episode.usedDuringMin.coerceAtLeast(0),
                )
                .putInt(
                    KEY_TOTAL_USED_COUNT,
                    p.getInt(KEY_TOTAL_USED_COUNT, 0) + if (episode.usedDuringMin >= 0) 1 else 0,
                )
                .remove(KEY_OPEN_STARTED)
                .remove(KEY_OPEN_STARTED_RT)
                .remove(KEY_OPEN_FROM_BOOT)
                .remove(KEY_OPEN_DETECTED)
                .remove(KEY_OPEN_HOW)
                .remove(KEY_OPEN_GUARD)
                .remove(KEY_OPEN_PRECEDED)
                .remove(KEY_OPEN_BOOT)
                .remove(KEY_OPEN_VERSION)
                .remove(KEY_OPEN_KILLED_BY)
                .apply()
            episode
        }
    }.getOrNull()

    /** True while the switch is known to be off and a period is open. */
    fun isOpen(context: Context): Boolean =
        runCatching { prefs(context).contains(KEY_OPEN_STARTED) }.getOrDefault(false)

    /** Every finished period, newest first. */
    internal fun recentEpisodes(context: Context): List<Episode> = runCatching {
        prefs(context).getString(KEY_EPISODES, "").orEmpty()
            .split(';').filter { it.isNotBlank() }
            .mapNotNull { decode(it) }
            .reversed()
    }.getOrDefault(emptyList())

    /** The most recent finished period, or null before there has been one. */
    fun last(context: Context): Episode? = recentEpisodes(context).firstOrNull()

    /** Lifetime totals, in millis and minutes. */
    data class Totals(
        val count: Int = 0,
        val totalMs: Long = 0L,
        val longestMs: Long = 0L,
        /** Minutes of real use across every period, and how many periods that figure covers. */
        val usedMin: Int = 0,
        val usedCount: Int = 0,
    )

    fun totals(context: Context): Totals = runCatching {
        val p = prefs(context)
        Totals(
            count = p.getInt(KEY_TOTAL_COUNT, 0),
            totalMs = p.getLong(KEY_TOTAL_MS, 0L),
            longestMs = p.getLong(KEY_LONGEST_MS, 0L),
            usedMin = p.getInt(KEY_TOTAL_USED_MIN, 0),
            usedCount = p.getInt(KEY_TOTAL_USED_COUNT, 0),
        )
    }.getOrDefault(Totals())

    internal fun encode(e: Episode): String = listOf(
        e.startedAt, e.durationMs, e.detectedAfterMs, e.how, e.guardArmed?.toString() ?: "?",
        e.precededBy, e.rebooted, e.fromBoot, e.usedDuringMin, e.versionCode, e.endedBy,
        e.killedBy, e.spanMs,
    ).joinToString("|")

    internal fun decode(raw: String): Episode? {
        val p = raw.split('|')
        // Eleven fields until 14 Sep 2026 and thirteen since; the older rows are already on his phone.
        if (p.size != 11 && p.size != 13) return null
        return Episode(
            startedAt = p[0].toLongOrNull() ?: return null,
            durationMs = p[1].toLongOrNull() ?: return null,
            detectedAfterMs = p[2].toLongOrNull() ?: return null,
            how = p[3].takeIf { it in How.ALL } ?: How.UNKNOWN,
            guardArmed = p[4].toBooleanStrictOrNull(),
            precededBy = p[5].takeIf { it in OutageLog.Preceded.ALL } ?: OutageLog.Preceded.NOTHING,
            rebooted = p[6].toBoolean(),
            fromBoot = p[7].toBoolean(),
            usedDuringMin = p[8].toIntOrNull() ?: OutageLog.UNKNOWN_USE,
            versionCode = p[9].toLongOrNull() ?: -1L,
            endedBy = p[10].takeIf { it in OutageLog.EndedBy.ALL } ?: OutageLog.EndedBy.UNKNOWN,
            killedBy = ProcessExits.safeToken(p.getOrNull(11)),
            spanMs = p.getOrNull(12)?.toLongOrNull() ?: -1L,
        )
    }
}
