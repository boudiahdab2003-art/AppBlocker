package com.appblocker.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
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
    }
}
