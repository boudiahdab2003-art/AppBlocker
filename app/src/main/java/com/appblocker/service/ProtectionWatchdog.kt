package com.appblocker.service

import android.content.Context
import com.appblocker.data.SettingsStore

/**
 * Single source of truth for "is the blocking service actually on right now" — called from the
 * periodic worker, the boot receiver, and the app's own resume fast-path, so all three paths
 * stay in sync on notifying/cancelling.
 */
object ProtectionWatchdog {
    /**
     * @param force pass true from the app-open/resume path so the alert always reflects the true
     *   current state (bypasses the 4-hour throttle); the background worker uses the default false.
     */
    fun checkAndNotify(context: Context, force: Boolean = false) {
        if (AccessibilityUtil.isEnabled(context)) {
            SettingsStore.clearProtectionOffSince(context)
            ProtectionNotifier.cancel(context)
        } else {
            ProtectionNotifier.notifyDisabled(context, force)
        }
    }
}
