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

/** Alerts the user if AppBlocker's Accessibility service gets silently turned off — or stops
 *  responding while still switched on. */
object ProtectionNotifier {
    private const val CHANNEL_ID = "protection_off"
    private const val NOTIF_ID = 1001
    // Its own id so the "stalled" alert can't silently replace (or be replaced by) the "off" one.
    private const val NOTIF_ID_STALLED = 1002
    private const val NOTIF_ID_PAUSED = 1003

    // Once shown, don't nag again until this much time has passed while still disabled.
    private val MIN_RENOTIFY_MS = TimeUnit.HOURS.toMillis(4)

    /** Whether the standing "blocking has stopped" alert is already up, as far as this process
     *  knows. Not persisted on purpose — see [notifyStalled]. */
    @Volatile private var stalledPosted = false

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
            title = "Protection is off",
            collapsed = "Tap to turn blocking back on",
            bannerHeadline = "PROTECTION OFF",
            bannerSubtitle = "Blocking has stopped",
            action = "Turn on",
            requestCode = NOTIF_ID,
        )
        manager.notify(NOTIF_ID, notification)
        // Stamp the cooldown only after actually posting, so a failed post never suppresses a
        // later real one.
        SettingsStore.setProtectionLastNotifiedAt(context, now)
    }

    /**
     * Posts the "blocking has stopped" alert: the service is still switched on in Settings, but is
     * not running — the phone killed it (an OEM battery manager, or a Second Space switch stopping
     * every app in this space) and Android's toggle still says on, because that toggle records the
     * user's choice rather than the service's state. Turning accessibility off and on again is the
     * only revival Android permits an app, which is where the alert leads.
     *
     * **Ongoing, and exempt from the re-notify throttle**, unlike every other alert here. The
     * throttle exists to stop repeated nagging; this posts *one* notification that stays put until
     * [cancel] takes it down on the next healthy check. Swiping the old, dismissible version away
     * bought four hours of silence while nothing whatsoever was being blocked — the worst possible
     * trade for an app whose only job is blocking.
     *
     * Shares the channel with [notifyDisabled] (the same concern to the user: blocking isn't
     * working) but uses its own notification id so one can't silently replace the other.
     */
    @SuppressLint("MissingPermission") // guarded by the areNotificationsEnabled() check below.
    fun notifyStalled(context: Context, @Suppress("UNUSED_PARAMETER") force: Boolean = false) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        // Re-posting an identical notification is harmless but not free — it rebuilds the banner
        // bitmap — and this is now called from every shade pull and every app resume. The flag is
        // in memory only: if the process was restarted the alert may genuinely be gone from the
        // shade, so posting again is the right answer, not a duplicate.
        if (stalledPosted) return
        stalledPosted = true

        val notification = build(
            context,
            title = "Blocking has stopped",
            collapsed = "Your phone shut it down — tap to switch it back on",
            bannerHeadline = "BLOCKING HAS STOPPED",
            bannerSubtitle = "Nothing is being blocked",
            action = "Fix it",
            requestCode = NOTIF_ID_STALLED,
            openRepair = true,
            // The one alert that stands until it is true no longer. See [ongoing] on build().
            ongoing = true,
        )
        manager.notify(NOTIF_ID_STALLED, notification)
        // Deliberately does NOT stamp the shared cooldown. This alert is not repeated — it is a
        // single notification that sits there — so it neither needs the throttle nor may consume
        // it: burning the window here would suppress a genuinely separate "protection off" alert
        // for the next four hours.
    }

    /**
     * Posts the "paused after an update, blocking is off" alert.
     *
     * Every update switches blocking off until the user reactivates it, and until now the only
     * hint was a banner on the Blocking tab — so an update could leave the app blocking nothing
     * while its own status row said "Protection active". This is the alert for that.
     */
    @SuppressLint("MissingPermission") // guarded by the areNotificationsEnabled() check below.
    fun notifyPaused(context: Context, force: Boolean = false) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val now = System.currentTimeMillis()
        if (!force) {
            val last = SettingsStore.protectionLastNotifiedAt(context)
            if (now - last < MIN_RENOTIFY_MS) return
        }

        val notification = build(
            context,
            title = "Blocking is paused after the update",
            collapsed = "Tap to turn it back on",
            bannerHeadline = "BLOCKING IS OFF",
            bannerSubtitle = "Paused since the app updated",
            action = "Turn it back on",
            requestCode = NOTIF_ID_PAUSED,
            openPermissions = false, // Reactivate is on the Blocking tab, which is where we land
        )
        manager.notify(NOTIF_ID_PAUSED, notification)
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
            title = "Notifications are on",
            collapsed = "You'll be warned if protection stops",
            bannerHeadline = "NOTIFICATIONS ON",
            bannerSubtitle = "Alerts are working",
            action = "Open",
            requestCode = NOTIF_ID,
        )
        manager.notify(NOTIF_ID, notification)
    }

    private fun build(
        context: Context,
        title: String,
        collapsed: String,
        bannerHeadline: String,
        bannerSubtitle: String,
        action: String,
        /** Distinct per notification, because PendingIntent matching IGNORES extras: with a
         *  shared request code and FLAG_UPDATE_CURRENT, whichever notification was built last
         *  would silently rewrite where the others' taps go. Passing the notification id keeps
         *  each one's destination its own. */
        requestCode: Int,
        /** Where tapping should land. The permissions screen is right for a disabled service, but
         *  wrong for the after-update pause: Reactivate lives on the Blocking tab, which is where
         *  opening the app plainly already goes (tab 0). Sending someone to a permissions screen
         *  with nothing to fix is worse than not linking at all — which is also why a *stalled*
         *  service uses [openRepair] instead: every permission it needs is already granted. */
        openPermissions: Boolean = true,
        /** Land on the repair screen, which explains the failure and opens the one switch that
         *  fixes it. Wins over [openPermissions] when both are set. */
        openRepair: Boolean = false,
        /** Post as an ongoing notification the user cannot swipe away, and don't clear it on tap.
         *  Only for a state that is still true after the tap and that they must not lose track of;
         *  [cancel] is what ends it. */
        ongoing: Boolean = false,
    ): android.app.Notification {
        val fixIntent = Intent(context, MainActivity::class.java).apply {
            if (openRepair) putExtra(MainActivity.EXTRA_OPEN_REPAIR, true)
            else if (openPermissions) putExtra(MainActivity.EXTRA_OPEN_PERMISSIONS, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, requestCode, fixIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Collapsed: small round badge. Expanded: the full-width branded banner instead — no wall
        // of text. bigLargeIcon(null) drops the badge on expand so the banner owns the space.
        val largeIcon = ContextCompat.getDrawable(context, R.drawable.ic_notification_large)
            ?.toBitmap()
        val banner = NotificationBanner.build(context, bannerHeadline, bannerSubtitle)

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(largeIcon)
            .setContentTitle(title)
            .setContentText(collapsed)
            .setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(banner)
                    .bigLargeIcon(null as android.graphics.Bitmap?),
            )
            .setColor(ACCENT_COLOR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
            // An ongoing alert is re-posted on every health check, and a notification re-posted
            // under the same id makes a sound each time by default. One buzz per occurrence; the
            // notification staying put is what carries the message afterwards.
            .setOnlyAlertOnce(ongoing)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_notification, action, pendingIntent)
            .build()
    }

    /** Clears every alert — blocking being healthy means none of them still applies. Missing an id
     *  here would leave a stale "blocking is off" notification sitting there after it was fixed. */
    fun cancel(context: Context) {
        stalledPosted = false
        NotificationManagerCompat.from(context).apply {
            cancel(NOTIF_ID)
            cancel(NOTIF_ID_STALLED)
            cancel(NOTIF_ID_PAUSED)
        }
    }
}
