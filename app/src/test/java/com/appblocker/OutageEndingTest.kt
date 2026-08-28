package com.appblocker

import com.appblocker.data.ServiceHealth
import com.appblocker.service.ProtectionState
import com.appblocker.service.outageEndedBy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Two rules that decide whether the outage log is telling the truth.
 *
 * `OutageLog` is the instrument built to answer why blocking keeps stopping on the owner's phone.
 * An instrument that overstates recoveries, or that stops advancing its own clock, answers the
 * question wrongly and confidently — which is worse than not answering it.
 */
class OutageEndingTest {

    // ---- how an outage stopped --------------------------------------------------------------

    @Test
    fun `only coming back is a recovery`() {
        assertEquals("recovered", outageEndedBy(ProtectionState.OK))
    }

    /**
     * He switched accessibility off — which is exactly what the stalled alert tells him to do, so
     * this is the *common* ending, not an exotic one. Counting it as a recovery would credit the
     * app for a repair he performed by hand.
     */
    @Test
    fun `switching it off by hand is not a recovery`() {
        assertEquals("switched-off", outageEndedBy(ProtectionState.OFF))
    }

    /**
     * An update landing mid-outage. Everything after this point is the pause, not the failure;
     * counting it would inflate the duration the log exists to establish. He takes releases several
     * times a week, so this is not a rare path either.
     */
    @Test
    fun `an update pausing mid-outage is not a recovery`() {
        assertEquals("paused", outageEndedBy(ProtectionState.PAUSED))
    }

    // ---- the health stamp and a clock that moved backwards ----------------------------------

    @Test
    fun `an ordinary stamp is left alone`() {
        assertEquals(1_000L, ServiceHealth.anchoredStamp(stored = 1_000L, now = 5_000L))
        assertEquals(5_000L, ServiceHealth.anchoredStamp(stored = 5_000L, now = 5_000L))
    }

    /** Never stamped at all stays never stamped — `protectionState` reads 0 as "no data yet". */
    @Test
    fun `zero is not rewritten`() {
        assertEquals(0L, ServiceHealth.anchoredStamp(stored = 0L, now = 5_000L))
    }

    /**
     * **The freeze.** A stamp later than now means the wall clock moved back under it — an
     * unsynced clock at boot, or a manual change. `protectionState` then computes
     * `now - lastEventAt` as a negative number, reads it as "well under two hours" and answers OK
     * for as long as the clock is behind. On a watcher that has genuinely gone deaf, no event will
     * ever arrive to correct the stamp, so the one detector written for the owner's actual
     * complaint goes blind exactly when it is needed.
     *
     * Anchoring restarts the staleness window instead of leaving one that can never elapse.
     */
    @Test
    fun `a stamp from the future is anchored to now`() {
        assertEquals(5_000L, ServiceHealth.anchoredStamp(stored = 9_000_000L, now = 5_000L))
    }
}
