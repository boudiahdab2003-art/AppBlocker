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
    fun `the wait outlasts an impulse and the window is shorter but not a trap`() {
        // The intent of the two constants rather than their exact values. The wait has to be long
        // enough that the urge has passed by the time it is served; the window has to be shorter
        // (a forgotten unlock must not be an open door all day) but not so short that missing it
        // by being asleep or at work costs the whole wait again.
        assertTrue(delay >= 60 * 60_000L)
        assertTrue(window < delay)
        assertTrue(window >= 10 * 60_000L)
    }

    /**
     * **The guard used to eject people for switching protection ON.**
     *
     * It bounces the page that can turn the service off, and never asked whether the service was
     * currently off — so enabling it meant the freshly-started watcher saw its own accessibility
     * page and fired HOME at somebody doing the right thing. Reported from the owner's tablet,
     * and it broke the first run for *every* new user, since the disclosure screen sends them to
     * that exact page.
     *
     * Only the arithmetic is pinned here. **That the grace applies to the accessibility page and
     * to nothing else lives in `handleSettingsGuard` and is verified by reading** — the guard has
     * no extracted decision function the way `decideBlock` does, and a test that re-asserts this
     * comparison against itself would look like coverage without being any.
     */
    @Test
    fun `the grace covers switching on and then gets out of the way`() {
        assertTrue(OffSwitchGuard.justEnabled(0L))
        assertTrue(OffSwitchGuard.justEnabled(OffSwitchGuard.ENABLE_GRACE_MS - 1))
        assertFalse(OffSwitchGuard.justEnabled(OffSwitchGuard.ENABLE_GRACE_MS))
        // A service running for any real length of time guards the page as it always did.
        assertFalse(OffSwitchGuard.justEnabled(60_000L))
        assertFalse(OffSwitchGuard.justEnabled(24 * 60 * 60_000L))
    }

    /**
     * A negative interval means the reading is nonsense — a clock that went backwards, or the
     * "long ago" sentinel before the service has ever connected. It must read as **not** just
     * enabled: answering yes there would hold the guard down on no evidence, which is the same
     * clock-driven bypass this file's deadlines already defend against (invariant 9).
     */
    @Test
    fun `a nonsensical interval does not open the grace`() {
        assertFalse(OffSwitchGuard.justEnabled(-1L))
        assertFalse(OffSwitchGuard.justEnabled(Long.MIN_VALUE / 2))
    }

    /** Long enough to read the confirmation dialog and walk out of Settings; short enough that it
     *  is not itself an off-switch. */
    @Test
    fun `the grace is seconds, not minutes`() {
        assertTrue(OffSwitchGuard.ENABLE_GRACE_MS >= 3_000L)
        assertTrue(OffSwitchGuard.ENABLE_GRACE_MS <= 30_000L)
    }

    @Test
    fun `the labels say the same thing as the constants`() {
        // The block screen, the Profile row and the gate all print these words while the guard
        // acts on the numbers. Changing one and not the other would leave the app confidently
        // telling the owner a wait that isn't the one being enforced — and he'd only find out by
        // sitting through it.
        assertEquals("${delay / 60 / 60_000} hours", OffSwitchGuard.DELAY_LABEL)
        assertEquals("${window / 60_000} minutes", OffSwitchGuard.WINDOW_LABEL)
    }
}
