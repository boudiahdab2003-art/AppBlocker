package com.appblocker.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Being an active device admin means AppBlocker can't be uninstalled until the
 * admin is turned off first — extra friction against bypassing your blocks.
 */
class AppBlockerAdminReceiver : DeviceAdminReceiver() {
    /** Shown on the system's deactivation-confirmation screen when the user tries to turn the
     *  admin off — one more speed bump on the uninstall escape hatch. */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Turn off Strict Mode first — AppBlocker stays locked until your timer ends."

    companion object {
        fun componentName(context: Context) =
            ComponentName(context, AppBlockerAdminReceiver::class.java)
    }
}
