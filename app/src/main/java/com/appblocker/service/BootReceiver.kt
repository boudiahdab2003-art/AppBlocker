package com.appblocker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.appblocker.data.BootAudit
import com.appblocker.data.OutageLog
import com.appblocker.data.UpdatePause

/** Re-arms the protection watchdog and checks immediately after a reboot, since a device
 *  restart is one of the more common times an accessibility service fails to reconnect.
 *  Also fires right after the app itself is UPDATED (MY_PACKAGE_REPLACED) — that's the
 *  earliest moment to arm the after-update blocking pause. And, since Android 15, on the first
 *  start after a FORCE STOP, with no restart at all — which is not a boot (invariant 77). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val appContext = context.applicationContext
        // ⚠️ **Asked before the stamp, because it decides it** (invariant 77). A force-stopped app
        // is sent BOOT_COMPLETED on its next start; stamped as a boot, a report can say the app
        // started hours after a restart that never happened. The question reads Android's start
        // record and nothing of ours, so it cannot disturb the ordering below.
        val boot = intent.action == Intent.ACTION_BOOT_COMPLETED && BootAudit.isBoot(appContext)
        // ⚠️ **First, before anything else in this method.** Everything below reaches
        // BootAudit.noteRun, which looks for this stamp — so a later call would have the receiver
        // record its own boot as one it missed, an instrument reporting the exact opposite of what
        // happened. CodeShapeTest fails the build on the ordering.
        if (boot) BootAudit.heard(appContext)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            UpdatePause.checkVersionChange(appContext)
            // Look again at three minutes. The check below runs seconds after the install, inside
            // the bind grace, so it cannot yet tell a rebound watcher from one that will never
            // come back — and whether Android rebinds us after our own install is the OEM's
            // decision, not ours. Without this the next look is the 15-minute periodic tick, on
            // every single release. (`scheduleRecheckSoon` covers 45 seconds and is armed by the
            // check itself when it finds the grace still running.)
            ProtectionScheduler.scheduleRecheckAfterUpdate(appContext)
        }
        ProtectionScheduler.ensureScheduled(appContext)
        // Back after a force stop is not the phone restarting: whatever this finds is filed as an
        // ordinary background check, never as the boot's.
        val calledBy = if (intent.action == Intent.ACTION_BOOT_COMPLETED && !boot) {
            OutageLog.EndedBy.BACKGROUND
        } else {
            OutageLog.EndedBy.BOOT
        }
        ProtectionWatchdog.checkAndNotify(appContext, calledBy = calledBy)
    }
}
