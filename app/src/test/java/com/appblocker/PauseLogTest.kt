package com.appblocker

import com.appblocker.data.OngoingReport
import com.appblocker.data.OutageLog
import com.appblocker.data.PauseLog
import com.appblocker.data.StoppageHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariant 91: the after-update pause and an unrepaired stoppage are unprotected time that could
 * not report itself. PauseLog times the first; OngoingReport files both while they last.
 */
class PauseLogTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    private fun pause(at: Long, used: Int = 30) = PauseLog.Episode(
        startedAt = at,
        durationMs = 120 * minute,
        rebooted = false,
        fromBoot = false,
        usedDuringMin = used,
        versionCode = 177L,
        endedBy = PauseLog.EndedBy.REACTIVATED,
    )

    // ---- when it began ----------------------------------------------------------------------

    @Test
    fun `the pause's own stamp wins over the version change`() {
        assertEquals(now - 10 * minute, PauseLog.startEstimate(now - 10 * minute, now - 20 * minute, 0L, now))
    }

    @Test
    fun `a pause from a build that did not stamp it starts at the update that armed it`() {
        assertEquals(now - 20 * minute, PauseLog.startEstimate(0L, now - 20 * minute, 0L, now))
    }

    @Test
    fun `minutes a closed stoppage already counted are not counted again`() {
        assertEquals(now - minute, PauseLog.startEstimate(now - 60 * minute, 0L, now - minute, now))
    }

    @Test
    fun `nothing known starts now, and a stamp from the future is clamped`() {
        assertEquals(now, PauseLog.startEstimate(0L, 0L, 0L, now))
        assertEquals(now, PauseLog.startEstimate(now + 5 * minute, 0L, 0L, now))
    }

    // ---- how long -----------------------------------------------------------------------------

    @Test
    fun `a pause is measured monotonically, and a restart makes it a floor`() {
        val plain = PauseLog.shape(now, 1_000L, false, 1_000L + 90 * minute, 3, 3, 177L, PauseLog.EndedBy.REACTIVATED)
        assertEquals(90 * minute, plain.durationMs)
        assertFalse(plain.fromBoot)

        val restarted = PauseLog.shape(now, 50 * minute, false, 7 * minute, 3, 4, 177L, PauseLog.EndedBy.REACTIVATED)
        assertEquals(7 * minute, restarted.durationMs)
        assertTrue(restarted.rebooted)
        assertTrue(restarted.fromBoot)
    }

    @Test
    fun `an unknown ending and unknown use stay unknown`() {
        val e = PauseLog.shape(now, 0L, false, minute, 1, 1, 177L, "made-up", usedDuringMin = -7)
        assertEquals(PauseLog.EndedBy.UNKNOWN, e.endedBy)
        assertEquals(OutageLog.UNKNOWN_USE, e.usedDuringMin)
    }

    // ---- stored and shown ---------------------------------------------------------------------

    @Test
    fun `an episode survives encode and decode`() {
        val e = pause(now)
        assertEquals(e, PauseLog.decode(PauseLog.encode(e)))
        assertNull(PauseLog.decode("1|2|3"))
    }

    @Test
    fun `its line names itself and can never pass for an outage`() {
        val line = pause(now, used = 150).render()
        assertTrue(line, "  PAUSED  " in line)
        assertTrue(line, "used=150min" in line)
        assertFalse(line, "down=" in line)
    }

    @Test
    fun `pauses sit in the stoppage list in time order`() {
        val outage = OutageLog.Episode(
            startedAt = now - 30 * minute, durationMs = minute, detectedAfterMs = minute,
            aliveButDeaf = false, precededBy = OutageLog.Preceded.NOTHING, rebooted = false,
            versionCode = 177L,
        )
        val lines = StoppageHistory.merge(
            listOf(outage), emptyList(), pauses = listOf(pause(now), pause(now - 60 * minute)),
        )
        assertEquals(3, lines.size)
        assertTrue(lines[0], "PAUSED" in lines[0])
        assertFalse(lines[1], "PAUSED" in lines[1])
        assertTrue(lines[2], "PAUSED" in lines[2])
    }

    // ---- reported while it lasts --------------------------------------------------------------

    @Test
    fun `a stoppage is reported mid-episode only once it is old and has cost real use`() {
        val k = OngoingReport.Kind.STOPPED
        assertFalse(OngoingReport.due(k, 29 * minute, 60))
        assertFalse(OngoingReport.due(k, 120 * minute, 9))
        assertTrue(OngoingReport.due(k, 30 * minute, 10))
    }

    @Test
    fun `a pause is reported once it has cost a quarter of an hour of use`() {
        val k = OngoingReport.Kind.PAUSED
        assertFalse(OngoingReport.due(k, 14 * minute, 60))
        assertFalse(OngoingReport.due(k, 600 * minute, 14))
        assertTrue(OngoingReport.due(k, 15 * minute, 15))
    }

    @Test
    fun `use that cannot be told never files`() {
        assertFalse(OngoingReport.due(OngoingReport.Kind.PAUSED, 600 * minute, null))
        assertFalse(OngoingReport.due(OngoingReport.Kind.STOPPED, 600 * minute, -1))
        assertFalse(OngoingReport.due("other", 600 * minute, 600))
    }

    @Test
    fun `the time half alone decides whether use is worth reading`() {
        assertFalse(OngoingReport.oldEnough(OngoingReport.Kind.STOPPED, 29 * minute))
        assertTrue(OngoingReport.oldEnough(OngoingReport.Kind.PAUSED, 15 * minute))
    }
}
