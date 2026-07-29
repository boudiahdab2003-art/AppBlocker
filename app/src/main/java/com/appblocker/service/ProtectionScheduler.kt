package com.appblocker.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
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
