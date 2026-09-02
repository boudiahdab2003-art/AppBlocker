package com.appblocker

import com.appblocker.data.SilenceLog
import com.appblocker.service.CoverGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dial for the half of this app that has never had one. Under-blocking is invisible to the
 * owner by definition, so the boundary this draws is the whole value: draw it in the wrong place
 * and the counter reads zero through exactly the bug it exists to show.
 */
class SilenceLogTest {

    @Test
    fun `a decline inside the short grace is the mechanism working`() {
        // Those are the departing screen's stragglers, which is what the short window is for.
        // Counting them would bury the signal in noise.
        assertFalse(SilenceLog.isLate(0L))
        assertFalse(SilenceLog.isLate(CoverGate.DISMISS_GRACE_MS - 1))
    }

    @Test
    fun `a decline past the short grace is the suspicious kind`() {
        // Past here the only thing still suppressing is the long "the app is stuck on screen"
        // extension - so the watcher is quiet while the user sits in the app it just covered.
        assertTrue(SilenceLog.isLate(CoverGate.DISMISS_GRACE_MS))
        assertTrue(SilenceLog.isLate(CoverGate.DISMISS_GRACE_MS + 1))
    }

    @Test
    fun `the boundary is the SHORT grace, not the long one`() {
        // The failure that would re-hide the bug. The eight-second extension is the thing under
        // suspicion; measuring from its far end means the counter can only fire after the
        // suppression has already stopped applying - a dial wired to read zero forever.
        assertTrue(
            "a decline three seconds after a dismissal must count",
            SilenceLog.isLate(3_000L),
        )
        assertTrue(CoverGate.DISMISS_GRACE_MS < CoverGate.DISMISS_GRACE_STUCK_MS)
    }

    @Test
    fun `every counter is named and listed`() {
        // The diagnostics screen and the bug report read these counters by name, so KINDS is the
        // registry: a counter added without being listed is one nobody will ever read. The size is
        // hardcoded deliberately — it is what makes adding one an act rather than a line that
        // slips in unsurfaced, and it did its job for the two Shorts-exit counters.
        assertEquals(6, SilenceLog.KINDS.size)
        assertEquals(SilenceLog.KINDS.toSet().size, SilenceLog.KINDS.size)
        assertTrue(SilenceLog.KINDS.all { it.isNotBlank() })
    }
}
