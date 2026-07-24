package com.appblocker.service

import android.content.Context
import com.appblocker.data.ServiceHealth
import com.appblocker.data.SettingsStore
import com.appblocker.ui.hasUsageAccess

/**
 * Single source of truth for "is the blocking service actually on right now" — called from the
 * periodic worker, the boot receiver, and the app's own resume fast-path, so all three paths
 * stay in sync on notifying/cancelling.
 *
 * "On" means two things: switched on in Settings, and *still alive*. See [protectionState].
 */
object ProtectionWatchdog {

    /** The current health of blocking, for the watchdog and for the app's own status row. */
    internal fun state(context: Context, now: Long = System.currentTimeMillis()): ProtectionState {
        val enabled = AccessibilityUtil.isEnabled(context)
        val lastEventAt = ServiceHealth.lastEventAt(context)
        // Usage access is optional, so this can be null — protectionState then never says STALLED.
        val usedMinutes = if (enabled && lastEventAt > 0L && hasUsageAccess(context)) {
            UsageTracker.totalMinutesInRange(context, lastEventAt, now)
        } else {
            null
        }
        return protectionState(enabled, lastEventAt, now, usedMinutes)
    }

    /**
     * @param force pass true from the app-open/resume path so the alert always reflects the true
     *   current state (bypasses the 4-hour throttle); the background worker uses the default false.
     */
    fun checkAndNotify(context: Context, force: Boolean = false) {
        when (state(context)) {
            ProtectionState.OK -> {
                SettingsStore.clearProtectionOffSince(context)
                ProtectionNotifier.cancel(context)
            }
            ProtectionState.OFF -> ProtectionNotifier.notifyDisabled(context, force)
            // Switched on, but nothing has reached the watcher for hours of active use — the
            // signature of an OEM battery manager killing it. Toggling accessibility off/on
            // revives it, which is what the alert sends the user to do.
            ProtectionState.STALLED -> ProtectionNotifier.notifyStalled(context, force)
        }
    }
}
