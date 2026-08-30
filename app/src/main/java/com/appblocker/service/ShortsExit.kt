package com.appblocker.service

/**
 * What the screen says about YouTube's reel player, as one reading.
 *
 * This is [ScreenText.isShortsOnScreen]'s three-way answer split one notch finer, and for the same
 * reason it was made three-way in the first place: *"can't tell" is not "no"*. The exit needs one
 * distinction that the raise does not — **"the player is gone" and "YouTube is gone" are different
 * facts**, and only the second one ends the walk. A raise can treat them alike because in both
 * cases there is nothing to cover.
 */
internal enum class PlayerView {
    /** Reel markers found. A Short is in front of him right now. */
    OPEN,

    /** YouTube, readable, and no reel markers in it — the player closed and the feed is behind it. */
    CLOSED,

    /** Some other package is in front. He is out of YouTube; there is nothing left to do. */
    GONE,

    /** No window tree, or our own package in front of it. **Never** treated as progress. */
    UNREADABLE,
}

/**
 * The walk that closes a Short and then leaves YouTube, as plain values in / decision out — the
 * same shape as [CoverGate], [decideBlock] and [com.appblocker.data.SessionClock]. The service
 * owns the timers, the binder calls and the global actions; this owns *what to do next*, so the
 * rules below are testable rather than trusted.
 *
 * ## Why this exists
 *
 * Dismissing a Shorts cover used to be one line: drop the cover, `GLOBAL_ACTION_HOME`. That did
 * not close anything. HOME fires `onUserLeaveHint()` — the signal that hands a playing video to a
 * **picture-in-picture** window — so the block put the Short in a floating player over the
 * launcher, and left the reel on YouTube's back stack so re-opening resumed onto it. The owner
 * reported both halves in one sentence. See [CoverGate.exitFor].
 *
 * ## The one rule everything else serves
 *
 * ⚠️ **[Step.GIVE_UP] never presses HOME, and neither does any reading that is not a confirmed
 * close.** HOME on a playing Short is the bug. If the player cannot be confirmed shut, the right
 * outcome is to do nothing further and let the scan re-cover it — never to press the one button
 * known to make it float. `givingUpNeverPressesHome` states that as a test.
 */
internal object ShortsExit {

    /**
     * How many BACKs the walk will spend closing the player.
     *
     * One is the expected case: BACK on a reel closes it. Three is room for a confirmation sheet
     * or a comments panel opened over it, without ever becoming a way to walk backwards through
     * his whole history — past this the walk gives up rather than keeps pressing, because a BACK
     * that has not closed anything three times over is not going to.
     */
    const val MAX_BACKS = 3

    /** Gap between readings. Long enough for a BACK to land and the tree to settle, short enough
     *  that the whole walk stays under a second and a half in the ordinary case. */
    const val CLOSE_POLL_MS = 300L

    /** The whole walk's budget. Past this it stops, having pressed no HOME. */
    const val CLOSE_GIVE_UP_MS = 1_800L

    /**
     * Agreeing [PlayerView.CLOSED] readings required before HOME is allowed.
     *
     * ⚠️ **This is the guard on the one path that could put the bug straight back.**
     * [ScreenText.isShortsOnScreen] answers "no Short" even when its walk exhausted `MAX_NODES`
     * without finding a marker — a deliberate choice ("Decided, not defaulted"), sound for a
     * raise, where the cost of being wrong is a cover that does not go up and will be retried on
     * the next event. Here the cost of being wrong is HOME on a playing Short, which is the
     * floating window. One extra reading, 300ms later, is the cheapest possible defence against a
     * single unlucky walk.
     */
    const val CLOSED_READS_FOR_HOME = 2

    /** What the service should do next. */
    enum class Step {
        /** Press BACK, then read again. */
        BACK,

        /** The player is confirmed shut. Hand over to the ordinary exit watcher and leave. */
        HOME,

        /** Read again after [CLOSE_POLL_MS] without acting. */
        WAIT,

        /** He is out of YouTube already. Nothing left to do, and nothing to press. */
        DONE,

        /** Out of budget or out of BACKs, with no confirmed close. **Presses nothing.** */
        GIVE_UP,
    }

    /**
     * The next move, from the current reading and what the walk has spent so far.
     *
     * [closedReads] is how many *consecutive* [PlayerView.CLOSED] readings have been seen — the
     * caller resets it to zero on any other reading, so an OPEN or an UNREADABLE in the middle
     * discards the evidence rather than letting two non-adjacent closes add up.
     *
     * Branch order is load-bearing and pinned by tests:
     * - [PlayerView.GONE] first: leaving YouTube ends the walk however much budget is left.
     * - A confirmed close outranks the deadline. Reaching [CLOSE_GIVE_UP_MS] on the same turn as
     *   the second agreeing read should still leave, not abandon a walk that has succeeded.
     * - BACK only while the player is [PlayerView.OPEN] — never on a reading we could not make.
     * - The deadline, then WAIT.
     */
    fun next(
        view: PlayerView,
        backsSent: Int,
        closedReads: Int,
        sinceStartMs: Long,
    ): Step = when {
        view == PlayerView.GONE -> Step.DONE
        view == PlayerView.CLOSED && closedReads >= CLOSED_READS_FOR_HOME -> Step.HOME
        view == PlayerView.OPEN && backsSent < MAX_BACKS -> Step.BACK
        sinceStartMs >= CLOSE_GIVE_UP_MS -> Step.GIVE_UP
        view == PlayerView.OPEN -> Step.GIVE_UP // out of BACKs, and pressing HOME here is the bug
        else -> Step.WAIT
    }
}
