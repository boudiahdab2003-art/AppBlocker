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
 * uninstall. The wait is minutes rather than the pack's 24 hours because this one has to be
 * usable in a real emergency — being locked out of Settings on a phone you need is a genuine
 * harm, not a hypothetical one.
 *
 * Deadlines come from [GuardedDeadline], so winding the device clock cannot shorten either
 * moment — the bypass that has now been found three times in this app.
 */
object OffSwitchGuard {

    /** The wait after passing the gate before the guard stands down, and how long it then stays
     *  down. Deliberately short enough to be survivable and long enough to outlast an impulse. */
    const val UNLOCK_DELAY_MS = 15 * 60_000L
    const val UNLOCK_WINDOW_MS = 5 * 60_000L

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
