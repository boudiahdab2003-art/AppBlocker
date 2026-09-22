package com.appblocker

import com.appblocker.data.OwnUi
import com.appblocker.data.SelfToggleLog
import com.appblocker.data.SelfToggleLog.Marker
import com.appblocker.data.SelfToggleLog.Skip
import com.appblocker.data.SelfToggleLog.Verdict
import com.appblocker.service.AccessibilityUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The silent repair — AppBlocker switching its own Accessibility entry off and on when the phone has
 * killed the watcher — and the verdict on every attempt (invariant 82).
 *
 * Three things are pinned: when it may touch the switch at all, what counts as it having worked, and
 * — the one that matters most — that a switch he turned off himself is never switched back on.
 */
class SelfToggleLogTest {

    private fun decide(
        unbound: Boolean = true,
        on: Boolean = true,
        permitted: Boolean = true,
        pending: Boolean = false,
        since: Long = -1L,
        streak: Int = 0,
    ) = SelfToggleLog.decide(unbound, on, permitted, pending, since, streak)

    // ---- when it may touch the switch at all ----------------------------------------------------

    @Test fun `an unbound watcher with the switch on is toggled`() = assertNull(decide())

    /** A bound watcher that went deaf has its own repair; cutting it off could end one that works. */
    @Test fun `a bound watcher is never toggled`() = assertEquals(Skip.NOT_UNBOUND, decide(unbound = false))

    /** Off is his choice. This repair restores an entry that is on and dead, never one he turned off. */
    @Test fun `a switch that reads off is never turned on`() =
        assertEquals(Skip.SWITCHED_OFF, decide(on = false))

    @Test fun `without the permission from a computer it never tries`() =
        assertEquals(Skip.NO_PERMISSION, decide(permitted = false))

    @Test fun `an attempt still waiting for its verdict blocks the next`() =
        assertEquals(Skip.ATTEMPT_PENDING, decide(pending = true))

    @Test fun `one attempt per cooldown, and a new boot starts fresh`() {
        assertEquals(Skip.COOLDOWN, decide(since = SelfToggleLog.COOLDOWN_MS - 1))
        assertNull(decide(since = SelfToggleLog.COOLDOWN_MS))
        assertNull(decide(since = -1L))
    }

    @Test fun `tries that did not help stop it for an hour`() {
        val streak = SelfToggleLog.MAX_FUTILE_STREAK
        assertEquals(Skip.GAVE_UP, decide(streak = streak, since = SelfToggleLog.COOLDOWN_MS))
        assertEquals(Skip.GAVE_UP, decide(streak = streak, since = SelfToggleLog.GIVE_UP_FOR_MS - 1))
        assertNull(decide(streak = streak, since = SelfToggleLog.GIVE_UP_FOR_MS))
        assertNull(decide(streak = streak - 1, since = SelfToggleLog.COOLDOWN_MS))
    }

    /** The order of the refusals is part of the rule: an off switch outranks everything after it. */
    @Test fun `an off switch is named before any other refusal`() =
        assertEquals(Skip.SWITCHED_OFF, decide(on = false, permitted = false, pending = true, streak = 9, since = 0L))

    // ---- what counts as having worked -----------------------------------------------------------

    private val rt = 5_000_000L

    private fun judge(rebound: Boolean, now: Long, boot: Int = 7) =
        SelfToggleLog.judge(pendingRt = rt, pendingBoot = 7, nowRt = now, boot = boot, rebound = rebound)

    @Test fun `a rebind inside the window is credited to the attempt`() {
        assertEquals(Verdict.HELPED, judge(rebound = true, now = rt + 1_000L))
        assertEquals(Verdict.HELPED, judge(rebound = true, now = rt + SelfToggleLog.JUDGE_WINDOW_MS))
    }

    /** Blocking coming back later is not the toggle working, and must not be scored as if it were. */
    @Test fun `a rebind after the window is not credited`() =
        assertEquals(Verdict.NO_REBIND, judge(rebound = true, now = rt + SelfToggleLog.JUDGE_WINDOW_MS + 1))

    @Test fun `an unjudged attempt waits, then counts as no rebind`() {
        assertNull(judge(rebound = false, now = rt + SelfToggleLog.STALE_PENDING_MS - 1))
        assertEquals(Verdict.NO_REBIND, judge(rebound = false, now = rt + SelfToggleLog.STALE_PENDING_MS))
    }

    @Test fun `a restart between the attempt and its verdict is evidence neither way`() {
        assertEquals(Verdict.DROPPED, judge(rebound = true, now = 1_000L, boot = 8))
        assertEquals(Verdict.DROPPED, judge(rebound = false, now = 1_000L, boot = 8))
    }

    @Test fun `nothing pending is nothing to judge`() =
        assertNull(SelfToggleLog.judge(0L, 7, rt, 7, rebound = true))

    // ---- the marker: never leave the switch off, never turn his off back on -----------------------

    private fun marker(ageMs: Long, on: Boolean, stampBoot: Int = 7, boot: Int = 7) =
        SelfToggleLog.markerState(stampRt = rt, stampBoot = stampBoot, nowRt = rt + ageMs, boot = boot, switchedOn = on)

    @Test fun `no marker says nothing`() =
        assertEquals(Marker.NONE, SelfToggleLog.markerState(0L, 7, rt, 7, switchedOn = false))

    /** Between the two writes the switch reads off because we made it so: nothing may judge that. */
    @Test fun `a toggle between its writes holds every check off`() {
        assertEquals(Marker.IN_FLIGHT, marker(ageMs = 0L, on = false))
        assertEquals(Marker.IN_FLIGHT, marker(ageMs = SelfToggleLog.IN_FLIGHT_MS - 1, on = false))
        assertEquals(Marker.IN_FLIGHT, marker(ageMs = 500L, on = true))
    }

    /** The process died between the writes and left the entry off: the next check puts it back. */
    @Test fun `an interrupted toggle is finished`() {
        assertEquals(Marker.FINISH_ON, marker(ageMs = SelfToggleLog.IN_FLIGHT_MS, on = false))
        assertEquals(Marker.FINISH_ON, marker(ageMs = SelfToggleLog.INTERRUPTED_FINISH_WINDOW_MS - 1, on = false))
    }

    /**
     * ⚠️ **The rule this whole file exists for.** A marker that outlived its toggle must be cleared,
     * never acted on — or the next time he switched blocking off himself it would read as our
     * unfinished toggle and be switched straight back on against him.
     */
    @Test fun `a marker that is done, old or from before a restart is cleared, never acted on`() {
        assertEquals("the entry is already back on", Marker.STALE, marker(ageMs = SelfToggleLog.IN_FLIGHT_MS, on = true))
        assertEquals("too old to be ours to undo", Marker.STALE, marker(ageMs = SelfToggleLog.INTERRUPTED_FINISH_WINDOW_MS, on = false))
        assertEquals("from before a restart", Marker.STALE, marker(ageMs = SelfToggleLog.IN_FLIGHT_MS, on = false, stampBoot = 6))
        assertEquals("a stamp from the future", Marker.STALE, marker(ageMs = -1L, on = false))
    }

    // ---- the list it rewrites is shared by every accessibility service on the phone ----------------

    private val ours = "com.appblocker/com.appblocker.service.BlockerAccessibilityService"
    private val lux = "com.urbandroid.lux/com.urbandroid.lux.TwilightService"
    private val milink = "com.milink.service/com.miui.circulate.world.MLAccessibilityService"

    /** His own phone's list on 22 Sep 2026: two other services, both of which must survive. */
    @Test fun `the off write removes ours and keeps everyone else in order`() {
        assertEquals("$lux:$milink", AccessibilityUtil.listWithout("$lux:$milink:$ours", ours))
        assertEquals("$lux:$milink", AccessibilityUtil.listWithout("$lux:$ours:$milink", ours))
    }

    @Test fun `the off write understands the short spelling too`() =
        assertEquals(lux, AccessibilityUtil.listWithout("$lux:com.appblocker/.service.BlockerAccessibilityService", ours))

    @Test fun `ours alone leaves an empty list`() {
        assertEquals("", AccessibilityUtil.listWithout(ours, ours))
        assertEquals("", AccessibilityUtil.listWithout(null, ours))
    }

    @Test fun `the on write appends ours only when missing`() {
        assertEquals("$lux:$milink:$ours", AccessibilityUtil.listWith("$lux:$milink", ours))
        assertEquals("already there: unchanged", "$lux:$ours", AccessibilityUtil.listWith("$lux:$ours", ours))
        assertEquals(ours, AccessibilityUtil.listWith("", ours))
        assertEquals(ours, AccessibilityUtil.listWith(null, ours))
    }

    @Test fun `off then on gives back a list with everyone in it`() {
        val start = "$lux:$milink:$ours"
        val back = AccessibilityUtil.listWith(AccessibilityUtil.listWithout(start, ours), ours)
        assertEquals(start, back)
        assertTrue(AccessibilityUtil.isListed(back, ours))
    }

    // ---- the owner's own open, the other witness ------------------------------------------------

    @Test fun `a rebind seconds after our own screen came up followed it`() {
        assertTrue(OwnUi.openedWithin(nowRt = 100_000L, windowMs = 15_000L, resumedAt = 99_000L))
        assertFalse(OwnUi.openedWithin(nowRt = 100_000L, windowMs = 15_000L, resumedAt = 80_000L))
        assertFalse("a process that never showed a screen", OwnUi.openedWithin(100_000L, 15_000L, resumedAt = 0L))
        assertFalse("a stamp from the future is not 'just now'", OwnUi.openedWithin(100_000L, 15_000L, resumedAt = 120_000L))
    }
}
