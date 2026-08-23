package com.appblocker

import com.appblocker.data.CleanStreak
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clean counter's arithmetic.
 *
 * **Why this is worth a test file of its own.** This is the one number in the app that cannot be
 * recomputed from anything else. Screen time, blocked opens and unlocks all come from Android and
 * would survive being wrong for a day; a streak that reads 0 because a timezone changed, or a
 * "longest ever" that quietly banks a run that never happened, is destroyed information — and the
 * person reading it is not in a state to argue with a number.
 *
 * So every case below is a way the count has a wrong answer *without crashing*, which is the only
 * failure mode that matters here.
 */
class CleanStreakTest {

    private val day = 86_400_000L
    private val hour = 3_600_000L

    // ─────────────────────────────────────────────────────── how long has it been

    @Test
    fun `a count that was never started is zero, not fifty-six years`() {
        // 0 is what an unset preference reads as, and `now - 0` is the whole Unix epoch. The
        // screen would print it without blinking.
        assertEquals(0L, CleanStreak.elapsed(startedAt = 0L, now = 1_700_000_000_000L))
    }

    @Test
    fun `a clock that moved backwards reads zero, never a negative day`() {
        // A timezone change, the DST hour, or a phone correcting itself after a flat battery.
        assertEquals(0L, CleanStreak.elapsed(startedAt = 5_000L, now = 4_000L))
    }

    @Test
    fun `an ordinary run is the plain difference`() {
        assertEquals(3 * day, CleanStreak.elapsed(startedAt = 1_000_000L, now = 1_000_000L + 3 * day))
    }

    // ─────────────────────────────────────────────────────── days, hours, minutes, seconds

    @Test
    fun `nothing yet is four zeroes`() {
        assertEquals(CleanStreak.Elapsed(0, 0, 0, 0), CleanStreak.breakdown(0L))
    }

    @Test
    fun `a part-second still shows zero seconds rather than rounding up to one`() {
        assertEquals(CleanStreak.Elapsed(0, 0, 0, 0), CleanStreak.breakdown(999L))
        assertEquals(CleanStreak.Elapsed(0, 0, 0, 59), CleanStreak.breakdown(59_999L))
    }

    /** The boundary the display gets wrong if the units are computed independently: at exactly a
     *  day it must read 1d 0h 0m 0s, not 1d 24h. */
    @Test
    fun `exactly one day rolls the hours back to zero`() {
        assertEquals(CleanStreak.Elapsed(1, 0, 0, 0), CleanStreak.breakdown(day))
        assertEquals(CleanStreak.Elapsed(1, 0, 0, 1), CleanStreak.breakdown(day + 1_000L))
    }

    @Test
    fun `a long run keeps every unit inside its own range`() {
        val ms = 47 * day + 13 * hour + 22 * 60_000L + 9_000L
        assertEquals(CleanStreak.Elapsed(47, 13, 22, 9), CleanStreak.breakdown(ms))
    }

    /** Days are unbounded on purpose — a year of it should read as a year, not wrap. */
    @Test
    fun `days do not wrap at a month or a year`() {
        assertEquals(400L, CleanStreak.breakdown(400 * day).days)
    }

    @Test
    fun `a negative duration cannot leak through as negative units`() {
        assertEquals(CleanStreak.Elapsed(0, 0, 0, 0), CleanStreak.breakdown(-5_000L))
    }

    // ─────────────────────────────────────────────────────── the record

    @Test
    fun `the record keeps whichever run was longer`() {
        assertEquals(9 * day, CleanStreak.bankBest(best = 9 * day, finished = 2 * day))
        assertEquals(12 * day, CleanStreak.bankBest(best = 9 * day, finished = 12 * day))
    }

    /**
     * The first-ever "I relapsed" happens with no run behind it. Banking that would write a
     * record of zero and then claim it — the screen would say "your longest run so far: 0".
     */
    @Test
    fun `a run that never started banks nothing`() {
        assertEquals(0L, CleanStreak.bankBest(best = 0L, finished = 0L))
        assertEquals(4 * day, CleanStreak.bankBest(best = 4 * day, finished = -1L))
    }

    // ─────────────────────────────────────────────────────── picking a moment by hand

    @Test
    fun `a start cannot be set in the future`() {
        val now = 1_700_000_000_000L
        assertEquals(now, CleanStreak.clampToNow(now + 10 * day, now))
    }

    @Test
    fun `a start before the epoch is held at zero`() {
        assertEquals(0L, CleanStreak.clampToNow(-1L, now = 1_000L))
    }

    @Test
    fun `a relapse cannot be dated before the run it ends`() {
        val start = 1_700_000_000_000L
        val now = start + 10 * day
        // Back-dating past the start would bank a negative run and start the next count already
        // running — the count would come back reading three days on the day it was reset.
        assertEquals(start, CleanStreak.clampRelapse(at = start - 2 * day, now = now, startedAt = start))
    }

    @Test
    fun `a relapse cannot be dated in the future`() {
        val start = 1_700_000_000_000L
        val now = start + 10 * day
        assertEquals(now, CleanStreak.clampRelapse(at = now + hour, now = now, startedAt = start))
    }

    @Test
    fun `a back-dated relapse inside the run is kept exactly`() {
        val start = 1_700_000_000_000L
        val now = start + 10 * day
        val lastNight = now - 9 * hour
        assertEquals(lastNight, CleanStreak.clampRelapse(lastNight, now, start))
    }

    /**
     * The impossible combination, which a wound-back device clock produces for real: a run that
     * began "after" the current moment. It must not throw — `coerceIn` does, when its own bounds
     * cross — and the answer must still be a real moment.
     */
    @Test
    fun `a start later than now does not blow up the clamp`() {
        val now = 1_700_000_000_000L
        assertEquals(now, CleanStreak.clampRelapse(at = now, now = now, startedAt = now + 5 * day))
    }

    // ─────────────────────────────────────────────────────── taking it back

    @Test
    fun `nothing to undo when nothing was reset`() {
        assertFalse(CleanStreak.canUndo(undoAt = 0L, now = 1_700_000_000_000L))
    }

    @Test
    fun `a reset can be taken back the moment it happens and for a day after`() {
        val at = 1_700_000_000_000L
        assertTrue(CleanStreak.canUndo(at, at))
        assertTrue(CleanStreak.canUndo(at, at + 23 * hour))
        assertTrue(CleanStreak.canUndo(at, at + CleanStreak.UNDO_WINDOW_MS))
    }

    @Test
    fun `the offer goes away after a day rather than sitting there for a week`() {
        val at = 1_700_000_000_000L
        assertFalse(CleanStreak.canUndo(at, at + CleanStreak.UNDO_WINDOW_MS + 1_000L))
    }

    /** A backwards clock must not resurrect an expired undo, or hide a live one behind a
     *  negative age. */
    @Test
    fun `a clock behind the reset offers nothing`() {
        val at = 1_700_000_000_000L
        assertFalse(CleanStreak.canUndo(at, at - hour))
    }
}
