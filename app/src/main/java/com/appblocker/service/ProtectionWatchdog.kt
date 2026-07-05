package com.appblocker.service

import android.content.Context
import com.appblocker.data.SettingsStore

/**
 * Single source of truth for "is the blocking service actually on right now" — called from the
 * periodic worker, the boot receiver, and the app's own resume fast-path, so all three paths
 * stay in sync on notifying/cancelling.
 */
object ProtectionWatchdog {
    fun checkAndNotify(context: Context) {
        if (AccessibilityUtil.isEnabled(context)) {
            SettingsStore.clearProtectionOffSince(context)
            ProtectionNotifier.cancel(context)
        } else {
            ProtectionNotifier.notifyDisabled(context)
        }
    }
}
