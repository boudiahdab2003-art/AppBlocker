package com.appblocker.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Arms the periodic protection-off check. Safe to call on every launch/boot — KEEP means an
 *  already-scheduled run is left untouched rather than restarting its 15-minute cycle. */
object ProtectionScheduler {
    private const val WORK_NAME = "protection_watchdog"

    fun ensureScheduled(context: Context) {
        // No battery/idle constraints: this check is what protects the user, so it must keep
        // running regardless of battery state — and it's a single cheap Settings read anyway.
        val request = PeriodicWorkRequestBuilder<ProtectionCheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(false).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        ensureUpdateScheduled(context)
    }

    private const val STALLED_WORK_NAME = "protection_stalled_repeat"

    /**
     * Comes back in five minutes while blocking is dead, so the alert can float again.
     *
     * **A one-shot, re-armed each time, because WorkManager's *periodic* minimum is 15 minutes**
     * — the request above is at that floor already. Five minutes is only reachable as a chain of
     * one-shots, each enqueued by the check that found the phone still stalled.
     *
     * `REPLACE`, not `KEEP`: every check that still sees STALLED restarts the five minutes, so the
     * repeat follows the last thing that actually happened rather than a stale schedule.
     *
     * **What this cannot promise, said here rather than in a release note.** WorkManager does not
     * guarantee five-minute precision under Doze or an OEM battery manager — and those are the
     * very conditions that kill the watcher in the first place, so the alert about the failure is
     * subject to the same force as the failure. No constraints are set for exactly that reason,
     * and the owner's battery is already unrestricted, which makes it likely rather than certain.
     * The app-open, shade-pull and tile checks are immediate and unaffected.
     */
    fun scheduleStalledRepeat(context: Context) {
        val request = OneTimeWorkRequestBuilder<ProtectionCheckWorker>()
            .setInitialDelay(5, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(false).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(STALLED_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** Blocking is healthy again — stop coming back. Called from the watchdog's OK branch beside
     *  the notification cancel, so the repeat can never outlive the thing it is about. */
    fun cancelStalledRepeat(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(STALLED_WORK_NAME)
    }

    private const val RECHECK_WORK_NAME = "protection_recheck_soon"
    private const val RECHECK_AFTER_UPDATE_NAME = "protection_recheck_after_update"

    /**
     * Look again in three quarters of a minute, for the moment where a single check cannot tell
     * "healthy" from "not bound yet".
     *
     * **Why 45 seconds.** `SERVICE_BIND_GRACE_MS` forgives an unbound watcher for the first 20
     * seconds after our process starts. When WorkManager cold-starts that process *in order to
     * run the check*, the process is about two seconds old — so the check lands inside its own
     * grace and cannot answer, and before this it answered OK anyway. 45 seconds puts the retry
     * past the grace with the same process still up.
     *
     * `KEEP`, not `REPLACE`: several triggers can ask for this at once — the worker, the boot
     * receiver, an app open — and each replacement would push the answer further away, which is
     * the opposite of the point.
     */
    fun scheduleRecheckSoon(context: Context) {
        val request = OneTimeWorkRequestBuilder<ProtectionCheckWorker>()
            .setInitialDelay(45, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(false).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(RECHECK_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Check again three minutes after our own update landed.
     *
     * Installing our APK is Android killing this process (invariant 21), and whether the
     * accessibility service is rebound afterwards is the OEM's decision, not ours. Without this
     * the first look after an update is whenever the 15-minute periodic tick next fires — so a
     * rebind that never happened costs up to a quarter of an hour of unprotected phone on every
     * release, and the owner takes several releases a week. A separate work name from
     * [scheduleRecheckSoon] so the two cannot cancel each other: together they cover 45 seconds
     * and three minutes after the install.
     */
    fun scheduleRecheckAfterUpdate(context: Context) {
        val request = OneTimeWorkRequestBuilder<ProtectionCheckWorker>()
            .setInitialDelay(3, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(false).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(RECHECK_AFTER_UPDATE_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    private const val UPDATE_WORK_NAME = "auto_update"

    /**
     * Arms the silent self-update check. Six-hourly and on an unmetered network only — the
     * opposite trade-off from the watchdog above, deliberately: that one protects the owner and
     * must run whatever the battery says, this one is a convenience and must never be the reason
     * his data allowance or his battery went. A release that lands six hours late has cost
     * nothing; today's fixes sat unread for far longer than that.
     */
    private fun ensureUpdateScheduled(context: Context) {
        if (!com.appblocker.Dist.SELF_UPDATE) return
        val request = PeriodicWorkRequestBuilder<AutoUpdateWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UPDATE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
