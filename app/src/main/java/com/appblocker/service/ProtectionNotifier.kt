package com.appblocker.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.appblocker.MainActivity
import com.appblocker.R
import com.appblocker.data.SettingsStore
import java.util.concurrent.TimeUnit

/** Alerts the user if AppBlocker's Accessibility service gets silently turned off. */
object ProtectionNotifier {
    private const val CHANNEL_ID = "protection_off"
    private const val NOTIF_ID = 1001

    // Once shown, don't nag again until this much time has passed while still disabled.
    private val MIN_RENOTIFY_MS = TimeUnit.HOURS.toMillis(4)

    // The app's established "needs attention" amber (Permissions.kt's "Required" label,
    // BlockEditorScreen.kt's ProtectionBanner) — distinct from the blue/violet used for
    // positive/primary actions elsewhere, so this reads as urgent rather than routine.
    private val ACCENT_COLOR = 0xFFFFB020.toInt()

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Protection alerts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Tells you if AppBlocker's blocking service gets turned off."
            enableLights(true)
            lightColor = ACCENT_COLOR
            enableVibration(true)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Posts the "protection turned off" alert.
     *
     * @param force when true (the user actively opened the app), always post regardless of the
     *   4-hour throttle — showing the true current state on a deliberate open is never spammy.
     *   The background worker calls with force=false so it stays rate-limited.
     */
    @SuppressLint("MissingPermission") // guarded by the areNotificationsEnabled() check below.
    fun notifyDisabled(context: Context, force: Boolean = false) {
        val manager = NotificationManagerCompat.from(context)
        // If notifications are off (permission denied / channel blocked), bail WITHOUT consuming
        // the cooldown — otherwise a check that couldn't actually post would still "use up" the
        // 4-hour window and suppress the real notification later once notifications are allowed.
        if (!manager.areNotificationsEnabled()) return

        val now = System.currentTimeMillis()
        if (!force) {
            val last = SettingsStore.protectionLastNotifiedAt(context)
            if (now - last < MIN_RENOTIFY_MS) return
        }

        val notification = build(
            context,
            title = "Your protection just turned off",
            collapsed = "AppBlocker can no longer block apps — tap to fix in seconds.",
            expanded = "Android (or something on your device) turned off AppBlocker's " +
                "Accessibility service, so your blocks and limits aren't being enforced right " +
                "now. Tap to turn it back on — it only takes a second.",
            action = "Turn back on",
        )
        manager.notify(NOTIF_ID, notification)
        // Stamp the cooldown only after actually posting, so a failed post never suppresses a
        // later real one.
        SettingsStore.setProtectionLastNotifiedAt(context, now)
    }

    /**
     * Fires the alert immediately, ignoring the accessibility state and the throttle, so the user
     * can confirm notifications actually reach them on their device (esp. on OEMs like MIUI that
     * silently restrict them). Reuses the exact same channel/icons/styling as the real alert.
     */
    @SuppressLint("MissingPermission") // guarded by the areNotificationsEnabled() check below.
    fun notifyTest(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val notification = build(
            context,
            title = "Test alert — notifications are working",
            collapsed = "This is how you'll be warned if protection ever turns off.",
            expanded = "Notifications are set up correctly. If AppBlocker's Accessibility service " +
                "ever gets turned off, you'll get an alert like this one so you can turn it " +
                "straight back on.",
            action = "Open AppBlocker",
        )
        manager.notify(NOTIF_ID, notification)
    }

    private fun build(
        context: Context,
        title: String,
        collapsed: String,
        expanded: String,
        action: String,
    ): android.app.Notification {
        val fixIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_PERMISSIONS, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, fixIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val largeIcon = ContextCompat.getDrawable(context, R.drawable.ic_notification_large)
            ?.toBitmap()

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(largeIcon)
            .setContentTitle(title)
            .setContentText(collapsed)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
            .setColor(ACCENT_COLOR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_notification, action, pendingIntent)
            .build()
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID)
    }
}
