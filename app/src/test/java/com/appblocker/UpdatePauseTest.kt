package com.appblocker

import com.appblocker.data.UpdatePause
import com.appblocker.data.UpdatePause.PauseState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two update-pause flags, and the rule that keeps them from disagreeing.
 *
 * Blocking pausing after an update is a feature. Blocking pausing again **after he has turned it
 * back on** is the bug this file exists for, and it had no test because the decision was spread
 * across two `apply()` calls in a coroutine rather than written down as a rule.
 */
class UpdatePauseTest {

    // ---- resolving a pending intent -------------------------------------------------------

    @Test
    fun `a pending pause arms when no strict session is running`() {
        assertEquals(
            PauseState(paused = true, pending = false),
            UpdatePause.resolve(PauseState(paused = false, pending = true), strictRunning = false),
        )
    }

    /** A running session keeps blocking ON, so the pause is dropped rather than the session cleared. */
    @Test
    fun `a strict session drops the pause instead of ending itself`() {
        assertEquals(
            PauseState(paused = false, pending = false),
            UpdatePause.resolve(PauseState(paused = false, pending = true), strictRunning = true),
        )
    }

    /** Either way the intent is consumed — that is the half that used to survive on its own. */
    @Test
    fun `resolving always consumes the intent`() {
        assertFalse(
            UpdatePause.resolve(PauseState(paused = false, pending = true), true).pending,
        )
        assertFalse(
            UpdatePause.resolve(PauseState(paused = false, pending = true), false).pending,
        )
    }

    /** An already-armed pause is not lifted by a second update arriving during a strict session. */
    @Test
    fun `resolving never turns an existing pause off`() {
        assertTrue(
            UpdatePause.resolve(PauseState(paused = true, pending = true), strictRunning = true).paused,
        )
    }

    @Test
    fun `nothing pending changes nothing`() {
        assertEquals(
            PauseState(paused = true, pending = false),
            UpdatePause.resolve(PauseState(paused = true, pending = false), strictRunning = false),
        )
    }

    // ---- the bug ---------------------------------------------------------------------------

    /**
     * **The regression this whole file is for.**
     *
     * `resolvePendingPause` writes the decision and consumes the intent, and those used to be two
     * separate `apply()` calls with a Room database open between them, running in a broadcast
     * receiver's process. Surviving half-applied left `pending` true with `paused` already set.
     *
     * The owner then taps Reactivate. If that clears only `paused`, the stranded intent is still
     * there — and `checkVersionChange` re-reads it on **every** service connect, so the next boot,
     * update, space switch or revive silently switches blocking off again, with the accessibility
     * switch still reading ON and nothing on screen having changed.
     */
    @Test
    fun `reactivating survives a stranded pending intent`() {
        val stranded = PauseState(paused = true, pending = true)

        val afterTap = UpdatePause.reactivate()
        assertEquals(PauseState(paused = false, pending = false), afterTap)

        // The next service connect re-runs the resolution. Blocking must stay on.
        val afterNextConnect = UpdatePause.resolve(afterTap, strictRunning = false)
        assertFalse(
            "a lifted pause must not come back on the next service connect",
            afterNextConnect.paused,
        )

        // And the old shape, kept next to it so the difference is visible: clearing `paused` alone
        // leaves the intent, and the very next connect re-pauses.
        val halfCleared = stranded.copy(paused = false)
        assertTrue(
            "this is what the bug did — kept as the counter-example",
            UpdatePause.resolve(halfCleared, strictRunning = false).paused,
        )
    }
}
