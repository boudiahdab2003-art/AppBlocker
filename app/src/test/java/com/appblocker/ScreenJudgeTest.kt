package com.appblocker

import com.appblocker.service.screenIsJudgeable
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The full truth table, because the bug was one cell of it.**
 *
 * `canObserveEvents` read `km?.isKeyguardLocked != true` — so an absent KeyguardManager came out as
 * "not locked" and the revive verdict was recorded on a screen nobody had confirmed was unlocked.
 * The PowerManager half of the same expression got it right, four characters away. Nine cases here
 * rather than the two that were being thought about.
 */
class ScreenJudgeTest {

    @Test
    fun `only a lit, confirmed-unlocked screen is judgeable`() {
        assertTrue(screenIsJudgeable(interactive = true, keyguardLocked = false))
    }

    @Test
    fun `a screen that is off is never judgeable`() {
        assertFalse(screenIsJudgeable(false, false))
        assertFalse(screenIsJudgeable(false, true))
        assertFalse(screenIsJudgeable(false, null))
    }

    @Test
    fun `a locked screen is never judgeable`() {
        assertFalse(screenIsJudgeable(true, true))
        assertFalse(screenIsJudgeable(null, true))
    }

    /**
     * The cell that was wrong. A missing system service is not a report that the phone is
     * unlocked, and treating it as one scores a working self-repair as futile — which is the
     * conclusion `revivesHelped` exists to reach honestly.
     */
    @Test
    fun `an unknown answer is never judgeable, on either half`() {
        assertFalse("no KeyguardManager is not proof of an unlocked screen",
            screenIsJudgeable(interactive = true, keyguardLocked = null))
        assertFalse("no PowerManager is not proof of a lit screen",
            screenIsJudgeable(interactive = null, keyguardLocked = false))
        assertFalse(screenIsJudgeable(null, null))
    }
}
