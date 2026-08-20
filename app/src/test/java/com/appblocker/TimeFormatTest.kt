package com.appblocker

import com.appblocker.ui.fmtCountdown
import com.appblocker.ui.fmtDuration
import com.appblocker.ui.fmtHoursMinutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared time formatters had no tests at all, and the one property that mattered most was the
 * one nobody had stated: **a countdown has an upper bound.**
 *
 * `fmtCountdown` stopped at `H:MM:SS`, while the duration wheel offers up to 30 days. So a 24-day
 * session put `35507:29` in the header pill — eight glyphs of minutes in a chip the width of a
 * word, wrapped onto two lines and spilling out of its own rounded shape. The owner reported it as
 * "very ugly" (20 Aug 2026). The same call renders the Strict screen's headline at 56sp, where it
 * would have read `591:47:29`.
 *
 * These pin the three bands, their boundaries, and the width bound itself — the last of which is
 * the only one that would have caught the bug before it was drawn.
 */
class TimeFormatTest {

    private fun mins(m: Long) = m * 60_000L
    private fun hours(h: Long) = h * 3_600_000L
    private fun days(d: Long) = d * 86_400_000L

    // ---- under an hour: seconds matter, this is a Pomodoro ------------------------------

    @Test fun underAnHourReadsMinutesAndSeconds() {
        assertEquals("4:12", fmtCountdown(mins(4) + 12_000))
        assertEquals("59:59", fmtCountdown(mins(59) + 59_000))
    }

    /** Strict's big timer pads so it doesn't change width as it ticks. */
    @Test fun padMinutesKeepsTheWidthSteady() {
        assertEquals("04:12", fmtCountdown(mins(4) + 12_000, padMinutes = true))
        assertEquals("00:09", fmtCountdown(9_000, padMinutes = true))
    }

    @Test fun zeroAndNegativeReadAsNothingLeft() {
        assertEquals("0:00", fmtCountdown(0))
        assertEquals("0:00", fmtCountdown(-5_000))
        assertEquals("00:00", fmtCountdown(-5_000, padMinutes = true))
    }

    // ---- an hour and up: hours, minutes, seconds ----------------------------------------

    @Test fun anHourStartsTheHoursBand() {
        assertEquals("1:00:00", fmtCountdown(hours(1)))
        assertEquals("6:12:04", fmtCountdown(hours(6) + mins(12) + 4_000))
    }

    /** The last second before the day band — padMinutes must not change the shape here. */
    @Test fun theHoursBandRunsRightUpToADay() {
        assertEquals("23:59:59", fmtCountdown(days(1) - 1_000))
        assertEquals("23:59:59", fmtCountdown(days(1) - 1_000, padMinutes = true))
    }

    // ---- a day and up: the band that did not exist ---------------------------------------

    @Test fun aDayStartsTheDaysBand() {
        assertEquals("1d 0h", fmtCountdown(days(1)))
        assertEquals("1d 1h", fmtCountdown(days(1) + hours(1) + mins(59)))
    }

    /** The session the owner was actually looking at: 35507 minutes, once rendered `35507:29`. */
    @Test fun theReportedSessionReadsAsDaysAndHours() {
        assertEquals("24d 15h", fmtCountdown(mins(35507) + 29_000))
    }

    @Test fun theWheelsLongestSessionStillReadsCleanly() {
        assertEquals("30d 0h", fmtCountdown(days(30)))
        assertEquals("29d 23h", fmtCountdown(days(30) - 1_000))
    }

    /** Seconds are dropped past a day on purpose, so the string stops changing every tick. */
    @Test fun theDaysBandIgnoresSeconds() {
        assertEquals(fmtCountdown(days(3) + hours(2)), fmtCountdown(days(3) + hours(2) + 59_000))
    }

    // ---- the property the pill and the 56sp headline both depend on ----------------------

    /**
     * **A countdown must fit.** Every value the duration wheel can produce (0 to 30 days), sampled
     * a minute at a time, must format to at most eight characters. This is the assertion that
     * fails on the old code: the hours band ran unbounded, so this same session formatted to
     * `591:47:29` (nine) and the wheel's 30-day maximum to `1200:00:59` (ten).
     */
    @Test fun noCountdownIsEverWiderThanEightCharacters() {
        var m = 0L
        while (m <= 30 * 24 * 60L) {
            for (pad in listOf(false, true)) {
                val out = fmtCountdown(mins(m) + 59_000, padMinutes = pad)
                assertTrue("$m min -> \"$out\" (${out.length} chars)", out.length <= 8)
            }
            m++
        }
    }

    // ---- the neighbours, so one definition can't drift from another ----------------------

    @Test fun coarseWaitsReadAsHoursAndMinutes() {
        assertEquals("35m", fmtHoursMinutes(mins(35)))
        assertEquals("23h 40m", fmtHoursMinutes(hours(23) + mins(40)))
    }

    /** Rounded up so a wait with seconds left never reads as over. */
    @Test fun aCoarseWaitNeverReadsZero() {
        assertEquals("1m", fmtHoursMinutes(1_000))
    }

    @Test fun wholeMinuteDurationsSpellTheirUnits() {
        assertEquals("25 min", fmtDuration(25))
        assertEquals("1 hr", fmtDuration(60))
        assertEquals("1 hr 30 min", fmtDuration(90))
    }
}
