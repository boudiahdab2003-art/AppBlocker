package com.appblocker

import com.appblocker.data.OwnUi
import com.appblocker.data.SelfRestoreLog
import com.appblocker.data.SelfRestoreLog.Skip
import com.appblocker.data.SelfRestoreLog.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AppBlocker reopening itself, and the verdict on every attempt (invariant 74).
 *
 * Two things are pinned: when it may interrupt him at all, and what counts as it having worked. The
 * second matters more — `revives` read 67 of 67 while the fault it was meant to repair carried on,
 * because it counted running as working.
 */
class SelfRestoreLogTest {

    private fun decide(
        unbound: Boolean = true,
        appOpen: Boolean = false,
        inUse: Boolean = true,
        overlay: Boolean = true,
        pending: Boolean = false,
        since: Long = -1L,
        streak: Int = 0,
    ) = SelfRestoreLog.decide(unbound, appOpen, inUse, overlay, pending, since, streak)

    // ---- when it may open at all ----------------------------------------------------------------

    @Test fun `an unbound watcher on a phone in use is reopened`() = assertNull(decide())

    /** A bound watcher that went deaf is not helped by a screen opening; it has its own repair. */
    @Test fun `a bound watcher is never reopened`() = assertEquals(Skip.NOT_UNBOUND, decide(unbound = false))

    @Test fun `nothing is opened over a dark or locked screen`() =
        assertEquals(Skip.PHONE_NOT_IN_USE, decide(inUse = false))

    @Test fun `the app already open, no overlay permission, or an attempt in flight all decline`() {
        assertEquals(Skip.APP_ALREADY_OPEN, decide(appOpen = true))
        assertEquals(Skip.NO_OVERLAY_PERMISSION, decide(overlay = false))
        assertEquals(Skip.ATTEMPT_PENDING, decide(pending = true))
    }

    @Test fun `one attempt per cooldown, and a new boot starts fresh`() {
        assertEquals(Skip.COOLDOWN, decide(since = SelfRestoreLog.COOLDOWN_MS - 1))
        assertNull(decide(since = SelfRestoreLog.COOLDOWN_MS))
        assertNull(decide(since = -1L))
    }

    /** A repair that keeps interrupting him without helping is worse than none. */
    @Test fun `tries that did not help stop it for a day`() {
        val streak = SelfRestoreLog.MAX_FUTILE_STREAK
        assertEquals(Skip.GAVE_UP, decide(streak = streak, since = SelfRestoreLog.COOLDOWN_MS))
        assertEquals(Skip.GAVE_UP, decide(streak = streak, since = SelfRestoreLog.GIVE_UP_FOR_MS - 1))
        assertNull(decide(streak = streak, since = SelfRestoreLog.GIVE_UP_FOR_MS))
        assertNull(decide(streak = streak - 1, since = SelfRestoreLog.COOLDOWN_MS))
    }

    // ---- what counts as having worked -----------------------------------------------------------

    private val rt = 5_000_000L

    private fun judge(rebound: Boolean, now: Long, launched: Boolean = true, boot: Int = 7) =
        SelfRestoreLog.judge(
            pendingRt = rt, pendingBoot = 7, launched = launched, nowRt = now, boot = boot, rebound = rebound,
        )

    @Test fun `a rebind inside the window is credited to the attempt`() {
        assertEquals(Verdict.HELPED, judge(rebound = true, now = rt + 1_000L))
        assertEquals(Verdict.HELPED, judge(rebound = true, now = rt + SelfRestoreLog.JUDGE_WINDOW_MS))
    }

    /** Blocking coming back minutes later is not the reopen working, and must not be scored as if it were. */
    @Test fun `a rebind after the window is not credited`() {
        val late = rt + SelfRestoreLog.JUDGE_WINDOW_MS + 1
        assertEquals(Verdict.NO_REBIND, judge(rebound = true, now = late, launched = true))
        assertEquals(Verdict.NOT_LAUNCHED, judge(rebound = true, now = late, launched = false))
    }

    @Test fun `an unjudged attempt waits, then is judged by whether the screen ever opened`() {
        assertNull(judge(rebound = false, now = rt + SelfRestoreLog.STALE_PENDING_MS - 1))
        assertEquals(Verdict.NO_REBIND, judge(rebound = false, now = rt + SelfRestoreLog.STALE_PENDING_MS, launched = true))
        assertEquals(Verdict.NOT_LAUNCHED, judge(rebound = false, now = rt + SelfRestoreLog.STALE_PENDING_MS, launched = false))
    }

    @Test fun `a restart between the attempt and its verdict is evidence neither way`() {
        assertEquals(Verdict.DROPPED, judge(rebound = true, now = 1_000L, boot = 8))
        assertEquals(Verdict.DROPPED, judge(rebound = false, now = 1_000L, boot = 8))
    }

    @Test fun `nothing pending is nothing to judge`() =
        assertNull(SelfRestoreLog.judge(0L, 7, false, rt, 7, rebound = true))

    // ---- the owner's own open, the other witness ------------------------------------------------

    @Test fun `a rebind seconds after our own screen came up followed it`() {
        assertTrue(OwnUi.openedWithin(nowRt = 100_000L, windowMs = 15_000L, resumedAt = 99_000L))
        assertFalse(OwnUi.openedWithin(nowRt = 100_000L, windowMs = 15_000L, resumedAt = 80_000L))
        assertFalse("a process that never showed a screen", OwnUi.openedWithin(100_000L, 15_000L, resumedAt = 0L))
        assertFalse("a stamp from the future is not 'just now'", OwnUi.openedWithin(100_000L, 15_000L, resumedAt = 120_000L))
    }
}
