package com.appblocker

import com.appblocker.data.SelfEnableLog
import com.appblocker.data.SelfEnableLog.Skip
import com.appblocker.data.SelfToggleLog
import com.appblocker.data.SelfToggleLog.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AppBlocker writing its own Accessibility entry back ON when a check finds it off (invariant 84) —
 * his choice of 28 Sep 2026, "Turn back on, always".
 *
 * Pinned: that it does switch back on a switch that went off across a restart or behind the guard's
 * back, and the three it must never touch — an install that was never switched on by hand, and a
 * switch-off made with the off-switch guard down, now or at the moment it went off.
 */
class SelfEnableLogTest {

    private fun decide(
        on: Boolean = false,
        everRan: Boolean = true,
        guardNow: Boolean = true,
        guardWhenOff: Boolean? = null,
        permitted: Boolean = true,
        pending: Boolean = false,
        since: Long = -1L,
        streak: Int = 0,
    ) = SelfEnableLog.decide(on, everRan, guardNow, guardWhenOff, permitted, pending, since, streak)

    // ---- what it switches back on ---------------------------------------------------------------

    /** Saturday 26 Sep: off after a restart, no unbind belongs to it — the case he chose this for. */
    @Test fun `a switch that went off while nothing was running is switched back on`() =
        assertNull(decide(guardWhenOff = null))

    /** "Always", he said — a hand that got past the guard included. */
    @Test fun `a switch turned off with the guard up is switched back on`() =
        assertNull(decide(guardWhenOff = true))

    // ---- what it must never touch ---------------------------------------------------------------

    @Test fun `a switch that reads on is left alone`() = assertEquals(Skip.SWITCHED_ON, decide(on = true))

    /** #202: an emulator holding the permission, never switched on. Accessibility is his to grant. */
    @Test fun `an install whose watcher never ran is never switched on`() {
        assertEquals(Skip.NEVER_RAN, decide(everRan = false))
        assertEquals(Skip.NEVER_RAN, decide(everRan = false, guardWhenOff = true, permitted = true))
    }

    /** The served two-hour wait: inside the window the switch works, as the guard promises. */
    @Test fun `with the guard down now it stays off`() =
        assertEquals(Skip.GUARD_DOWN, decide(guardNow = false))

    /** And a switch-off made in that window stays off once the window has closed. */
    @Test fun `a switch turned off while the guard was down stays off after it is back up`() =
        assertEquals(Skip.GUARD_DOWN, decide(guardNow = true, guardWhenOff = false))

    @Test fun `without the permission from a computer it never tries`() =
        assertEquals(Skip.NO_PERMISSION, decide(permitted = false))

    // ---- how often ------------------------------------------------------------------------------

    @Test fun `a write still waiting for its verdict blocks the next`() =
        assertEquals(Skip.ATTEMPT_PENDING, decide(pending = true))

    @Test fun `one write per cooldown, and a new boot starts fresh`() {
        assertEquals(Skip.COOLDOWN, decide(since = SelfEnableLog.COOLDOWN_MS - 1))
        assertNull(decide(since = SelfEnableLog.COOLDOWN_MS))
        assertNull(decide(since = -1L))
    }

    // ---- what counts as it having worked --------------------------------------------------------

    private fun judge(afterMs: Long, rebound: Boolean) = SelfToggleLog.judge(
        pendingRt = 1_000L, pendingBoot = 3, nowRt = 1_000L + afterMs, boot = 3, rebound = rebound,
        windowMs = SelfEnableLog.JUDGE_WINDOW_MS, staleMs = SelfEnableLog.STALE_PENDING_MS,
    )

    /** 28 Sep, Android 16 emulator: after a restart the watcher connected 40–50 s after the write,
     *  and the repair's 30 s window filed our own write as `no_rebind` and the period as `rebound`. */
    @Test fun `a rebind most of a minute after the write is still the switch-on's`() {
        assertEquals(Verdict.HELPED, judge(45_000L, rebound = true))
        assertEquals(Verdict.HELPED, judge(SelfEnableLog.JUDGE_WINDOW_MS, rebound = true))
        assertEquals(Verdict.NO_REBIND, judge(SelfEnableLog.JUDGE_WINDOW_MS + 1, rebound = true))
    }

    @Test fun `a late rebind is never judged futile before it arrives`() {
        assertNull(judge(SelfEnableLog.JUDGE_WINDOW_MS, rebound = false))
        assertEquals(Verdict.NO_REBIND, judge(SelfEnableLog.STALE_PENDING_MS, rebound = false))
        assertTrue(SelfEnableLog.STALE_PENDING_MS > SelfEnableLog.JUDGE_WINDOW_MS)
    }

    @Test fun `writes that did not help stop it for an hour`() {
        val streak = SelfEnableLog.MAX_FUTILE_STREAK
        assertEquals(Skip.GAVE_UP, decide(streak = streak, since = SelfEnableLog.COOLDOWN_MS))
        assertEquals(Skip.GAVE_UP, decide(streak = streak, since = SelfEnableLog.GIVE_UP_FOR_MS - 1))
        assertNull(decide(streak = streak, since = SelfEnableLog.GIVE_UP_FOR_MS))
        assertNull(decide(streak = streak, since = -1L))
        assertNull(decide(streak = streak - 1, since = SelfEnableLog.COOLDOWN_MS))
    }
}
