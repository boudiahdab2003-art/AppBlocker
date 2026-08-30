package com.appblocker.service

import com.appblocker.data.OutageLog

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
 * Consecutive failed probes before a **bound** watcher is called dead.
 *
 * A probe is the service asking, on a lit and unlocked screen, whether it can still read a window
 * at all — `BlockerAccessibilityService.probeScreen`, recorded by
 * [com.appblocker.data.ServiceHealth.recordProbe]. It runs only inside the heartbeat's existing
 * "several minutes of silence" branch, so five of them is roughly a quarter of an hour of a
 * connection that is gone while Android's switch still reads ON.
 *
 * **This is the arm that closes the two-hour hole.** The event-staleness path below needs
 * [STALE_AFTER_MS] to pass *and* [STALE_MIN_USED_MINUTES] of measured use *and* usage-stats
 * permission, because an absence of events is weak evidence on its own — an idle phone produces
 * exactly the same silence as a dead watcher. A service that cannot read a lit, unlocked screen is
 * not weak evidence, so it is not made to wait like it.
 */
internal const val PROBE_FAIL_LIMIT = 5

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
 * @param probeFailStreak consecutive failures of the watcher's own "can I still read the screen?"
 *   probe — see [PROBE_FAIL_LIMIT]. Zero when nothing has been measured, which is the same as
 *   healthy: this arm can only ever *add* a verdict, never soften one.
 */
internal fun protectionState(
    enabled: Boolean,
    lastEventAt: Long,
    now: Long,
    usedMinutesSinceLastEvent: Int?,
    updatePaused: Boolean = false,
    serviceConnected: Boolean? = null,
    msSinceProcessStart: Long = Long.MAX_VALUE,
    probeFailStreak: Int = 0,
): ProtectionState = protectionVerdict(
    enabled, lastEventAt, now, usedMinutesSinceLastEvent,
    updatePaused, serviceConnected, msSinceProcessStart, probeFailStreak,
).state

/**
 * The state **and which arm produced it** — one evaluation, so the two can never disagree.
 *
 * [protectionState] delegates here and throws the arm away. That indirection is the point: there
 * are now three routes to STALLED answering at wildly different speeds (twenty seconds, a quarter
 * of an hour, two hours), and `OutageLog` has to record which one fired or a change in detection
 * time cannot be attributed to anything — invariant 31, measuring the crossing and discarding what
 * caused it.
 *
 * ⚠️ **The obvious alternative was a second function that re-derives the arm from the same inputs,
 * and that is the shape this codebase has been hurt by most** — two implementations of one
 * ordering, drifting the first time somebody edits one. `ProtectionStateTest` also pins the two
 * halves together: STALLED always names an arm, and nothing else ever does.
 */
internal data class Verdict(val state: ProtectionState, val arm: String?)

internal fun protectionVerdict(
    enabled: Boolean,
    lastEventAt: Long,
    now: Long,
    usedMinutesSinceLastEvent: Int?,
    updatePaused: Boolean = false,
    serviceConnected: Boolean? = null,
    msSinceProcessStart: Long = Long.MAX_VALUE,
    probeFailStreak: Int = 0,
): Verdict {
    fun ok(state: ProtectionState) = Verdict(state, null)
    fun stalled(arm: String) = Verdict(ProtectionState.STALLED, arm)

    if (!enabled) return ok(ProtectionState.OFF)
    // Switched on in Settings, and not running. This is the whole of the Second Space failure:
    // the phone stops every app in the space, HyperOS doesn't always rebind us on the way back,
    // and the setting still lists us because it records a *choice*. Everything below this line
    // was the only detector before, and it needs two hours plus fifteen measured minutes of use
    // plus usage-stats permission to say the same thing — hours during which the app cheerfully
    // reported "Protection active" and blocked nothing.
    //
    // Below OFF, because telling someone to revive a service they themselves switched off would be
    // wrong. ⚠️ **ABOVE PAUSED, which is the opposite of where this used to sit.** The pause is
    // armed by an update, and an update is Android killing our process (invariant 21) — so the
    // pause covers exactly the window in which the watcher is most likely never to come back. With
    // PAUSED ranked first, that window reported "tap Reactivate", the watchdog never reached
    // STALLED, `recordFoundDead` never fired and `OutageLog.begin` never opened an episode: the
    // leading hypothesis for the owner's outages was the one case the instrument could not see.
    // The bind grace below, plus bindPending's deferrals, are what stop this crying wolf during
    // the seconds Android legitimately takes to rebind after an install.
    if (serviceConnected == false && msSinceProcessStart >= SERVICE_BIND_GRACE_MS) {
        return stalled(OutageLog.DetectedBy.UNBOUND)
    }
    // Bound, running its own timer, and unable to read a lit unlocked screen five times running.
    // That is the "alive but deaf" half of the owner's complaint, and until now the only detector
    // for it was the two-hour staleness check below.
    //
    // `== true` is required, not merely tidy. `false` is already answered above; `null` means the
    // caller could not tell whether we are bound, and a persisted streak must never be combined
    // with an unknown liveness — that is how a count left behind by a dead process gets read as a
    // verdict about a live one.
    //
    // Placed above PAUSED for the same reason the unbound arm is: an update pause covers exactly
    // the window in which Android has just killed our process (invariant 21), so ranking the pause
    // first is what hid the leading outage hypothesis from its own instrument in v1.143.
    if (serviceConnected == true && probeFailStreak >= PROBE_FAIL_LIMIT) {
        return stalled(OutageLog.DetectedBy.PROBE)
    }
    if (updatePaused) return ok(ProtectionState.PAUSED)
    if (lastEventAt <= 0L) return ok(ProtectionState.OK) // never ran yet (fresh install/just enabled)
    if (now - lastEventAt < STALE_AFTER_MS) return ok(ProtectionState.OK)
    val used = usedMinutesSinceLastEvent ?: return ok(ProtectionState.OK)
    return if (used >= STALE_MIN_USED_MINUTES) {
        stalled(OutageLog.DetectedBy.STALE)
    } else {
        ok(ProtectionState.OK)
    }
}

/**
 * **"OK, or just too early to tell?"** — true when [protectionState] answered OK *only* because
 * the bind grace had not run out yet.
 *
 * The grace exists so a cold start does not report blocking dead in the moment before Android
 * binds us. What that missed is that **the check itself is usually what cold-starts the process**:
 * the periodic worker, the boot receiver and the tile all run in a process WorkManager had to
 * start first, so [SERVICE_BIND_GRACE_MS] is measured against a process two seconds old and the
 * grace swallows the answer. The one check written to catch a watcher that never came back was, in
 * exactly that case, certain to forgive it and wait another fifteen minutes.
 *
 * Worse than slow. The OK branch **clears the alert, cancels the repeat and closes the open
 * [com.appblocker.data.OutageLog] episode** — so a cold-started check during a real outage would
 * end the episode while blocking was still down, and the log would record a recovery that never
 * happened. An instrument that can be fooled by the thing it measures is not an instrument.
 *
 * Kept as a separate predicate rather than a fifth [ProtectionState] so every screen reading the
 * four states is untouched: this is a question only the watchdog asks, and it is about what to do
 * next rather than about what is true.
 *
 * ⚠️ **An update pause no longer excludes a pending bind, and the parameter is gone.** It used to
 * exclude one, on the grounds that a paused watcher could never be reported STALLED anyway. Now
 * that it can be — the pause covers precisely the window after an install, which is Android killing
 * our process — the deferral has to cover the paused case too, or a cold-started check landing
 * seconds after an install would call a watcher dead that Android simply had not bound yet.
 * **The deferral is what makes ranking STALLED above PAUSED safe.**
 */
internal fun bindPending(
    enabled: Boolean,
    serviceConnected: Boolean?,
    msSinceProcessStart: Long,
): Boolean = enabled &&
    serviceConnected == false &&
    msSinceProcessStart < SERVICE_BIND_GRACE_MS

/**
 * How an outage stopped, for the state the watchdog left STALLED for.
 *
 * ⚠️ **Only [ProtectionState.OK] is a recovery.** The other two exits are the owner switching
 * accessibility off (the repair the alert asks him to do) and an update pausing blocking
 * mid-outage. Reporting either as a recovery would make the app look like it repairs itself, in
 * exactly the log written to find out whether it does.
 *
 * Pure, and separate from the watchdog, so the rule is a test rather than a branch nobody reads.
 */
internal fun outageEndedBy(state: ProtectionState): String = when (state) {
    ProtectionState.OK -> "recovered"
    ProtectionState.OFF -> "switched-off"
    else -> "paused"
}
