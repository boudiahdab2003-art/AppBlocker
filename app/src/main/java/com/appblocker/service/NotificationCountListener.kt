package com.appblocker.service

import android.os.Handler
import android.os.Looper
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
 *
 * ⭐ **It is also the one part of AppBlocker that Android restarts by itself** (invariant 76). While
 * Notification access is granted, Android keeps this service bound — and on 14 Sep 2026, on the
 * API 35 emulator, it restarted AppBlocker's process one second after a force stop, where without
 * the access nothing of ours ran at all. A force stop is the shape of the owner's gap of 11–13 Sep:
 * the switch ended up OFF and nothing ran for two days, because a force stop also cancels every alarm
 * and job — and the only health check here waited for a notification to arrive. So a reconnect
 * re-arms the scheduler and looks at blocking itself.
 */
class NotificationCountListener : NotificationListenerService() {

    /** Last health check from here, monotonic (invariant 9). */
    @Volatile private var lastHealthCheckAt = 0L

    private val handler = Handler(Looper.getMainLooper())

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Everything a force stop cancelled. KEEP and idempotent, so the reconnect of a healthy
        // process costs one WorkManager lookup.
        runCatching { ProtectionScheduler.ensureScheduled(applicationContext) }
        handler.removeCallbacks(connectCheck)
        handler.postDelayed(connectCheck, CONNECT_CHECK_DELAY_MS)
    }

    override fun onListenerDisconnected() {
        handler.removeCallbacks(connectCheck)
        super.onListenerDisconnected()
    }

    /**
     * The check a reconnect owes, on this process's own clock — not a WorkManager job, the scheduler
     * his phone throttles hardest (`workerSilent` 377 on 14 Sep). It waits past the watchdog's bind
     * grace so a watcher that is genuinely not coming back is judged rather than deferred. Nobody is
     * necessarily looking at the phone, so it reports itself as BACKGROUND, not GLANCED.
     */
    private val connectCheck = Runnable {
        lastHealthCheckAt = SystemClock.elapsedRealtime()
        ProtectionWatchdog.checkAndNotify(applicationContext, calledBy = OutageLog.EndedBy.BACKGROUND)
    }

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

/** After a reconnect, when the listener looks at blocking itself: past [SERVICE_BIND_GRACE_MS], so a
 *  seconds-old process does not hand its own verdict to a throttled job. */
private const val CONNECT_CHECK_DELAY_MS = SERVICE_BIND_GRACE_MS + 5_000L
