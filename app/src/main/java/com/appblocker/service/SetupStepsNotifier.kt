package com.appblocker.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.appblocker.R
import com.appblocker.data.SetupGuide
import com.appblocker.data.Words

/**
 * The setup steps, carried into Settings.
 *
 * **The one place the app cannot speak is the one place people get lost.** Tapping "Turn on
 * blocking" hands the user to Android's own Settings, and from that moment the wizard — pictures,
 * headings, rescue and all — is behind them. Everything it knows was said before they left, to
 * someone who had not yet seen the screen it was describing.
 *
 * A notification is the only channel that still reaches them there, and it is a legitimate one.
 * **Drawing over the accessibility settings screen would not be**: an overlay on exactly that page
 * is the signature move of accessibility-abusing malware, it is a Play rejection risk, and it is
 * not a line worth walking for a convenience. A notification is what the platform offers instead.
 *
 * Three deliberate restraints, because a setup helper that becomes a nuisance is worse than none:
 *
 *  - **Its own channel at DEFAULT importance, never HIGH.** A heads-up would slide down over the
 *    top of the screen — which on the accessibility list is exactly where the AppBlocker row sits.
 *    The helper covering the thing it is pointing at would be a fine joke and a bad feature. Quiet
 *    in the shade, with a status-bar icon, is the right loudness; the step text on screen tells
 *    them it is there.
 *  - **Only ever after the user pressed the button.** It is never posted on its own.
 *  - **Cleared the moment they come back**, whether it worked or not, and cleared when the step is
 *    granted. A stale instruction for a job already done is litter.
 *
 * Silently does nothing when notifications are denied — this is help, not a requirement, and the
 * pictures are still on the screen they came from.
 */
object SetupStepsNotifier {

    private const val CHANNEL_ID = "setup_steps"
    private const val NOTIFICATION_ID = 5301

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            Words.of(context).get(R.string.channel_setup_name),
            // Deliberately not HIGH — see the class note. This one must not float over the screen
            // it is describing.
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = Words.of(context).get(R.string.channel_setup_desc)
            enableVibration(false)
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    /** Posts [guide]'s captions as a numbered list. No-op without notification permission. */
    fun show(context: Context, guide: SetupGuide, headline: String) {
        if (!canPost(context)) return
        createChannel(context)

        val steps = guide.shots
            .mapIndexed { i, shot -> "${i + 1}. ${shot.caption}" }
            .joinToString("\n\n")

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(headline)
            .setContentText(guide.shots.firstOrNull()?.caption.orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(steps))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            // No content intent on purpose: tapping it should not yank them out of Settings
            // half-way through the very steps it is listing.
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    /** Takes it down. Safe to call when nothing was ever posted. */
    fun clear(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return runCatching {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }.getOrDefault(false)
    }
}
