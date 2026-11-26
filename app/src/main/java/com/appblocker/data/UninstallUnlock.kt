package com.appblocker.data

import android.content.Context

/**
 * **The slow way to switch "Prevent uninstall" off** (invariant 110).
 *
 * **Why this exists.** On 26 Nov 2026 the owner relapsed by uninstalling AppBlocker. Every other
 * off-switch in the app needed a served wait ([OffSwitchGuard]'s two hours); this one needed only
 * the typed paragraph, and then Profile called `disableDeviceAdmin` on the spot. With device admin
 * gone, a long-press on the home-screen icon removed the app and every block with it. The paragraph
 * stops a passing whim; it did not stop a bad evening.
 *
 * His choice that day: turning it off must wait **longer than 24 hours**. So passing the gate now
 * only *requests* it, and the request is served by a clock-proof [GuardedDeadline] of
 * [UNLOCK_DELAY_MS], then a [UNLOCK_WINDOW_MS] in which the Profile row actually works.
 *
 * **The same request also lowers the uninstall arms of the watcher's guard** — the uninstall
 * confirmation, the device-admin removal page, and the launcher's own uninstall popup. Those used
 * to come down with the two-hour [OffSwitchGuard] unlock, which would have made two hours the real
 * price of an uninstall no matter how long this wait was: open the guard, deactivate admin in
 * Settings, uninstall. So they answer to this clock and only this clock; Strict still bounces them
 * regardless. There is no switch to turn these arms off — "Guard the off-switch" off leaves them up.
 *
 * The state machine is [OffSwitchGuard]'s own ([OffSwitchGuard.phase], [OffSwitchGuard.tap]),
 * reused rather than copied: two copies of one state machine is the first bug shape in
 * `docs/BLOCKING_INVARIANTS.md`.
 */
object UninstallUnlock {

    /** His "longer than 24 hrs": two days. */
    const val UNLOCK_DELAY_MS = 48 * 60 * 60_000L

    /** After a two-day wait a short window is a trap; half an hour is enough to notice and act. */
    const val UNLOCK_WINDOW_MS = 30 * 60_000L

    const val DELAY_LABEL = "48 hours"
    const val WINDOW_LABEL = "30 minutes"

    /** Only [OffSwitchGuard.Phase.OPEN] stands the uninstall arms down. No switch, no other way. */
    internal fun armed(phase: OffSwitchGuard.Phase): Boolean = phase != OffSwitchGuard.Phase.OPEN

    fun phase(context: Context, bootCount: Int): OffSwitchGuard.Phase {
        val request = SettingsStore.uninstallUnlockRequest(context)
        return OffSwitchGuard.phase(
            hasRequest = request != null,
            untilUnlock = request?.remaining(bootCount) ?: 0L,
            untilExpiry = request?.remaining(bootCount, extraMs = UNLOCK_WINDOW_MS) ?: 0L,
        )
    }

    /** Whether the watcher bounces the uninstall screens right now. Reads prefs every call, for
     *  the reason [OffSwitchGuard.armed] gives. */
    fun armed(context: Context): Boolean = armed(phase(context, DeviceBoot.count(context)))

    /** For the report: `guarded`, `waiting` or `open`. */
    fun reportValue(context: Context): String =
        phase(context, DeviceBoot.count(context)).name.lowercase()
}
