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

    /**
     * **An update stamped just AFTER blocking stopped is exactly what an update looks like.**
     *
     * This test used to assert the opposite, under the name
     * `anUpdateAfterTheOutageStartedIsNotTheCause` and the reasoning "an update that lands after
     * blocking stopped cannot be what stopped it". That reasoning is wrong *for this app*, and it
     * made the one field built to settle the update hypothesis incapable of ever reporting it.
     *
     * `UpdatePause.checkVersionChange` says it plainly: installing our own APK IS Android killing
     * this process. So the order is forced — blocking stops first, the new process starts, and
     * only THEN does anything exist to write `lastUpdateAt`. The stamp is always later than
     * `startedAt`. A rule that only credits an update at or before the start can never fire.
     *
     * Found 1 Sep 2026 in the first real outage log: ten stoppages, `after=nothing` on every one,
     * and one of them beginning 42 seconds after v1.146 was published.
     */
    @Test fun anUpdateStampedJustAfterTheOutageStartedIsBlamed() =
        assertEquals(
            OutageLog.Preceded.UPDATE,
            OutageLog.blame(startedAt = now, lastUpdateAt = now + minute, bootedAt = 0L),
        )

    /** Far enough after and it is a coincidence, not a cause. */
    @Test fun anUpdateLongAfterTheOutageStartedIsNotBlamed() =
        assertEquals(
            OutageLog.Preceded.NOTHING,
            OutageLog.blame(startedAt = now, lastUpdateAt = now + 6 * 3_600_000L, bootedAt = 0L),
        )

    /**
     * A reboot has the same shape: the device boots *after* blocking stopped, because the last
     * event the watcher saw was before the phone went down.
     */
    @Test fun aBootJustAfterTheOutageStartedIsBlamed() =
        assertEquals(
            OutageLog.Preceded.BOOT,
            OutageLog.blame(startedAt = now, lastUpdateAt = 0L, bootedAt = now + 2 * minute),
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

    // ---- which detector found it ---------------------------------------------------------------

    /**
     * ⚠️ **The episodes already on his phone have to survive this field being added.**
     *
     * `decode` rejected anything that was not exactly seven fields, so bumping the format without
     * accepting the old shape would have discarded every outage recorded since v1.143 — at exactly
     * the moment the version that could finally interpret them was installed, and before a single
     * one had ever been read. The first real data this project has about its worst bug is the last
     * thing a schema change should throw away.
     */
    @Test fun oldSevenFieldEpisodesStillDecode() {
        val e = OutageLog.decode("$now|60000|60000|true|update|false|143")
        assertEquals(60_000L, e?.durationMs)
        assertEquals(OutageLog.Preceded.UPDATE, e?.precededBy)
        // Not guessed at: a row written before the field existed genuinely does not know.
        assertEquals(OutageLog.DetectedBy.UNKNOWN, e?.detectedBy)
    }

    /** And the new shape round-trips, arm included. */
    @Test fun anEpisodeSaysWhichArmDetectedIt() {
        val e = OutageLog.Episode(
            now, 14 * minute, 3 * minute, true, OutageLog.Preceded.UPDATE, false, 145L,
            detectedBy = OutageLog.DetectedBy.PROBE,
        )
        assertEquals(e, OutageLog.decode(OutageLog.encode(e)))
        assertTrue(e.render().contains("by=probe"))
    }



    /**
     * **The version in a stoppage line is a BUILD number, and it is not the 1.x version.**
     *
     * `versionCode = 150` sits beside `versionName = "1.149"` in `app/build.gradle.kts` — they
     * are one apart, and always have been. The line rendered `v=146` into a report whose own
     * header said `[1.146]`, so a reader matches the two and reads the wrong release. That is not
     * hypothetical: on 1 Sep 2026 it sent the first pass at his outage data looking at 1.146 when
     * every stoppage but one happened on 1.145.
     *
     * The legend in `BugReport` calls this field the thing that separates a regression from a
     * spread. Naming the wrong release is precisely how a regression hunt goes to the wrong place.
     */
    @Test fun theStoppageLineLabelsTheVersionAsABuildNumber() {
        val line = OutageLog.shape(
            startedAt = now - minute, startedRt = 1_000L, detectedAt = now,
            nowRt = 1_000L + minute, bootAtOpen = 3, bootNow = 3,
            aliveButDeaf = false, precededBy = OutageLog.Preceded.NOTHING, versionCode = 146L,
        ).render()
        assertTrue(
            "a stoppage line must not render the build number as \"v=\", which reads as the 1.x " +
                "version shown everywhere else in the same report. Got: $line",
            line.contains("build=146"),
        )
        assertFalse("$line still carries the ambiguous v= label", line.contains("v=146"))
    }

    /** An arm this version does not know is normalised, exactly like a foreign cause. */
    @Test fun anUnknownArmIsNormalisedOnTheWayBack() =
        assertEquals(
            OutageLog.DetectedBy.UNKNOWN,
            OutageLog.decode("$now|60000|60000|true|update|false|143|telepathy")?.detectedBy,
        )
    // ---- what brought blocking back --------------------------------------------------------

    /**
     * **The field the recovery work turns on.** `outageEnded: recovered` has always said blocking
     * returned and never what returned it. If these come back overwhelmingly `app-opened`,
     * recovery waits for him to pick the phone up; if `background`, it recovers alone and the
     * hours-long tail is something else. The two point at different fixes.
     */
    @Test fun whatEndedTheOutageSurvivesTheRoundTrip() {
        val e = OutageLog.shape(
            startedAt = now - 10 * minute, startedRt = 5_000_000L, detectedAt = now - 9 * minute,
            nowRt = 5_000_000L + 10 * minute, bootAtOpen = 7, bootNow = 7,
            aliveButDeaf = true, precededBy = OutageLog.Preceded.NOTHING, versionCode = 151L,
            detectedBy = OutageLog.DetectedBy.PROBE,
            endedBy = OutageLog.EndedBy.APP_OPENED,
        )
        assertEquals(OutageLog.EndedBy.APP_OPENED, e.endedBy)
        assertEquals(
            OutageLog.EndedBy.APP_OPENED,
            OutageLog.decode(OutageLog.encode(e))?.endedBy,
        )
        assertTrue(e.render().contains("backBy=app-opened"))
    }

    /** An episode written before the field existed says so, rather than claiming a cause. */
    @Test fun anOlderEpisodeHasNoEndedByAndDoesNotInventOne() {
        assertEquals(
            OutageLog.EndedBy.UNKNOWN,
            OutageLog.decode("$now|60000|60000|true|nothing|false|143|probe")?.endedBy,
        )
    }

    /** A value nobody defined is not quietly adopted. */
    @Test fun anUnknownEndedByIsRejected() =
        assertEquals(
            OutageLog.EndedBy.UNKNOWN,
            OutageLog.decode("$now|60000|60000|true|nothing|false|143|probe|telepathy")?.endedBy,
        )

    /**
     * **Every ending this app can record must decode back to itself.**
     *
     * `decode` maps any value not in `EndedBy.ALL` to `UNKNOWN`, which is right for a value some
     * older build wrote and wrong for one we ship: adding a constant and forgetting the set is
     * not a compile error, not a crash, and not visible in a report -- it is an episode that
     * silently forgets how it ended, which is the single thing the field exists to record.
     *
     * The same trap as the sanitiser's allow-list, one layer down.
     */
    @Test fun everyEndingIsDecodable() {
        val declared = OutageLog.EndedBy::class.java.declaredFields
            .filter { it.type == String::class.java }
            .mapNotNull { it.isAccessible = true; it.get(OutageLog.EndedBy) as? String }
        assertTrue("no EndedBy constants found; this check is reading nothing", declared.size >= 5)
        val missing = declared.filterNot { it in OutageLog.EndedBy.ALL }
        assertEquals(
            "these EndedBy values are declared but not in ALL, so an episode ending that way " +
                "decodes as \"unknown\" and the ending is lost: $missing",
            emptyList<String>(),
            missing,
        )
    }

    /**
     * The watcher closing its own episode is the honest ending, and it has to survive the round
     * trip like any other -- it is the one the next release's whole comparison rests on.
     */
    @Test fun theWatcherCanEndItsOwnOutage() {
        val e = OutageLog.Episode(
            startedAt = now, durationMs = 4 * minute, detectedAfterMs = 2 * minute,
            aliveButDeaf = false, precededBy = OutageLog.Preceded.NOTHING, rebooted = false,
            versionCode = 154, detectedBy = OutageLog.DetectedBy.UNBOUND,
            endedBy = OutageLog.EndedBy.REBOUND,
        )
        assertEquals(OutageLog.EndedBy.REBOUND, OutageLog.decode(OutageLog.encode(e))?.endedBy)
        assertTrue(e.render().contains("backBy=rebound"))
        val deaf = e.copy(aliveButDeaf = true, endedBy = OutageLog.EndedBy.HEARTBEAT)
        assertEquals(OutageLog.EndedBy.HEARTBEAT, OutageLog.decode(OutageLog.encode(deaf))?.endedBy)
        assertTrue(deaf.render().contains("backBy=heard-again"))
    }
    /**
     * **Only the endings that ARE the moment blocking returned may count as measured.**
     *
     * `SELF_TIMED` decides what goes into the "timed by the blocker itself" share of the total he
     * reads. Letting a poller-driven ending in would put its notice lag back into the number that
     * exists to be free of it — the same mistake as before, with a new name.
     */
    @Test
    fun `self-timed endings are exactly the two the watcher records itself`() {
        assertEquals(
            setOf(OutageLog.EndedBy.REBOUND, OutageLog.EndedBy.HEARTBEAT),
            OutageLog.EndedBy.SELF_TIMED,
        )
        // And every one of them has to be a real, decodable ending, not a string nothing produces.
        assertTrue(OutageLog.EndedBy.SELF_TIMED.all { it in OutageLog.EndedBy.ALL })
        listOf(
            OutageLog.EndedBy.BACKGROUND, OutageLog.EndedBy.APP_OPENED,
            OutageLog.EndedBy.BOOT, OutageLog.EndedBy.GLANCED, OutageLog.EndedBy.UNKNOWN,
        ).forEach {
            assertFalse("$it went looking, so its duration is a ceiling, not a measurement",
                it in OutageLog.EndedBy.SELF_TIMED)
        }
    }
}
