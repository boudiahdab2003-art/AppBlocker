package com.appblocker.service

import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.appblocker.data.NotificationCounter
import com.appblocker.data.OutageLog

/**
 * Counts incoming notifications for the Insights "Distractions" card. Optional — only active
 * while the user has granted Notification Access. We never read notification content; we only
 * increment a per-day counter. Ongoing/group-summary notifications are skipped so the count
 * reflects real interruptions, not persistent status items or duplicate group headers.
 */
class NotificationCountListener : NotificationListenerService() {

    /** Last health check from here, monotonic (invariant 9). */
    @Volatile private var lastHealthCheckAt = 0L

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Piggy-backed here because this service is bound whenever Notification Access is granted,
        // in the same process as the watcher — so it is alive and being called precisely when the
        // watcher may not be. Throttled hard: notifications arrive in bursts, and the check reads
        // Settings and usage stats.
        val now = SystemClock.elapsedRealtime()
        if (now - lastHealthCheckAt >= HEALTH_CHECK_MS) {
            lastHealthCheckAt = now
            ProtectionWatchdog.checkAndNotify(applicationContext, calledBy = OutageLog.EndedBy.GLANCED)
        }
        sbn ?: return
        if (sbn.isOngoing) return
        val flags = sbn.notification?.flags ?: 0
        if (flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return
        NotificationCounter.recordNotification(applicationContext)
    }
}

private const val HEALTH_CHECK_MS = 2 * 60_000L
