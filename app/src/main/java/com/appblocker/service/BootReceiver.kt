package com.appblocker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms the protection watchdog and checks immediately after a reboot, since a device
 *  restart is one of the more common times an accessibility service fails to reconnect. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        ProtectionScheduler.ensureScheduled(appContext)
        ProtectionWatchdog.checkAndNotify(appContext)
    }
}
