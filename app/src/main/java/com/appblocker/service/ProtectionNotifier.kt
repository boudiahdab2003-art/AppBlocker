package com.appblocker.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.appblocker.MainActivity
import com.appblocker.R
import com.appblocker.data.SettingsStore
import java.util.concurrent.TimeUnit
import com.appblocker.data.Words

/** Alerts the user if AppBlocker's Accessibility service gets silently turned off — or stops
 *  responding while still switched on. */
object ProtectionNotifier {
    private const val CHANNEL_ID = "protection_off"

    /**
     * The stalled alert's own channel, and it has to be its own for two separate reasons.
     *
     * **Android will not let a channel be made louder once it exists.** `createNotificationChannel`
     * silently ignores an importance *raise* on a channel that is already there, so if
     * [CHANNEL_ID] was ever turned down — by the owner, or by an OEM's notification manager — the
     * app could never restore it and this alert would be permanently quiet with nothing on screen
     * to say so. A new id is the only way to guarantee HIGH, and the only way to repair it later
     * is another new id.
     *
     * **And they are not the same kind of message.** "Paused after an update" is routine and
     * self-inflicted; "blocking has stopped" means the phone is claiming to protect him and is
     * doing nothing. Sharing a channel meant he could not quieten the first without silencing the
     * second. Reported as *"can you make the notifications much more persisting and floating so i
     * see them"*.
     */
    private const val CHANNEL_ID_STALLED = "protection_stalled"

    /** How long before the standing alert floats again while blocking is still dead. Short on
     *  purpose: this is the one state where being annoying is the correct behaviour. */
    internal val REFLOAT_MS = TimeUnit.MINUTES.toMillis(5)
    private const val NOTIF_ID = 1001
    // Its own id so the "stalled" alert can't silently replace (or be replaced by) the "off" one.
    private const val NOTIF_ID_STALLED = 1002
    private const val NOTIF_ID_PAUSED = 1003

    // Once shown, don't nag again until this much time has passed while still disabled.
    private val MIN_RENOTIFY_MS = TimeUnit.HOURS.toMillis(4)

    /**
     * When the standing "blocking has stopped" alert last *floated*, monotonically.
     *
     * **It used to be a boolean — "have I posted this?" — and that was the wrong question.** Once
     * true it stopped the alert being posted again for the life of the process, and the
     * notification itself carried `setOnlyAlertOnce(true)`, which tells Android never to sound or
     * peek for that id again. Between them the alert could float exactly once, ever, and then sat
     * silently in the shade while nothing was being blocked. The right question is *when did he
     * last see it*, so it can be shown again.
     *
     * Still in memory, and still deliberately: a restarted process may have lost the notification
     * from the shade, so a fresh process floating immediately is correct. Note this is the
     * opposite call from `InstallPrompt`, which had to *survive* a process death — the difference
     * is that this one wants to notice one.
     *
     * `elapsedRealtime`, never the wall clock (invariant 9): a clock change must not be able to
     * silence the one alert that means nothing is being blocked.
     */
    @Volatile private var lastStalledFloatRt = 0L

    // The app's established "needs attention" amber (Permissions.kt's "Required" label,
    // BlockEditorScreen.kt's ProtectionBanner) — distinct from the blue/violet used for
    // positive/primary actions elsewhere, so this reads as urgent rather than routine.
    private val ACCENT_COLOR = 0xFFFFB020.toInt()

    fun createChannel(context: Context) {
        // The words in the app's language, not the phone's: a Service never passes through
        // attachBaseContext. See com.appblocker.data.Words.
        val w = Words.of(context)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            w.get(R.string.channel_protection_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = w.get(R.string.channel_protection_desc)
            enableLights(true)
            lightColor = ACCENT_COLOR
            enableVibration(true)
        }
        val stalled = NotificationChannel(
            CHANNEL_ID_STALLED,
            w.get(R.string.channel_stalled_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = w.get(R.string.channel_stalled_desc)
            enableLights(true)
            lightColor = ACCENT_COLOR
            enableVibration(true)
        }
        context.getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(channel)
            createNotificationChannel(stalled)
        }
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
        // The words in the app's language, not the phone's: a Service never passes through
        // attachBaseContext. See com.appblocker.data.Words.
        val w = Words.of(context)
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
            title = w.get(R.string.notif_off_title),
            collapsed = w.get(R.string.notif_off_collapsed),
            bannerHeadline = w.get(R.string.notif_off_headline),
            bannerSubtitle = w.get(R.string.notif_off_subtitle),
            action = w.get(R.string.notif_off_action),
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
    fun notifyStalled(context: Context, force: Boolean = false) {
        // The words in the app's language, not the phone's: a Service never passes through
        // attachBaseContext. See com.appblocker.data.Words.
        val w = Words.of(context)
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        // Called from every shade pull, every app resume and the repeat worker, so most calls do
        // nothing but re-check the clock. Rebuilding the banner bitmap is the expensive part and
        // only happens when it is actually going to be shown again.
        if (!shouldRefloat(lastStalledFloatRt, SystemClock.elapsedRealtime(), force)) return
        lastStalledFloatRt = SystemClock.elapsedRealtime()

        val notification = build(
            context,
            title = w.get(R.string.notif_stalled_title),
            collapsed = w.get(R.string.notif_stalled_collapsed),
            bannerHeadline = w.get(R.string.notif_stalled_headline),
            bannerSubtitle = w.get(R.string.notif_stalled_subtitle),
            action = w.get(R.string.notif_stalled_action),
            requestCode = NOTIF_ID_STALLED,
            openRepair = true,
            // The one alert that stands until it is true no longer. See [ongoing] on build().
            ongoing = true,
            channelId = CHANNEL_ID_STALLED,
            // **The whole point.** Every other alert here keeps onlyAlertOnce, for the good reason
            // spelt out on [build]; this one must be allowed to peek again or it is a line of text
            // in a shade he is not looking at.
            alertEveryTime = true,
        )
        // **Cancel, then post.** Re-posting under an id that is already showing UPDATES the
        // notification, silently — Android does not peek again for a notification it is already
        // displaying. Taking it down first makes the next post a new arrival, which is what
        // floats. The gap is a few milliseconds and the alert is ongoing either side of it.
        manager.cancel(NOTIF_ID_STALLED)
        manager.notify(NOTIF_ID_STALLED, notification)
        // Deliberately does NOT stamp the shared cooldown. This alert is not repeated — it is a
        // single notification that sits there — so it neither needs the throttle nor may consume
        // it: burning the window here would suppress a genuinely separate "protection off" alert
        // for the next four hours.
    }

    /**
     * Whether the standing alert should be shown again now.
     *
     * Pure, and taking its clock as an argument, for the reason `SessionClock.remainingAt` is the
     * same shape: this app has no Robolectric, so a rule is only testable if it does not go
     * looking for the world itself.
     *
     * [force] is the app-open path (`AppRoot` already passes it, and it was being *ignored* — the
     * parameter was marked unused). Someone who has just opened AppBlocker is looking at the
     * phone, which is the best moment there is to tell them blocking is dead.
     *
     * A clock that has gone backwards answers *float* rather than *stay quiet*: every uncertainty
     * here resolves towards him seeing it.
     */
    internal fun shouldRefloat(lastAtRt: Long, nowRt: Long, force: Boolean): Boolean {
        if (force || lastAtRt <= 0L) return true
        val since = nowRt - lastAtRt
        return since < 0L || since >= REFLOAT_MS
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
        // The words in the app's language, not the phone's: a Service never passes through
        // attachBaseContext. See com.appblocker.data.Words.
        val w = Words.of(context)
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val now = System.currentTimeMillis()
        if (!force) {
            val last = SettingsStore.protectionLastNotifiedAt(context)
            if (now - last < MIN_RENOTIFY_MS) return
        }

        val notification = build(
            context,
            title = w.get(R.string.notif_paused_title),
            collapsed = w.get(R.string.notif_paused_collapsed),
            bannerHeadline = w.get(R.string.notif_paused_headline),
            bannerSubtitle = w.get(R.string.notif_paused_subtitle),
            action = w.get(R.string.notif_paused_action),
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
        // The words in the app's language, not the phone's: a Service never passes through
        // attachBaseContext. See com.appblocker.data.Words.
        val w = Words.of(context)
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val notification = build(
            context,
            title = w.get(R.string.notif_test_title),
            collapsed = w.get(R.string.notif_test_collapsed),
            bannerHeadline = w.get(R.string.notif_test_headline),
            bannerSubtitle = w.get(R.string.notif_test_subtitle),
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
        /** Which channel to post on. Only the stalled alert overrides it — see
         *  [CHANNEL_ID_STALLED] for why that one cannot share. */
        channelId: String = CHANNEL_ID,
        /** Let this notification sound and float on every post, not just the first. Off for
         *  everything routine; see the note on `setOnlyAlertOnce` below. */
        alertEveryTime: Boolean = false,
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

        return NotificationCompat.Builder(context, channelId)
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
            //
            // **Except for the one whose job is to be noticed.** That reasoning is right for a
            // state the user already knows about, and wrong for "nothing is being blocked" — there
            // the notification staying put is exactly what does NOT carry the message, because he
            // never looks. [alertEveryTime] is that exception, and its rate limit lives in
            // [shouldRefloat] rather than here, where it can be read and tested.
            .setOnlyAlertOnce(ongoing && !alertEveryTime)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_notification, action, pendingIntent)
            .build()
    }

    /** Clears every alert — blocking being healthy means none of them still applies. Missing an id
     *  here would leave a stale "blocking is off" notification sitting there after it was fixed. */
    fun cancel(context: Context) {
        lastStalledFloatRt = 0L
        NotificationManagerCompat.from(context).apply {
            cancel(NOTIF_ID)
            cancel(NOTIF_ID_STALLED)
            cancel(NOTIF_ID_PAUSED)
        }
    }
}
