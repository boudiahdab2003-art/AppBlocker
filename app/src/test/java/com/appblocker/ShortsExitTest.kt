package com.appblocker

import com.appblocker.service.PlayerView
import com.appblocker.service.ShortsExit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The walk that closes a Short before leaving YouTube.
 *
 * Reported 30 Aug 2026: the block threw him out of YouTube but left the Short playing in a
 * floating window over the home screen, and re-opening YouTube dropped him straight back onto it.
 * The cause was the dismissal firing `GLOBAL_ACTION_HOME`, which is `onUserLeaveHint()` — the
 * exact signal that hands a playing video to picture-in-picture.
 *
 * **So the rule these tests exist to hold is a negative one: never press HOME unless the player is
 * confirmed shut.** Everything else here is in service of that.
 *
 * What cannot be tested at this level, and is measured on the phone instead (`SilenceLog`'s
 * `SHORTS_EXIT_CLOSED` / `SHORTS_EXIT_BLIND`): whether BACK actually pops YouTube's reel, whether
 * a floating window results, and whether re-opening lands on the feed. Those are facts about a
 * real build of someone else's app on a real device.
 */
class ShortsExitTest {

    private fun next(
        view: PlayerView,
        backsSent: Int = 1,
        closedReads: Int = 0,
        sinceStartMs: Long = 0L,
    ) = ShortsExit.next(view, backsSent, closedReads, sinceStartMs)

    // --- The rule the whole walk exists for ---

    /**
     * ⚠️ **The bug, as a mechanical check.** Out of time is not permission to press the one button
     * known to make a playing Short float away. Giving up costs nothing — the cover is already
     * down and the scan owns the player again, so a Short still playing is simply re-covered.
     */
    @Test fun givingUpNeverPressesHome() {
        assertEquals(
            ShortsExit.Step.GIVE_UP,
            next(PlayerView.UNREADABLE, sinceStartMs = ShortsExit.CLOSE_GIVE_UP_MS),
        )
        // Out of BACKs with the player still open: also a give-up, and still not a HOME.
        assertEquals(
            ShortsExit.Step.GIVE_UP,
            next(PlayerView.OPEN, backsSent = ShortsExit.MAX_BACKS),
        )
    }

    /** A Short is on screen. There is no reading of that which ends in HOME. */
    @Test fun homeIsNeverTheAnswerWhileThePlayerIsOpen() {
        for (backs in 0..ShortsExit.MAX_BACKS + 1) {
            for (t in listOf(0L, ShortsExit.CLOSE_GIVE_UP_MS, ShortsExit.CLOSE_GIVE_UP_MS * 4)) {
                assertNotEquals(
                    "open player, backs=$backs t=$t",
                    ShortsExit.Step.HOME,
                    next(PlayerView.OPEN, backsSent = backs, closedReads = 99, sinceStartMs = t),
                )
            }
        }
    }

    /**
     * "Can't tell" is not "closed". Our own cover reporting as the active window is the routine
     * way to get here, and treating it as progress is the same can't-tell-is-not-a-no mistake this
     * codebase has paid for repeatedly.
     */
    @Test fun homeIsNeverTheAnswerWhenTheScreenCannotBeRead() {
        for (t in listOf(0L, ShortsExit.CLOSE_GIVE_UP_MS - 1, ShortsExit.CLOSE_GIVE_UP_MS)) {
            assertNotEquals(
                ShortsExit.Step.HOME,
                next(PlayerView.UNREADABLE, closedReads = 99, sinceStartMs = t),
            )
        }
    }

    /**
     * ⚠️ One reading is not enough, and this is the guard on the only path that could reinstate
     * the bug. `readPlayerView` answers CLOSED even when its walk ran out of node budget without
     * finding a reel marker — right for raising a cover, where being wrong costs a retry, and
     * wrong here, where being wrong costs a HOME on a playing Short. A second agreeing read 300ms
     * later is the cheapest defence against one unlucky walk.
     */
    @Test fun aClosedPlayerNeedsTwoAgreeingReadsBeforeHome() {
        assertEquals(ShortsExit.Step.WAIT, next(PlayerView.CLOSED, closedReads = 1))
        assertEquals(
            ShortsExit.Step.HOME,
            next(PlayerView.CLOSED, closedReads = ShortsExit.CLOSED_READS_FOR_HOME),
        )
    }

    // --- Branch order ---

    /**
     * A confirmed close outranks the deadline. Reaching the budget on the same turn as the second
     * agreeing read is a walk that has *succeeded*, and abandoning it there would leave him in
     * YouTube after the app had already established the player was shut.
     */
    @Test fun aReadableClosedPlayerBeatsTheDeadline() {
        assertEquals(
            ShortsExit.Step.HOME,
            next(
                PlayerView.CLOSED,
                closedReads = ShortsExit.CLOSED_READS_FOR_HOME,
                sinceStartMs = ShortsExit.CLOSE_GIVE_UP_MS * 10,
            ),
        )
    }

    /**
     * He is out of YouTube — the BACK that closed the reel also left the app, or he walked out
     * himself. Nothing left to close and nothing to press, however much budget remains.
     */
    @Test fun leavingYouTubeEndsTheExitAtOnce() {
        assertEquals(ShortsExit.Step.DONE, next(PlayerView.GONE))
        assertEquals(
            ShortsExit.Step.DONE,
            next(PlayerView.GONE, backsSent = ShortsExit.MAX_BACKS, sinceStartMs = 99_999L),
        )
    }

    // --- Bounds ---

    /** BACK is for closing the player, never for walking backwards through his history. */
    @Test fun theBackPressesAreBounded() {
        assertEquals(ShortsExit.Step.BACK, next(PlayerView.OPEN, backsSent = 0))
        assertEquals(
            ShortsExit.Step.BACK,
            next(PlayerView.OPEN, backsSent = ShortsExit.MAX_BACKS - 1),
        )
        assertNotEquals(
            ShortsExit.Step.BACK,
            next(PlayerView.OPEN, backsSent = ShortsExit.MAX_BACKS),
        )
    }

    /** The walk ends. An unreadable screen forever is a give-up, not a loop. */
    @Test fun theCloseWindowIsBounded() {
        assertEquals(ShortsExit.Step.WAIT, next(PlayerView.UNREADABLE, sinceStartMs = 0L))
        assertEquals(
            ShortsExit.Step.GIVE_UP,
            next(PlayerView.UNREADABLE, sinceStartMs = ShortsExit.CLOSE_GIVE_UP_MS),
        )
    }
}
