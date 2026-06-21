package com.appblocker.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context

/**
 * Being an active device admin means AppBlocker can't be uninstalled until the
 * admin is turned off first — extra friction against bypassing your blocks.
 */
class AppBlockerAdminReceiver : DeviceAdminReceiver() {
    companion object {
        fun componentName(context: Context) =
            ComponentName(context, AppBlockerAdminReceiver::class.java)
    }
}
