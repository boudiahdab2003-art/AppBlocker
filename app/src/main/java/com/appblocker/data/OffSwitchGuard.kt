package com.appblocker.data

import android.content.Context

/**
 * The guard on AppBlocker's own off-switch — the Accessibility page, the device-admin page and
 * our App-info page — and the slow way back out of it.
 *
 * **Why this exists.** Strict Mode already bounced those pages, but only while a Strict session
 * was running (`handleSettingsGuard` in the watcher). Outside one, nothing defended the toggle at
 * all: two taps in Settings switched the accessibility service off and *every* block in the app
 * with it — apps, words and websites alike, since all of them are enforced by that one service.
 * The owner hit exactly that on a bad day. `docs/BLOCKING_INVARIANTS.md` had already written the
 * hole down — "Nothing else defends that toggle" — as a known gap rather than a fixed one.
 *
 * **The shape of the escape, and why it isn't a lock.** Turning the guard ON is instant. Turning
 * it off is a request that must be *served*: [UNLOCK_DELAY_MS] with the guard still standing,
 * then a short [UNLOCK_WINDOW_MS] in which the pages open and the switch works. Miss the window
 * and the request lapses, gate and all. The same shape as the adult pack's cooling-off
 * ([SettingsStore.adultPackOffRequest]), and for the same reason: the urge that wants the toggle
 * now does not survive the wait, and a protection you can never escape is one you eventually
 * uninstall. The wait is hours rather than the pack's 24 because this one has to stay usable in a
 * real emergency — being locked out of Settings on a phone you need is a genuine harm, not a
 * hypothetical one.
 *
 * Deadlines come from [GuardedDeadline], so winding the device clock cannot shorten either
 * moment — the bypass that has now been found three times in this app.
 */
object OffSwitchGuard {

    /**
     * The wait after passing the gate before the guard stands down, and how long it then stays
     * down. Long enough to outlast an impulse, short enough to survive a real emergency.
     *
     * The window is *not* five minutes, which is what it would be if it had simply kept its
     * proportion when the wait went from 15 minutes to two hours. After a two-hour wait a
     * five-minute window is a trap: miss it because you were asleep, driving or at work and the
     * price is the whole gate and another two hours. The wait is the friction; the window only
     * has to be long enough to notice and act.
     */
    const val UNLOCK_DELAY_MS = 2 * 60 * 60_000L
    const val UNLOCK_WINDOW_MS = 15 * 60_000L

    /** Plain-language names for the two durations. The block screen, the Profile row and the gate
     *  all read them from here rather than formatting the millis themselves, so the wording can't
     *  drift apart — and "120 minutes" is not how anybody says two hours. */
    const val DELAY_LABEL = "2 hours"
    const val WINDOW_LABEL = "15 minutes"

    /** What the owner is currently looking at, and what the watcher acts on. */
    enum class Phase {
        /** No request pending — the guard stands. */
        GUARDED,

        /** Requested, still serving the wait. The guard stands. */
        WAITING,

        /** The wait is served: the pages open and the switch works, until the window lapses. */
        OPEN,
    }

    /**
     * The pure decision, split out from the prefs read so it is unit-testable — the watcher itself
     * has no test coverage and can't have any as written, so anything that decides whether
     * blocking holds belongs on this side of the seam.
     *
     * @param untilUnlock ms left of the wait ([GuardedDeadline.remaining]), 0 when served or absent.
     * @param untilExpiry ms left of wait+window (`remaining(extraMs = UNLOCK_WINDOW_MS)`), 0 when
     *   the whole request has lapsed or there is none.
     */
    internal fun phase(hasRequest: Boolean, untilUnlock: Long, untilExpiry: Long): Phase = when {
        !hasRequest -> Phase.GUARDED
        untilUnlock > 0L -> Phase.WAITING
        // Wait served but the window gone: the request lapsed, so the guard closes again. Note
        // this is the same answer as "no request", on purpose — a lapsed request must never be
        // an open door, and the caller clears the record when it sees this.
        untilExpiry <= 0L -> Phase.GUARDED
        else -> Phase.OPEN
    }

    /** True when the watcher should bounce the off-switch pages. Only [Phase.OPEN] stands down. */
    internal fun armed(enabled: Boolean, phase: Phase): Boolean = enabled && phase != Phase.OPEN

    /** What tapping Profile's "Guard the off-switch" row does right now. */
    enum class Tap {
        /** Arm the guard. Instant, and allowed in every state including Strict. */
        TURN_ON,

        /** Nothing pending: send the user to the typed gate, which starts the wait. */
        START_WAIT,

        /** The wait was served and the window is open — the guard actually comes down. */
        LOWER,

        /** Strict Mode is running and this would weaken the guard. Refused. */
        REFUSED_STRICT,

        /** A wait is being served; the row shows a countdown and does nothing. */
        NOTHING,
    }

    /**
     * The row's whole decision, pure so it can be tested — `phase` and `armed` are here for the
     * same reason, and this one now carries a rule about when blocking may be weakened.
     *
     * **Strict Mode may not lower this guard, and until now it could.** The row was deliberately
     * left usable during a session, reasoned as safe because "Strict guards these pages by itself
     * regardless of this row". That is true for exactly as long as the session lasts. Switching
     * the guard off mid-session leaves it disarmed the instant Strict expires — so the session
     * itself serves as the two-hour wait, and the off-switch is simply open the moment it ends.
     * Nothing looks wrong while the session runs, because [handleSettingsGuard] short-circuits on
     * `strict` and bounces the pages anyway. The escape was pre-arranged rather than prevented.
     *
     * **[TURN_ON] stays available during Strict**, which is why the row cannot just be disabled
     * the way the other locked rows are: arming a protection is strengthening, and refusing it
     * would be the same mistake as v1.127's guard ejecting people for switching protection *on*.
     * Only the weakening directions are refused, and both of them are — starting a fresh wait and
     * completing one that is already served.
     *
     * A wait started before the session keeps running and may lapse during it. That is correct:
     * the window exists to be caught, and a Strict session is precisely when it should not be.
     */
    fun tap(guardOn: Boolean, phase: Phase, strictActive: Boolean): Tap = when {
        !guardOn -> Tap.TURN_ON
        strictActive -> Tap.REFUSED_STRICT
        phase == Phase.OPEN -> Tap.LOWER
        phase == Phase.GUARDED -> Tap.START_WAIT
        else -> Tap.NOTHING
    }

    /**
     * How long after the service starts the **Accessibility page alone** stops being bounced.
     *
     * **The guard was ejecting people for switching protection ON.** It knows the page in front
     * can turn the service off; it never asked whether the service was currently off. So the
     * moment someone enabled it, the freshly-started service saw its own accessibility page and
     * fired HOME — reported from the owner's tablet, and worse than it sounds, because that is
     * the exact path *every first-time user* walks after the disclosure screen. A protection that
     * punishes the user for turning it on is an obstacle, not a protection.
     *
     * The only way to be looking at our accessibility page at the instant the service connects is
     * that it was just switched on there, which is the opposite of what the guard defends against.
     *
     * **What it costs:** for these few seconds the toggle could be flipped straight back off
     * unbounced. Close to nothing — whoever just enabled it was one tap away from never enabling
     * it — against a first run that was broken for everyone. Eight seconds is enough to read the
     * confirmation dialog and walk out of Settings, and short enough not to be an off-switch.
     *
     * Deliberately scoped to that page and no other: uninstall, device-admin removal and the
     * App-info page during Strict have nothing to do with enabling the service, so they keep
     * bouncing throughout. See `handleSettingsGuard`.
     */
    const val ENABLE_GRACE_MS = 8_000L

    /**
     * Whether the service was enabled so recently that the accessibility page in front of us is
     * the one the user just used to switch it on.
     *
     * [msSinceConnected] must be measured monotonically (`stopwatchNow()`), never the wall clock —
     * invariant 9, and a backwards clock here would hold the guard down indefinitely.
     */
    fun justEnabled(msSinceConnected: Long): Boolean =
        msSinceConnected in 0 until ENABLE_GRACE_MS

    /** The current phase, read from prefs. */
    fun phase(context: Context, bootCount: Int): Phase {
        val request = SettingsStore.guardUnlockRequest(context)
        return phase(
            hasRequest = request != null,
            untilUnlock = request?.remaining(bootCount) ?: 0L,
            untilExpiry = request?.remaining(bootCount, extraMs = UNLOCK_WINDOW_MS) ?: 0L,
        )
    }

    /**
     * Whether the off-switch pages should be bounced right now. Called from the watcher on every
     * dangerous page open.
     *
     * Reads prefs rather than caching: the request can be made, cancelled or lapse while the
     * service keeps running, and a cached answer would be exactly the kind of stale state that
     * has caused this app's past bugs (the theme and arrangement are re-read on every show for
     * the same reason). A `getString` on an already-loaded SharedPreferences is cheap, and this
     * only runs when a guarded package is in the foreground.
     */
    fun armed(context: Context): Boolean = armed(
        enabled = SettingsStore.guardOffSwitch(context),
        phase = phase(context, DeviceBoot.count(context)),
    )
}
