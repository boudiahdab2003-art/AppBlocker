package com.appblocker

import com.appblocker.data.OutageLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outage log's arithmetic, which is the whole of it that can be got wrong quietly.
 *
 * The store around these is SharedPreferences and cannot be reached from a JVM test — the same
 * split [com.appblocker.service.protectionState] uses. What is pinned here is every value that
 * ends up in a report, because a report is read once, months later, by someone who cannot re-run
 * the phone: a wrong number there is worse than a missing one.
 */
class OutageLogTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    // ---- shape: how long was it down, and could we even tell ------------------------------

    @Test fun durationIsMeasuredMonotonically() {
        val e = OutageLog.shape(
            startedAt = now - 10 * minute,
            startedRt = 5_000_000L,
            detectedAt = now - 8 * minute,
            nowRt = 5_000_000L + 10 * minute,
            bootAtOpen = 7, bootNow = 7,
            aliveButDeaf = false,
            precededBy = OutageLog.Preceded.NOTHING,
            versionCode = 143L,
        )
        assertEquals(10 * minute, e.durationMs)
        // Two minutes passed before anything noticed — the protection actually lost.
        assertEquals(2 * minute, e.detectedAfterMs)
        assertFalse(e.rebooted)
    }

    /**
     * ⚠️ A reboot resets [android.os.SystemClock.elapsedRealtime], so the difference across one is
     * not a duration at all. Reporting "unknown" is the honest answer; a wall-clock fallback would
     * look like a measurement while being at the mercy of a clock the user can change
     * (invariant 9).
     */
    @Test fun aRebootMakesTheDurationUnknownRatherThanWrong() {
        val e = OutageLog.shape(
            startedAt = now - 30 * minute,
            startedRt = 9_000_000L,
            detectedAt = now - 29 * minute,
            // Smaller than startedRt: the clock went back to near zero at the restart.
            nowRt = 40_000L,
            bootAtOpen = 7, bootNow = 8,
            aliveButDeaf = false,
            precededBy = OutageLog.Preceded.NOTHING,
            versionCode = 143L,
        )
        assertTrue(e.rebooted)
        assertEquals(-1L, e.durationMs)
    }

    /**
     * An unreadable boot counter is -1 on both sides and compares equal — "can't tell" must not
     * invent a reboot (invariant 11). The duration stays a real measurement.
     */
    @Test fun anUnreadableBootCounterIsNotARebootV() {
        val e = OutageLog.shape(
            startedAt = now - minute, startedRt = 100_000L,
            detectedAt = now, nowRt = 100_000L + minute,
            bootAtOpen = -1, bootNow = -1,
            aliveButDeaf = true,
            precededBy = OutageLog.Preceded.UPDATE,
            versionCode = 143L,
        )
        assertFalse(e.rebooted)
        assertEquals(minute, e.durationMs)
    }

    /** Never a negative interval on screen: a clock that moved backwards reads as "no time". */
    @Test fun timeGoingBackwardsIsClampedNotNegative() {
        val e = OutageLog.shape(
            startedAt = now, startedRt = 500_000L,
            detectedAt = now - minute, nowRt = 400_000L,
            bootAtOpen = 3, bootNow = 3,
            aliveButDeaf = false,
            precededBy = OutageLog.Preceded.NOTHING,
            versionCode = 143L,
        )
        assertEquals(0L, e.durationMs)
        assertEquals(0L, e.detectedAfterMs)
    }

    /** Nothing was ever seen, so there is no moment to measure from — not a zero-length outage. */
    @Test fun noLastEventMeansTheDetectionGapIsUnknown() {
        val e = OutageLog.shape(
            startedAt = 0L, startedRt = 0L,
            detectedAt = now, nowRt = minute,
            bootAtOpen = 1, bootNow = 1,
            aliveButDeaf = false,
            precededBy = OutageLog.Preceded.NOTHING,
            versionCode = 143L,
        )
        assertEquals(-1L, e.detectedAfterMs)
    }

    /** A value from outside our own three literals never reaches a report. */
    @Test fun anUnknownCauseIsRecordedAsNothing() {
        val e = OutageLog.shape(
            startedAt = now, startedRt = 0L, detectedAt = now, nowRt = 0L,
            bootAtOpen = 1, bootNow = 1, aliveButDeaf = false,
            precededBy = "com.some.package", versionCode = 143L,
        )
        assertEquals(OutageLog.Preceded.NOTHING, e.precededBy)
    }

    // ---- blame: what had just happened ------------------------------------------------------

    /** The hypothesis this field exists to settle: our own install killed the process. */
    @Test fun anOutageJustAfterAnUpdateBlamesTheUpdate() =
        assertEquals(
            OutageLog.Preceded.UPDATE,
            OutageLog.blame(
                startedAt = now, lastUpdateAt = now - 2 * minute, bootedAt = now - 3 * minute,
            ),
        )

    /** An update just after a reboot is still an update: the more specific answer wins. */
    @Test fun updateOutranksBootWhenBothAreRecent() =
        assertEquals(
            OutageLog.Preceded.UPDATE,
            OutageLog.blame(startedAt = now, lastUpdateAt = now - minute, bootedAt = now - minute),
        )

    @Test fun anOutageJustAfterARestartBlamesTheBoot() =
        assertEquals(
            OutageLog.Preceded.BOOT,
            OutageLog.blame(startedAt = now, lastUpdateAt = now - 5 * 3_600_000L, bootedAt = now),
        )

    /**
     * **The case that makes the field worth having.** An outage in the middle of an ordinary day
     * blames nothing — and a run of these is what would rule the update hypothesis out.
     */
    @Test fun anOutageOutOfNowhereBlamesNothing() =
        assertEquals(
            OutageLog.Preceded.NOTHING,
            OutageLog.blame(
                startedAt = now,
                lastUpdateAt = now - 6 * 3_600_000L,
                bootedAt = now - 20 * 3_600_000L,
            ),
        )

    /** An update that lands *after* blocking stopped cannot be what stopped it. */
    @Test fun anUpdateAfterTheOutageStartedIsNotTheCause() =
        assertEquals(
            OutageLog.Preceded.NOTHING,
            OutageLog.blame(startedAt = now, lastUpdateAt = now + minute, bootedAt = 0L),
        )

    /** A never-updated install has no stamp, and zero must not read as "1970, close enough". */
    @Test fun noRecordedUpdateNeverBlamesOne() =
        assertEquals(
            OutageLog.Preceded.NOTHING,
            OutageLog.blame(startedAt = now, lastUpdateAt = 0L, bootedAt = 0L),
        )

    /** Just outside the window: dead a quarter of an hour later is dead for its own reasons. */
    @Test fun anOldUpdateIsNotBlamed() =
        assertEquals(
            OutageLog.Preceded.NOTHING,
            OutageLog.blame(
                startedAt = now,
                lastUpdateAt = now - OutageLog.BLAME_WINDOW_MS - 1,
                bootedAt = 0L,
            ),
        )

    // ---- aliveButDeaf: THE discriminator -----------------------------------------------------

    /**
     * The process outlived the last event it saw: it was bound the whole time and Android simply
     * stopped delivering. Not a battery manager, and a different fix.
     */
    @Test fun aProcessOlderThanTheLastEventWasAliveAndDeaf() =
        assertTrue(
            OutageLog.aliveButDeaf(
                lastEventAt = now - minute,
                nowWall = now,
                nowRt = 3_600_000L,
                // Started an hour before now, i.e. long before that last event.
                procStartRt = 0L,
            ),
        )

    /** The process is younger than the last event, so it died and came back — never rebound. */
    @Test fun aProcessYoungerThanTheLastEventDied() =
        assertFalse(
            OutageLog.aliveButDeaf(
                lastEventAt = now - 30 * minute,
                nowWall = now,
                nowRt = 3_600_000L,
                // Started one minute ago: after the last event, so this is a new process.
                procStartRt = 3_600_000L - minute,
            ),
        )

    /** Nothing has ever been seen, so there is nothing to be older than. Never a false "deaf". */
    @Test fun noEventEverMeansNotDeaf() =
        assertFalse(
            OutageLog.aliveButDeaf(
                lastEventAt = 0L, nowWall = now, nowRt = 1_000L, procStartRt = 0L,
            ),
        )

    // ---- the stored format -------------------------------------------------------------------

    /** What is written must read back identically, or a report describes a different outage. */
    @Test fun anEpisodeSurvivesTheRoundTrip() {
        val e = OutageLog.Episode(
            startedAt = now,
            durationMs = 14 * minute,
            detectedAfterMs = 3 * minute,
            aliveButDeaf = true,
            precededBy = OutageLog.Preceded.UPDATE,
            rebooted = false,
            versionCode = 143L,
        )
        assertEquals(e, OutageLog.decode(OutageLog.encode(e)))
    }

    /** The delimiter is `;` between entries, so no field may contain one. */
    @Test fun theEncodedFormHasNoEntryDelimiterInIt() =
        assertFalse(
            OutageLog.encode(
                OutageLog.Episode(now, minute, minute, false, OutageLog.Preceded.BOOT, true, 1L),
            ).contains(';'),
        )

    /** A truncated or foreign line is dropped rather than decoded into a wrong-looking outage. */
    @Test fun rubbishDecodesToNothing() {
        assertNull(OutageLog.decode("not|an|episode"))
        assertNull(OutageLog.decode(""))
        assertNull(OutageLog.decode("x|1|1|true|update|false|1"))
    }

    /** An unknown cause read back from disk is normalised the same way it is on the way in. */
    @Test fun aForeignCauseIsNormalisedOnTheWayBack() =
        assertEquals(
            OutageLog.Preceded.NOTHING,
            OutageLog.decode("$now|60000|60000|true|whatever|false|143")?.precededBy,
        )

    // ---- the rendered line -------------------------------------------------------------------

    /** Minutes, and an unmeasurable duration says so rather than printing a misleading zero. */
    @Test fun anUnknownDurationRendersAsAQuestionMark() {
        val line = OutageLog.Episode(
            now, -1L, -1L, false, OutageLog.Preceded.BOOT, true, 143L,
        ).render()
        assertTrue(line.contains("down=?min"))
        assertTrue(line.contains("noticedAfter=?min"))
    }

    /** Every field in a rendered line is a number or one of our own words — never content. */
    @Test fun aRenderedLineCarriesTheFactsAReportNeeds() {
        val line = OutageLog.Episode(
            now, 14 * minute, 3 * minute, true, OutageLog.Preceded.UPDATE, false, 143L,
        ).render()
        assertTrue(line.contains("down=14min"))
        assertTrue(line.contains("noticedAfter=3min"))
        assertTrue(line.contains("deaf=true"))
        assertTrue(line.contains("after=update"))
    }
}
