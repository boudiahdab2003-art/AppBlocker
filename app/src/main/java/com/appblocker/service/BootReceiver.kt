package com.appblocker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.appblocker.data.UpdatePause

/** Re-arms the protection watchdog and checks immediately after a reboot, since a device
 *  restart is one of the more common times an accessibility service fails to reconnect.
 *  Also fires right after the app itself is UPDATED (MY_PACKAGE_REPLACED) — that's the
 *  earliest moment to arm the after-update blocking pause. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val appContext = context.applicationContext
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            UpdatePause.checkVersionChange(appContext)
        }
        ProtectionScheduler.ensureScheduled(appContext)
        ProtectionWatchdog.checkAndNotify(appContext)
    }
}
