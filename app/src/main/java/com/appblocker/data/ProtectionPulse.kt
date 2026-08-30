package com.appblocker.data

import android.content.Context
import android.os.SystemClock

/**
 * **Whether WorkManager is still running at all**, which nothing on this phone could answer.
 *
 * Every out-of-process check the app has — the fifteen-minute periodic watchdog, the five-minute
 * stalled repeat, the forty-five-second and three-minute rechecks — is a WorkManager job. That is
 * one wake source, and `ProtectionScheduler` admits in its own KDoc that it is the *wrong* one for
 * this job: *"WorkManager does not guarantee five-minute precision under Doze or an OEM battery
 * manager — and those are the very conditions that kill the watcher in the first place, so the
 * alert about the failure is subject to the same force as the failure."*
 *
 * So the worker leaves a pulse here every time it runs, and a second, independent wake source (an
 * inexact alarm — see `ProtectionAlarmReceiver`) checks the pulse and does **nothing at all**
 * unless it has stopped. That narrowness is the design:
 *
 * - It is not a second copy of the watchdog. Running `checkAndNotify` on two timers would double
 *   the background work — a usage-events walk each time — to answer a question already being
 *   asked, against the owner's "keep the battery as it is". On a healthy phone this costs one
 *   prefs read per alarm and stops.
 * - ⚠️ **It is not independent of the failure being chased, and pretending otherwise would be the
 *   mistake.** An OEM that force-stops the app cancels its alarms exactly as it cancels its jobs.
 *   What this buys is *diversity* — Doze and app-standby throttle the two mechanisms differently —
 *   and, more valuable than either, **a number nobody has**: how often WorkManager stops running
 *   on his phone, which is a live hypothesis for the whole outage problem and has never been
 *   measured.
 *
 * Elapsed time is monotonic (invariant 9), and a reboot in between makes the answer honestly
 * unknown rather than a wall-clock guess — the same rule [OutageLog] follows for a duration.
 */
object ProtectionPulse {

    private const val PREFS = "protection_pulse"
    private const val KEY_RT = "last_run_rt"
    private const val KEY_BOOT = "last_run_boot"
    private const val KEY_SILENT_COUNT = "worker_silent_count"

    /** "Can't tell" — no stamp yet, or a reboot since. Never treated as silence. */
    const val UNKNOWN = -1L

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * How long since the stamp, or [UNKNOWN].
     *
     * Pure, so the reboot rule can be stated rather than trusted — the same split [OutageLog.shape]
     * uses, and for the same reason: unit tests run with `isReturnDefaultValues = true`, so an
     * un-mocked `SystemClock.elapsedRealtime()` silently returns 0.
     *
     * A reboot resets the monotonic clock, so the difference across one is not an interval. It also
     * genuinely is not evidence: everything is freshly scheduled after a boot, and the receiver
     * re-stamps on the way through.
     */
    internal fun sinceStamp(
        stampedRt: Long,
        stampedBoot: Int,
        nowRt: Long,
        nowBoot: Int,
    ): Long = when {
        stampedRt <= 0L -> UNKNOWN
        // An unreadable boot counter is -1 on both sides and compares equal, which is right:
        // "can't tell" must not invent a reboot (invariant 11).
        stampedBoot != nowBoot -> UNKNOWN
        else -> (nowRt - stampedRt).coerceAtLeast(0L)
    }

    /** The worker ran. Called from [com.appblocker.service.ProtectionCheckWorker]. */
    fun stamp(context: Context) {
        runCatching {
            prefs(context).edit()
                .putLong(KEY_RT, SystemClock.elapsedRealtime())
                .putInt(KEY_BOOT, DeviceBoot.count(context))
                .apply()
        }
    }

    /**
     * Stamps only if nothing is stamped for this boot, so "silence" is measured from when the
     * alarm was armed rather than from the beginning of time.
     *
     * Without this, a phone where WorkManager has **never** run reads as [UNKNOWN] forever, and
     * the one failure worth catching — the worker never starting at all — is the one case that
     * would go unnoticed.
     */
    fun ensureBaseline(context: Context) {
        runCatching {
            val p = prefs(context)
            val boot = DeviceBoot.count(context)
            if (p.getLong(KEY_RT, 0L) <= 0L || p.getInt(KEY_BOOT, -2) != boot) stamp(context)
        }
    }

    /** Millis since the worker last ran, or [UNKNOWN]. */
    fun silentFor(context: Context): Long = runCatching {
        val p = prefs(context)
        sinceStamp(
            stampedRt = p.getLong(KEY_RT, 0L),
            stampedBoot = p.getInt(KEY_BOOT, -2),
            nowRt = SystemClock.elapsedRealtime(),
            nowBoot = DeviceBoot.count(context),
        )
    }.getOrDefault(UNKNOWN)

    /** How many times the alarm has found WorkManager silent. The measurement this exists for. */
    fun recordSilent(context: Context) {
        runCatching {
            val p = prefs(context)
            p.edit().putInt(KEY_SILENT_COUNT, p.getInt(KEY_SILENT_COUNT, 0) + 1).apply()
        }
    }

    fun silentCount(context: Context): Int =
        runCatching { prefs(context).getInt(KEY_SILENT_COUNT, 0) }.getOrDefault(0)
}
