package com.appblocker.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.appblocker.data.NotificationCounter

/**
 * Counts incoming notifications for the Insights "Distractions" card. Optional — only active
 * while the user has granted Notification Access. We never read notification content; we only
 * increment a per-day counter. Ongoing/group-summary notifications are skipped so the count
 * reflects real interruptions, not persistent status items or duplicate group headers.
 */
class NotificationCountListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.isOngoing) return
        val flags = sbn.notification?.flags ?: 0
        if (flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return
        NotificationCounter.recordNotification(applicationContext)
    }
}
