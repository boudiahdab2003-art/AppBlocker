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
        // The usage-stats read is also the one part of this that talks to a system service that
        // can fail (revoked access mid-read, an OEM throwing from queryEvents), and state() is
        // called from composables — ProfileScreen's status row and AppRoot's resume effect — so
        // letting it throw would crash the app on open. A failure means "can't tell", which is
        // already the null case: never a false STALLED.
        val usedMinutes = if (enabled && lastEventAt > 0L && hasUsageAccess(context)) {
            runCatching { UsageTracker.totalMinutesInRange(context, lastEventAt, now) }.getOrNull()
        } else {
            null
        }
        return protectionState(
            enabled, lastEventAt, now, usedMinutes,
            updatePaused = SettingsStore.updatePaused(context),
        )
    }

    /**
     * @param force pass true from the app-open/resume path so the alert always reflects the true
     *   current state (bypasses the 4-hour throttle); the background worker uses the default false.
     */
    fun checkAndNotify(context: Context, force: Boolean = false) = guarded(context, "watchdog") {
        // Guarded for the same reason the watcher's callbacks are: this runs from the app's own
        // resume effect (AppRoot), the boot receiver and the periodic worker. An exception from
        // posting a notification — OEM notification managers do throw — would crash the app on
        // open from the resume path. The thing that tells the user blocking has stopped must not
        // itself be able to take the app down. Failures land in ServiceHealth, which the Profile
        // screen now shows.
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
            // Off after an update, pending reactivation. Worth an alert precisely because it is
            // self-inflicted and easy to forget: the app was doing nothing at all, and saying it
            // was fine, until the user happened to open the Blocking tab.
            ProtectionState.PAUSED -> ProtectionNotifier.notifyPaused(context, force)
        }
    }
}
