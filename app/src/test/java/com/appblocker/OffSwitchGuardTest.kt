package com.appblocker

import com.appblocker.data.GuardedDeadline
import com.appblocker.data.OffSwitchGuard
import com.appblocker.data.OffSwitchGuard.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard on AppBlocker's own off-switch.
 *
 * This is the layer the owner walked through on a bad day: outside Strict Mode nothing defended
 * the Accessibility toggle, and switching the service off takes every block in the app with it.
 * The decision below is what the watcher now asks on every dangerous page, so the states that
 * matter are the ones where it must answer "still guarded" — a pending wait, and a lapsed
 * request, both of which superficially look like "the owner asked to be let out".
 *
 * Deliberately pure: the watcher itself has no test coverage and can't have any as written, so
 * anything deciding whether blocking holds lives on this side of the seam.
 */
class OffSwitchGuardTest {

    private val boot = 4
    private val delay = OffSwitchGuard.UNLOCK_DELAY_MS
    private val window = OffSwitchGuard.UNLOCK_WINDOW_MS

    // --- the phases ---

    @Test
    fun `no request means guarded`() {
        assertEquals(Phase.GUARDED, OffSwitchGuard.phase(false, 0L, 0L))
    }

    @Test
    fun `a pending wait is still guarded`() {
        // The whole point of the delay: asking to be let out must not let you out.
        assertEquals(Phase.WAITING, OffSwitchGuard.phase(true, untilUnlock = 1L, untilExpiry = 1L))
        assertEquals(
            Phase.WAITING,
            OffSwitchGuard.phase(true, untilUnlock = delay, untilExpiry = delay + window),
        )
    }

    @Test
    fun `the window opens only once the wait is served`() {
        assertEquals(Phase.OPEN, OffSwitchGuard.phase(true, untilUnlock = 0L, untilExpiry = window))
    }

    @Test
    fun `a lapsed request closes the guard again rather than leaving the door open`() {
        // Both zero = the wait was served but the window went by unused. This must read the same
        // as "no request at all" — the failure that would matter is a lapsed request being
        // mistaken for an open one, which would leave the toggle reachable indefinitely.
        assertEquals(Phase.GUARDED, OffSwitchGuard.phase(true, untilUnlock = 0L, untilExpiry = 0L))
    }

    // --- what the watcher acts on ---

    @Test
    fun `armed in every phase except the open window`() {
        assertTrue(OffSwitchGuard.armed(enabled = true, phase = Phase.GUARDED))
        assertTrue(OffSwitchGuard.armed(enabled = true, phase = Phase.WAITING))
        assertFalse(OffSwitchGuard.armed(enabled = true, phase = Phase.OPEN))
    }

    @Test
    fun `switched off means never armed`() {
        for (p in Phase.entries) assertFalse(OffSwitchGuard.armed(enabled = false, phase = p))
    }

    // --- the deadline underneath, which is what makes the wait unskippable ---
    // remainingAt so both clocks are explicit: under isReturnDefaultValues
    // SystemClock.elapsedRealtime() is 0 while currentTimeMillis() is genuine.

    private fun request(rtStart: Long = 1_000L, wallStart: Long = 1_700_000_000_000L) =
        GuardedDeadline(
            realtimeStart = rtStart,
            realtimeEnd = rtStart + delay,
            wallStart = wallStart,
            wallEnd = wallStart + delay,
            bootCount = boot,
        )

    private fun phaseAt(r: GuardedDeadline, nowRt: Long, nowWall: Long) = OffSwitchGuard.phase(
        hasRequest = true,
        untilUnlock = r.remainingAt(boot, nowRt = nowRt, nowWall = nowWall),
        untilExpiry = r.remainingAt(boot, nowRt = nowRt, nowWall = nowWall, extraMs = window),
    )

    @Test
    fun `winding the clock forward does not serve the wait early`() {
        // The bypass this app has now found three times: one minute in monotonically, but the
        // wall clock jumped a day. Still waiting.
        val r = request()
        assertEquals(
            Phase.WAITING,
            phaseAt(r, nowRt = 1_000L + 60_000L, nowWall = 1_700_000_000_000L + 86_400_000L),
        )
    }

    @Test
    fun `winding the clock backward does not open or freeze the guard`() {
        // The mirror-image mistake: a negative interval reading as "no time passed". Either way
        // the answer must be "still guarded", never OPEN.
        val r = request()
        assertEquals(
            Phase.WAITING,
            phaseAt(r, nowRt = 1_000L + 60_000L, nowWall = 1_600_000_000_000L),
        )
    }

    @Test
    fun `the window runs from the served wait and then closes`() {
        val r = request()
        val served = 1_000L + delay
        assertEquals(Phase.OPEN, phaseAt(r, nowRt = served, nowWall = 1L))
        assertEquals(Phase.OPEN, phaseAt(r, nowRt = served + window - 1_000L, nowWall = 1L))
        // Window gone: closed again, not open forever.
        assertEquals(Phase.GUARDED, phaseAt(r, nowRt = served + window, nowWall = 1L))
        assertEquals(Phase.GUARDED, phaseAt(r, nowRt = served + window * 100, nowWall = 1L))
    }

    @Test
    fun `the wait is the full delay and the window really is shorter`() {
        // Guards the intent of the two constants rather than their exact values: the wait has to
        // outlast an impulse, and the window has to be short enough that a forgotten unlock
        // isn't an open door for the rest of the day.
        assertTrue(delay >= 10 * 60_000L)
        assertTrue(window < delay)
    }
}
