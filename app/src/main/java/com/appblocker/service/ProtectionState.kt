package com.appblocker.service

/** What the watchdog found. */
internal enum class ProtectionState {
    OK,
    OFF,
    STALLED,

    /**
     * Switched on and alive, but blocking is **paused after an app update** and stays off until
     * the user reactivates it. Every update does this by design (see [com.appblocker.data.UpdatePause]),
     * and nothing outside the Blocking tab's banner used to say so — the watchdog reported OK, the
     * Profile status row said "Protection active", and no notification fired. For an app whose
     * whole job is blocking, "off and telling you it's fine" is the worst state to be silent about,
     * and it happened after *every* release.
     */
    PAUSED,
}

/**
 * How long after our own process starts the watcher is allowed to still be unbound before
 * `serviceConnected = false` counts as proof of death.
 *
 * Android binds an accessibility service shortly after the process comes up, and the process can
 * be started by anything — the launcher icon, a WorkManager tick, the quick-settings tile. Without
 * this window every cold start would report blocking as dead for the fraction of a second before
 * the bind lands, which is the false alarm this file exists to avoid.
 */
internal const val SERVICE_BIND_GRACE_MS = 20_000L

/** No event for this long is the first hint that the watcher may have been killed. */
internal const val STALE_AFTER_MS = 2 * 60 * 60_000L

/** …but only counts as stalled if the phone was genuinely used in that window. */
internal const val STALE_MIN_USED_MINUTES = 15

/**
 * Decides whether blocking is healthy, switched off, or **stalled**: switched on, yet the watcher
 * has shown no sign of life for hours while the phone was clearly being used. That last state is
 * the one the app used to be blind to — aggressive OEM battery managers (HyperOS especially) kill
 * the service without touching its Settings toggle, so "enabled" looked identical to "working".
 *
 * Kept pure and separate from the watchdog so the thresholds can be tested rather than trusted.
 *
 * @param lastEventAt when the service last handled an event (0 = never since install).
 * @param usedMinutesSinceLastEvent foreground minutes across all apps since [lastEventAt], or
 *   **null when usage access isn't granted** — with no way to tell an idle phone from a dead
 *   watcher, the answer is never STALLED. A false "blocking stopped" alert while everything is
 *   fine would train the owner to ignore the one that matters.
 * @param updatePaused blocking is switched off pending reactivation after an update. Reported
 *   ahead of the staleness checks: it is the more specific answer, the user caused it, and it has
 *   a clear fix. It ranks below [ProtectionState.OFF] because a disabled service is the more
 *   fundamental problem — reactivating would achieve nothing while the toggle is off.
 * @param serviceConnected whether the watcher is bound and running *right now*
 *   ([com.appblocker.service.BlockerAccessibilityService.isConnected]), or **null when the caller
 *   couldn't tell** — which must behave exactly as this function did before the parameter existed.
 * @param msSinceProcessStart how long our process has been up, monotonically. Only read when
 *   [serviceConnected] is false, to skip the moment before Android has bound us.
 */
internal fun protectionState(
    enabled: Boolean,
    lastEventAt: Long,
    now: Long,
    usedMinutesSinceLastEvent: Int?,
    updatePaused: Boolean = false,
    serviceConnected: Boolean? = null,
    msSinceProcessStart: Long = Long.MAX_VALUE,
): ProtectionState {
    if (!enabled) return ProtectionState.OFF
    if (updatePaused) return ProtectionState.PAUSED
    // Switched on in Settings, and not running. This is the whole of the Second Space failure:
    // the phone stops every app in the space, HyperOS doesn't always rebind us on the way back,
    // and the setting still lists us because it records a *choice*. Everything below this line
    // was the only detector before, and it needs two hours plus fifteen measured minutes of use
    // plus usage-stats permission to say the same thing — hours during which the app cheerfully
    // reported "Protection active" and blocked nothing.
    //
    // Deliberately BELOW the OFF and PAUSED checks: both are more specific answers with their own
    // fixes, and telling someone to revive a service they themselves switched off would be wrong.
    if (serviceConnected == false && msSinceProcessStart >= SERVICE_BIND_GRACE_MS) {
        return ProtectionState.STALLED
    }
    if (lastEventAt <= 0L) return ProtectionState.OK // never ran yet (fresh install/just enabled)
    if (now - lastEventAt < STALE_AFTER_MS) return ProtectionState.OK
    val used = usedMinutesSinceLastEvent ?: return ProtectionState.OK
    return if (used >= STALE_MIN_USED_MINUTES) ProtectionState.STALLED else ProtectionState.OK
}
