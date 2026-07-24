package com.appblocker.service

/** What the watchdog found. */
internal enum class ProtectionState { OK, OFF, STALLED }

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
 */
internal fun protectionState(
    enabled: Boolean,
    lastEventAt: Long,
    now: Long,
    usedMinutesSinceLastEvent: Int?,
): ProtectionState {
    if (!enabled) return ProtectionState.OFF
    if (lastEventAt <= 0L) return ProtectionState.OK // never ran yet (fresh install/just enabled)
    if (now - lastEventAt < STALE_AFTER_MS) return ProtectionState.OK
    val used = usedMinutesSinceLastEvent ?: return ProtectionState.OK
    return if (used >= STALE_MIN_USED_MINUTES) ProtectionState.STALLED else ProtectionState.OK
}
