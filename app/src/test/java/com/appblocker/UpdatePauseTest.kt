package com.appblocker

import com.appblocker.data.UpdatePause
import com.appblocker.data.UpdatePause.PauseState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two update-pause flags. Since v1.177 the pause never arms (invariant 92, his choice); what is
 * left is lifting one an older build left standing, and the Reactivate tap doing the same.
 */
class UpdatePauseTest {

    // ---- lifting a pause an older build left -------------------------------------------------

    @Test
    fun `a standing pause is lifted, intent and all`() {
        val off = PauseState(paused = false, pending = false)
        assertEquals(off, UpdatePause.lifted(PauseState(paused = true, pending = false)))
        assertEquals(off, UpdatePause.lifted(PauseState(paused = false, pending = true)))
        assertEquals(off, UpdatePause.lifted(PauseState(paused = true, pending = true)))
    }

    /** One prefs read and no write on every start of a phone with nothing to lift. */
    @Test
    fun `nothing standing writes nothing`() {
        assertNull(UpdatePause.lifted(PauseState(paused = false, pending = false)))
    }

    // ---- the Reactivate tap ------------------------------------------------------------------

    /**
     * Invariant 32: clearing `paused` alone once left the intent behind, and the next service
     * connect switched blocking off again. The tap clears both, and the lift on the next start finds
     * nothing left to do.
     */
    @Test
    fun `reactivating clears both flags and leaves nothing to lift`() {
        val afterTap = UpdatePause.reactivate()
        assertEquals(PauseState(paused = false, pending = false), afterTap)
        assertNull(UpdatePause.lifted(afterTap))
    }
}
