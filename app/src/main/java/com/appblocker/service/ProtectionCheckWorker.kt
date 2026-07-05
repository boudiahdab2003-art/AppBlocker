package com.appblocker.service

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Periodic background check — the AccessibilityUtil read is a synchronous Settings lookup,
 *  so a plain Worker is enough, no coroutine machinery needed. */
class ProtectionCheckWorker(context: Context, params: WorkerParameters) :
    Worker(context, params) {
    override fun doWork(): Result {
        ProtectionWatchdog.checkAndNotify(applicationContext)
        return Result.success()
    }
}
