package com.appblocker.service

import android.content.Context
import android.os.SystemClock
import com.appblocker.data.BlockLatency
import com.appblocker.data.BugReportQueue
import com.appblocker.data.HealthFacts
import com.appblocker.data.OutageLog
import com.appblocker.data.ProtectionPulse
import com.appblocker.data.ServiceHealth
import com.appblocker.data.SettingsStore
import com.appblocker.data.SilenceLog

/**
 * Reads the phone once and hands [HealthFacts] the numbers it needs.
 *
 * Split out because [HealthFacts.verdicts] is pure and therefore testable, and this half — prefs,
 * system services, the watcher's live binding — is the half a JVM test cannot reach. Everything
 * interpretive lives on the other side of this line; nothing here decides whether a number is bad.
 *
 * Lives in `service` rather than `data` because it needs [ProtectionWatchdog] and
 * [BlockerAccessibilityService], and `data` must not depend upwards on those.
 *
 * **Never throws.** Both callers are on paths where something is already wrong — a bug report
 * being written, or the diagnostics screen the owner opens *because* blocking failed — and a
 * diagnostic that crashes the screen explaining the crash is worse than one that omits a row.
 * A failed read degrades to the value that means "cannot tell" for that field.
 */
object HealthReader {

    /**
     * @param watch a reading already taken by the caller. **Pass it whenever you have one** —
     *   [ProtectionWatchdog.read] walks the usage-event stream from the watcher's last event until
     *   now, which is the most expensive thing a report does, and a bug report needs the same
     *   reading for its settings table and for these verdicts. Taking it twice put two of those
     *   walks inside the uncaught-exception handler.
     */
    internal fun read(
        ctx: Context,
        now: Long = System.currentTimeMillis(),
        watch: ProtectionWatchdog.Reading? = null,
    ): HealthFacts.Reading {
        // One look, so the state and the numbers behind it cannot disagree — see Reading's KDoc.
        val reading = watch ?: runCatching { ProtectionWatchdog.read(ctx, now) }.getOrNull()
        val quick = runCatching { BlockLatency.quickShare(ctx) }.getOrNull()
        val buckets = runCatching {
            (0 until BlockLatency.SIZE).map { BlockLatency.get(ctx, it).total }
        }.getOrDefault(emptyList())
        val totals = runCatching { OutageLog.totals(ctx) }
            .getOrDefault(OutageLog.Totals(0, 0L, 0L))
        return HealthFacts.Reading(
            serviceEnabled = safe(false) { AccessibilityUtil.isEnabled(ctx) },
            serviceRunning = safe(false) { BlockerAccessibilityService.isConnected() },
            sinceLastEventMs = since(now) { ServiceHealth.lastEventAt(ctx) },
            sinceAliveMs = since(now) { ServiceHealth.lastAliveAt(ctx) },
            usedMinutes = reading?.usedMinutes,
            processAgeMs = reading?.sinceProcessStartMs
                ?: safe(0L) { SystemClock.elapsedRealtime() },
            updatePaused = safe(false) { SettingsStore.updatePaused(ctx) },
            updatePausePending = safe(false) { SettingsStore.updatePausePending(ctx) },
            updatePausePendingMs = since(now) { SettingsStore.updatePausePendingAt(ctx) },
            foundDead = safe(0) { ServiceHealth.foundDeadCount(ctx) },
            outageOpen = safe(false) { OutageLog.isOpen(ctx) },
            outageCount = totals.count,
            outageTotalMs = totals.totalMs,
            outageTimedMs = totals.timedMs,
            outageTimedCount = totals.timedCount,
            outageLongestMs = totals.longestMs,
            probeFailStreak = safe(0) { ServiceHealth.probeFailStreak(ctx) },
            bindDeferrals = safe(0) { SettingsStore.bindDeferrals(ctx) },
            // UNKNOWN (-1) means the scheduler has never been seen to run at all, which is not the
            // same as "ran a long time ago" — pass it through rather than flattening to a duration.
            workerSilentMs = safe(ProtectionPulse.UNKNOWN) { ProtectionPulse.silentFor(ctx) },
            quickSharePercent = quick,
            blocksMeasured = buckets.sum(),
            slowBlocks = buckets.lastOrNull() ?: 0,
            deafSpells = safe(0) { SilenceLog.get(ctx, SilenceLog.DEAF_DISMISSALS).total },
            lateSkips = safe(0) { SilenceLog.get(ctx, SilenceLog.LATE_DECLINES).total },
            unreadyDecisions = safe(0) { SilenceLog.get(ctx, SilenceLog.UNREADY_DECISIONS).total },
            unreadyBlind = safe(0) { SilenceLog.get(ctx, SilenceLog.UNREADY_BLIND).total },
            shortsBlind = safe(0) { SilenceLog.get(ctx, SilenceLog.SHORTS_EXIT_BLIND).total },
            queuedReports = safe(0) { BugReportQueue.pending(ctx).size },
            reportsLeftToday = safe(0) { BugReportQueue.remainingToday(ctx) },
            reportingOn = safe(false) { BugReportSender.enabled() },
            lastSendResult = runCatching { BugReportQueue.lastResult(ctx) }.getOrNull(),
            sinceLastSendMs = since(now) { BugReportQueue.lastAttemptAt(ctx) },
        )
    }

    private inline fun <T> safe(fallback: T, read: () -> T): T =
        runCatching(read).getOrDefault(fallback)

    /** Millis since a stored wall-clock stamp, or -1 for "never", which every caller renders. */
    private inline fun since(now: Long, read: () -> Long): Long {
        val at = runCatching(read).getOrDefault(0L)
        return if (at <= 0L) -1L else now - at
    }
}
