package com.appblocker

import com.appblocker.ui.NO_ATTEMPT
import com.appblocker.ui.SetupAttempt
import com.appblocker.ui.setupAttempt
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rescue that fires when someone comes back from Settings without having switched the blocker
 * on — the moment a first-time setup is most likely to be abandoned.
 *
 * Both directions matter, and the false-positive one matters more: telling somebody who has just
 * arrived that they have already failed is worse than saying nothing at all.
 */
class SetupAttemptTest {

    @Test
    fun `says nothing before anyone has been to Settings`() {
        // The screen has been sitting there for four resumes — a rotation, a phone call, whatever.
        // Nobody has pressed Grant, so there is nothing to rescue.
        assertEquals(SetupAttempt.NONE, setupAttempt(NO_ATTEMPT, resumeTick = 4, granted = false))
    }

    @Test
    fun `says nothing while they are still standing here`() {
        // Grant pressed at tick 2 and the app has not been resumed since: Settings may not even
        // have opened yet. Accusing them now would be accusing them of nothing.
        assertEquals(SetupAttempt.NONE, setupAttempt(attemptTick = 2, resumeTick = 2, granted = false))
    }

    @Test
    fun `the rescue fires once they are back and it is still off`() {
        assertEquals(SetupAttempt.STUCK, setupAttempt(attemptTick = 2, resumeTick = 3, granted = false))
    }

    @Test
    fun `coming back having done it says so instead`() {
        assertEquals(
            SetupAttempt.SUCCEEDED,
            setupAttempt(attemptTick = 2, resumeTick = 3, granted = true),
        )
    }

    /**
     * A second failed trip must still read as stuck. The count keeps climbing, and an
     * only-the-first-time rescue would go quiet exactly when someone is trying hardest.
     */
    @Test
    fun `a second failed trip still rescues`() {
        assertEquals(SetupAttempt.STUCK, setupAttempt(attemptTick = 3, resumeTick = 9, granted = false))
    }

    /**
     * Guards the ordering of the checks: "granted" alone must never be enough to claim success,
     * or a permission that was already on before the wizard opened would announce a victory
     * nobody won.
     */
    @Test
    fun `already granted before any attempt is still silence`() {
        assertEquals(SetupAttempt.NONE, setupAttempt(NO_ATTEMPT, resumeTick = 7, granted = true))
    }
}
