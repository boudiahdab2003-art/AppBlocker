package com.appblocker.service

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/** Whether AppBlocker's accessibility watcher is currently enabled by the user. */
object AccessibilityUtil {
    fun isEnabled(context: Context): Boolean {
        val expected = ComponentName(context, BlockerAccessibilityService::class.java)
            .flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
