package com.appblocker

import com.appblocker.data.OutageLog
import com.appblocker.data.SwitchOffLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The switched-off log's arithmetic and its one clue.
 *
 * Invariant 70: the watchdog read an OFF switch as a choice and timed nothing, so on 10 Sep 2026
 * fourteen hours with no watcher left no line anywhere. What is pinned here is every value that
 * ends up in a report — above all `how`, because it is the only thing that can tell "he switched
 * it off" from "the phone did".
 */
class SwitchOffLogTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    private fun unbind(
        at: Long,
        settings: Boolean? = false,
        screen: Boolean? = true,
        guard: Boolean? = false,
    ) = SwitchOffLog.Unbind(at = at, settingsInFront = settings, screenOn = screen, guardArmed = guard)

    // ---- how: who switched it off -----------------------------------------------------------

    @Test fun `unbound with Settings in front reads as a hand at the toggle`() {
        assertEquals(
            SwitchOffLog.How.SETTINGS_OPEN,
            SwitchOffLog.classify(now - 2 * minute, unbind(now - minute, settings = true)),
        )
    }

    /** A dark screen outranks Settings being the last app: nobody toggles a switch they cannot see. */
    @Test fun `unbound with the screen dark was not done by hand`() {
        assertEquals(
            SwitchOffLog.How.SCREEN_OFF,
            SwitchOffLog.classify(now - 2 * minute, unbind(now - minute, settings = true, screen = false)),
        )
    }

    @Test fun `unbound from some other app is not the toggle`() {
        assertEquals(
            SwitchOffLog.How.ELSEWHERE,
            SwitchOffLog.classify(now - 2 * minute, unbind(now - minute, settings = false)),
        )
    }

    @Test fun `no unbind at all means it went off while nothing was running`() {
        assertEquals(SwitchOffLog.How.NOT_RUNNING, SwitchOffLog.classify(now - minute, null))
    }

    /**
     * ⚠️ An unbind older than the last sign of life belongs to an earlier period: the watcher came
     * back after it. Reading it anyway would blame today's restart on last week's toggle.
     */
    @Test fun `an unbind from before the last sign of life says nothing about this one`() {
        assertEquals(
            SwitchOffLog.How.NOT_RUNNING,
            SwitchOffLog.classify(now - minute, unbind(now - 60 * minute, settings = true)),
        )
    }

    /** A stamp written by a build that did not record what was in front is not evidence of "no". */
    @Test fun `an unbind that recorded nothing about the screen is unknown, not guessed`() {
        assertEquals(
            SwitchOffLog.How.UNKNOWN,
            SwitchOffLog.classify(now - 2 * minute, unbind(now - minute, settings = null, screen = null)),
        )
    }

    // ---- when it started ------------------------------------------------------------------

    @Test fun `it started at the latest sign of life`() {
        assertEquals(now - 50 * minute, SwitchOffLog.startEstimate(now - 60 * minute, now - 50 * minute, 0L, now))
        assertEquals(now - 60 * minute, SwitchOffLog.startEstimate(now - 60 * minute, 0L, 0L, now))
    }

    /** An outage closed by the same check already counts the minutes up to now. */
    @Test fun `it never starts before an outage that was just closed`() {
        assertEquals(now, SwitchOffLog.startEstimate(now - 60 * minute, 0L, now, now))
    }

    @Test fun `no stamps at all and a stamp from the future both fall back to now`() {
        assertEquals(now, SwitchOffLog.startEstimate(0L, 0L, 0L, now))
        assertEquals(now, SwitchOffLog.startEstimate(now + 10 * minute, 0L, 0L, now))
    }

    // ---- shape: how long, and what could be told -------------------------------------------

    private fun shape(
        nowRt: Long = 5_000_000L + 30 * minute,
        bootNow: Int = 7,
        fromBoot: Boolean = false,
        how: String = SwitchOffLog.How.NOT_RUNNING,
        precededBy: String = OutageLog.Preceded.NOTHING,
        endedBy: String = OutageLog.EndedBy.APP_OPENED,
    ) = SwitchOffLog.shape(
        startedAt = now - 30 * minute,
        startedRt = 5_000_000L,
        fromBoot = fromBoot,
        detectedAt = now - 20 * minute,
        nowRt = nowRt,
        bootAtOpen = 7,
        bootNow = bootNow,
        how = how,
        guardArmed = null,
        precededBy = precededBy,
        versionCode = 163L,
        endedBy = endedBy,
        usedDuringMin = 0,
    )

    @Test fun `the length is measured monotonically`() {
        val e = shape()
        assertEquals(30 * minute, e.durationMs)
        assertEquals(10 * minute, e.detectedAfterMs)
        assertFalse(e.rebooted)
        assertFalse(e.fromBoot)
    }

    /**
     * Unlike an outage, a restart mid-period leaves a true statement to make: the switch was off
     * before and is found back on after, and a watcher that had come back in between would have
     * closed the period itself. So it is a floor from the boot, labelled as one.
     */
    @Test fun `a restart while it was off is a floor from the boot, not an unknown`() {
        val e = shape(nowRt = 40 * minute, bootNow = 8)
        assertTrue(e.rebooted)
        assertTrue(e.fromBoot)
        assertEquals(40 * minute, e.durationMs)
    }

    @Test fun `values it does not know decode as unknown rather than as something plausible`() {
        val e = shape(how = "bogus", precededBy = "bogus", endedBy = "bogus")
        assertEquals(SwitchOffLog.How.UNKNOWN, e.how)
        assertEquals(OutageLog.Preceded.NOTHING, e.precededBy)
        assertEquals(OutageLog.EndedBy.UNKNOWN, e.endedBy)
    }

    // ---- storage and the line in the report ------------------------------------------------

    /** Every field set away from its default, so a field dropped by encode or decode shows (invariant 62). */
    private val full = SwitchOffLog.Episode(
        startedAt = now - 90 * minute,
        durationMs = 12 * minute,
        detectedAfterMs = 3 * minute,
        how = SwitchOffLog.How.SCREEN_OFF,
        guardArmed = true,
        precededBy = OutageLog.Preceded.BOOT,
        rebooted = true,
        fromBoot = true,
        usedDuringMin = 4,
        versionCode = 163L,
        endedBy = OutageLog.EndedBy.REBOUND,
    )

    @Test fun `a period survives being stored and read back`() {
        assertEquals(full, SwitchOffLog.decode(SwitchOffLog.encode(full)))
        val noGuard = full.copy(guardArmed = null, how = SwitchOffLog.How.NOT_RUNNING)
        assertEquals(noGuard, SwitchOffLog.decode(SwitchOffLog.encode(noGuard)))
    }

    @Test fun `a malformed row is dropped rather than half read`() {
        assertNull(SwitchOffLog.decode("1|2|3"))
        assertNull(SwitchOffLog.decode("x|2|3|screen-off|?|boot|true|true|0|163|rebound"))
    }

    @Test fun `a line names itself and can never pass for an outage`() {
        val line = full.render()
        assertTrue(line, "SWITCHED-OFF" in line)
        assertTrue(line, "off=12min+fromBoot" in line)
        assertTrue(line, "how=screen-off" in line)
        assertTrue(line, "guard=true" in line)
        assertTrue(line, "after=boot" in line)
        assertTrue(line, "backBy=rebound" in line)
        assertFalse(line, "down=" in line)
        assertTrue("guard=?" in full.copy(guardArmed = null).render())
    }
}
