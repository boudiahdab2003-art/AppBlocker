package com.appblocker.data

import android.content.Context
import android.os.SystemClock

/**
 * **How long blocking stood paused after an update, and how much of the phone was used meanwhile.**
 *
 * The after-update pause ([UpdatePause]) switches all blocking off except the adult layer until
 * Reactivate is tapped, with the watcher healthy and the accessibility switch reading ON. The
 * watchdog answered it with one notification every four hours and recorded nothing: no episode, no
 * stoppage line, no report. So on 24 Oct 2026 he used Instagram from after work until 2 am with
 * nothing blocking it, and his main phone — silent since the v1.176 install two days earlier — had
 * no way to say so (invariant 91). Each space pauses on its own, and the pause in the space he is
 * not looking at is the one nobody taps.
 *
 * Kept apart from [OutageLog] and [SwitchOffLog] for the reason they are kept apart from each other:
 * those totals judge the self-repair and the switch, and a pause is neither. Only the lines are
 * merged, in [StoppageHistory].
 *
 * Same rules as its siblings: counts and durations only; an episode is written when it ENDS; the
 * length is monotonic, and a restart in the middle makes it a floor rather than a guess.
 */
object PauseLog {

    private const val PREFS = "pause_log"

    private const val KEY_EPISODES = "episodes"

    private const val KEY_OPEN_STARTED = "open_started_at"
    private const val KEY_OPEN_STARTED_RT = "open_started_rt"
    private const val KEY_OPEN_FROM_BOOT = "open_from_boot"
    private const val KEY_OPEN_BOOT = "open_boot_count"
    private const val KEY_OPEN_VERSION = "open_version"

    private const val KEY_TOTAL_COUNT = "total_count"
    private const val KEY_TOTAL_MS = "total_ms"
    private const val KEY_LONGEST_MS = "longest_ms"
    private const val KEY_TOTAL_USED_MIN = "total_used_min"
    private const val KEY_TOTAL_USED_COUNT = "total_used_count"

    /** Same ring size as [OutageLog]. */
    private const val MAX = 20

    /** How a pause came to an end, as the watchdog saw it. */
    object EndedBy {
        /** The flag was lifted — Reactivate, nearly always. */
        const val REACTIVATED = "reactivated"

        /** The watcher died while paused; the stoppage that [OutageLog] opened takes over. */
        const val STOPPED = "stopped"

        /** The accessibility switch was found OFF; [SwitchOffLog] takes over. */
        const val SWITCHED_OFF = "switched-off"

        const val UNKNOWN = "unknown"

        /** ⚠️ Every constant above. `decode` maps anything else to [UNKNOWN]. */
        val ALL = setOf(REACTIVATED, STOPPED, SWITCHED_OFF, UNKNOWN)
    }

    /** One finished pause. */
    data class Episode(
        /** Wall clock, when the pause began — see [startEstimate]. */
        val startedAt: Long,
        /** How long it stood, monotonically; after a restart mid-pause, the time since the boot. */
        val durationMs: Long,
        val rebooted: Boolean,
        /** [durationMs] is a floor measured from a boot, not the whole length. */
        val fromBoot: Boolean,
        /** Minutes of real use while blocking was paused, or [OutageLog.UNKNOWN_USE]. */
        val usedDuringMin: Int,
        val versionCode: Long,
        /** One of [EndedBy]. */
        val endedBy: String,
    ) {
        /** A report-ready line that names itself, so it can never pass for an outage. */
        fun render(): String {
            val mins = if (durationMs < 0) "?" else "${durationMs / 60_000}"
            val span = if (fromBoot) "${mins}min+fromBoot" else "${mins}min"
            val used = if (usedDuringMin < 0) "?" else "$usedDuringMin"
            return "at=${StoppageHistory.label(startedAt)}  PAUSED  paused=$span  used=${used}min  " +
                "rebooted=$rebooted  build=$versionCode  backBy=$endedBy"
        }
    }

    /**
     * When the pause most likely began: its own stamp, else the version change that armed it, else
     * now — and **never before [notBefore]**, which is set when the same check has just closed a
     * stoppage that already accounts for those minutes. Never in the future either.
     */
    internal fun startEstimate(pausedAt: Long, updateAt: Long, notBefore: Long, now: Long): Long {
        val own = if (pausedAt > 0L) pausedAt else updateAt
        val best = maxOf(own, notBefore)
        return if (best <= 0L) now else minOf(best, now)
    }

    /** Works out a pause from raw readings. Pure, so the arithmetic is tested. */
    internal fun shape(
        startedAt: Long,
        startedRt: Long,
        fromBoot: Boolean,
        nowRt: Long,
        bootAtOpen: Int,
        bootNow: Int,
        versionCode: Long,
        endedBy: String,
        usedDuringMin: Int = OutageLog.UNKNOWN_USE,
    ): Episode {
        val rebooted = bootAtOpen != bootNow
        val duration = if (rebooted) nowRt.coerceAtLeast(0L) else (nowRt - startedRt).coerceAtLeast(0L)
        return Episode(
            startedAt = startedAt,
            durationMs = duration,
            rebooted = rebooted,
            fromBoot = fromBoot || rebooted,
            usedDuringMin = usedDuringMin.coerceAtLeast(OutageLog.UNKNOWN_USE),
            versionCode = versionCode,
            endedBy = if (endedBy in EndedBy.ALL) endedBy else EndedBy.UNKNOWN,
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Blocking has been found paused. Opens an episode if one isn't already open. */
    fun begin(
        context: Context,
        notBefore: Long = 0L,
        now: Long = System.currentTimeMillis(),
    ) {
        runCatching {
            // Invariant 37: the same check-then-act as OutageLog.begin, reached by the same callers.
            synchronized(this) {
                val p = prefs(context)
                if (p.contains(KEY_OPEN_STARTED)) return@runCatching
                val nowRt = SystemClock.elapsedRealtime()
                val startedAt = startEstimate(
                    SettingsStore.updatePausedAt(context),
                    OutageLog.lastUpdateAt(context),
                    notBefore,
                    now,
                )
                val anchor = OutageLog.startAnchor(startedAt, now, nowRt)
                p.edit()
                    .putLong(KEY_OPEN_STARTED, startedAt)
                    .putLong(KEY_OPEN_STARTED_RT, anchor.startedRt)
                    .putBoolean(KEY_OPEN_FROM_BOOT, anchor.fromBoot)
                    .putInt(KEY_OPEN_BOOT, DeviceBoot.count(context))
                    .putLong(KEY_OPEN_VERSION, AppVersion.code(context))
                    .apply()
            }
        }
    }

    /**
     * Blocking is no longer paused. Closes the open episode and returns it, or null when none was
     * open — the ordinary case.
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
                nowRt = SystemClock.elapsedRealtime(),
                bootAtOpen = p.getInt(KEY_OPEN_BOOT, -1),
                bootNow = DeviceBoot.count(context),
                versionCode = p.getLong(KEY_OPEN_VERSION, -1L),
                endedBy = endedBy,
                usedDuringMin = runCatching { usedMinutes(startedAt, now) }
                    .getOrDefault(OutageLog.UNKNOWN_USE),
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
                .remove(KEY_OPEN_BOOT)
                .remove(KEY_OPEN_VERSION)
                .apply()
            episode
        }
    }.getOrNull()

    fun isOpen(context: Context): Boolean =
        runCatching { prefs(context).contains(KEY_OPEN_STARTED) }.getOrDefault(false)

    /** Wall clock the open pause began, or 0 when none is open. */
    fun openStartedAt(context: Context): Long = runCatching {
        prefs(context).getLong(KEY_OPEN_STARTED, 0L)
    }.getOrDefault(0L)

    /** How long the open pause has stood, monotonically (a floor after a restart), or -1 when none is. */
    fun openForMs(context: Context): Long = runCatching {
        val p = prefs(context)
        if (!p.contains(KEY_OPEN_STARTED)) return@runCatching -1L
        val nowRt = SystemClock.elapsedRealtime()
        if (p.getInt(KEY_OPEN_BOOT, -1) != DeviceBoot.count(context)) nowRt
        else (nowRt - p.getLong(KEY_OPEN_STARTED_RT, 0L)).coerceAtLeast(0L)
    }.getOrDefault(-1L)

    /** Every finished pause, newest first. */
    internal fun recentEpisodes(context: Context): List<Episode> = runCatching {
        prefs(context).getString(KEY_EPISODES, "").orEmpty()
            .split(';').filter { it.isNotBlank() }
            .mapNotNull { decode(it) }
            .reversed()
    }.getOrDefault(emptyList())

    /** Lifetime totals, in millis and minutes. */
    data class Totals(
        val count: Int = 0,
        val totalMs: Long = 0L,
        val longestMs: Long = 0L,
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
        e.startedAt, e.durationMs, e.rebooted, e.fromBoot, e.usedDuringMin, e.versionCode, e.endedBy,
    ).joinToString("|")

    internal fun decode(raw: String): Episode? {
        val p = raw.split('|')
        if (p.size != 7) return null
        return Episode(
            startedAt = p[0].toLongOrNull() ?: return null,
            durationMs = p[1].toLongOrNull() ?: return null,
            rebooted = p[2].toBoolean(),
            fromBoot = p[3].toBoolean(),
            usedDuringMin = p[4].toIntOrNull() ?: OutageLog.UNKNOWN_USE,
            versionCode = p[5].toLongOrNull() ?: -1L,
            endedBy = p[6].takeIf { it in EndedBy.ALL } ?: EndedBy.UNKNOWN,
        )
    }
}
