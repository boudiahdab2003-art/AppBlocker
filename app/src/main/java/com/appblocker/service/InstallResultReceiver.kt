package com.appblocker.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.appblocker.R
import com.appblocker.data.InstallPrompt
import com.appblocker.data.SettingsStore

/**
 * Hears what happened to a silent self-update, and asks for a tap when the system insists on one.
 *
 * **Why this file has to exist.** `PackageInstaller.commit` does not answer on the spot — it answers
 * here, later. Without a receiver the most important answer of all would be thrown away:
 * `STATUS_PENDING_USER_ACTION`, which means "I'll do it, but a human has to agree first". That is
 * the expected reply the first time the app installs itself, and it may be the reply every time on a
 * phone whose maker doesn't allow the no-tap path. With nothing listening, the update would simply
 * not happen and nothing on the phone would say so — the exact failure mode auto-update was added
 * to end.
 *
 * So the refusal becomes a notification carrying the system's own confirmation screen. One tap, and
 * from then on AppBlocker is the installer of record for itself, which is what the silent path
 * needs.
 *
 * The success case never reaches this code: the process is killed the moment its replacement lands.
 * That is why [com.appblocker.data.SilentInstaller] writes its marker *before* committing.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_RESULT -> onResult(context, intent)
            ACTION_CONFIRM -> onConfirm(context, intent)
        }
    }

    private fun onResult(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        // Whatever happens below, this is no longer an automatic install. The marker exists to stop
        // a *silent* update pausing blocking; an update the owner taps through himself means he is
        // standing right there, and should pause exactly as it always has.
        SettingsStore.setAutoInstalled(context, false)

        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) return

        @Suppress("DEPRECATION")
        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
        notifyReady(context, confirm)
    }

    /**
     * The owner tapped the notification. Marks the prompt as ours before opening it — the guard
     * bounces the installer otherwise, and blocking the screen that installs the update is how this
     * whole area went wrong in the first place (see [InstallPrompt]).
     *
     * A broadcast sent from a notification tap may start an activity for a few seconds afterwards,
     * which is why the notification points here rather than straight at the confirmation screen:
     * those seconds are what buys the line above.
     */
    private fun onConfirm(context: Context, intent: Intent) {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID)
        @Suppress("DEPRECATION")
        val confirm = intent.getParcelableExtra<Intent>(EXTRA_CONFIRM) ?: return
        InstallPrompt.requested()
        runCatching {
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    companion object {
        const val ACTION_RESULT = "com.appblocker.INSTALL_RESULT"
        private const val ACTION_CONFIRM = "com.appblocker.INSTALL_CONFIRM"
        private const val EXTRA_CONFIRM = "confirm_intent"
        private const val CHANNEL_ID = "app_updates"

        // 1001..1003 belong to ProtectionNotifier.
        private const val NOTIF_ID = 1004

        /**
         * "An update is downloaded and waiting for one tap." [confirm] is the screen that tap
         * opens: the system's own confirmation when a silent commit came back needing a human, or
         * the ordinary installer intent when the silent path could not even start.
         *
         * Posting this is the difference between auto-update failing loudly enough to fix in one
         * tap and failing invisibly, which is what it was built to stop.
         */
        @SuppressLint("MissingPermission") // guarded by areNotificationsEnabled() below.
        fun notifyReady(context: Context, confirm: Intent) {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            createChannel(context)

            val tap = PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, InstallResultReceiver::class.java)
                    .setAction(ACTION_CONFIRM)
                    .putExtra(EXTRA_CONFIRM, confirm),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Update ready to install")
                .setContentText("Tap to finish — it takes one screen.")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "A new version of AppBlocker is downloaded and ready. Android wants " +
                            "your permission for this one — once you've installed it yourself, " +
                            "later updates should arrive without asking.",
                    ),
                )
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(tap)
                .build()
            manager.notify(NOTIF_ID, notification)
        }

        private fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            // Deliberately quieter than the protection channel, and separate from it: a waiting
            // update is news, not an alarm, and silencing one must never silence the other.
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App updates",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Tells you when a new version is ready to install."
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
}
