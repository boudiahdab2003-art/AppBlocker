package com.appblocker

import com.appblocker.service.UsageTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UsageTracker.addInterval] only — the part of the usage reading that is plain arithmetic.
 *
 * It used to spin forever once a year. A local day is 25 hours long on the DST fall-back day, so
 * an interval can sit past `dayStart + 24h`; the hour index was clamped into 0..23 while the hour's
 * end was computed from the clamped hour, which put that end *behind* the cursor. The step went
 * negative, then zero, and the loop never finished — hanging Insights and the coach on a pegged
 * core. The tests that matter here are the ones with a `timeout`: they assert termination.
 */
class UsageTrackerTest {

    private val hour = 3_600_000L
    private val dayStart = 1_700_000_000_000L

    // --- the ordinary cases ---

    @Test fun `an interval inside one hour lands in that hour`() {
        val b = LongArray(24)
        UsageTracker.addInterval(b, dayStart + 2 * hour, dayStart + 2 * hour + 600_000L, dayStart)
        assertEquals(600_000L, b[2])
        assertEquals(0L, b[3])
    }

    @Test fun `an interval spanning hours is split across them`() {
        val b = LongArray(24)
        // 01:30 to 03:15 → 30min in hour 1, a full hour in 2, 15min in 3.
        UsageTracker.addInterval(b, dayStart + hour + 1_800_000L, dayStart + 3 * hour + 900_000L, dayStart)
        assertEquals(1_800_000L, b[1])
        assertEquals(hour, b[2])
        assertEquals(900_000L, b[3])
    }

    @Test fun `time before the day start is not counted`() {
        val b = LongArray(24)
        UsageTracker.addInterval(b, dayStart - 5 * hour, dayStart + 600_000L, dayStart)
        assertEquals(600_000L, b[0])
        assertEquals(600_000L, b.sum()) // and nothing leaked elsewhere
    }

    @Test fun `an empty or backwards interval adds nothing`() {
        val b = LongArray(24)
        UsageTracker.addInterval(b, dayStart + hour, dayStart + hour, dayStart)
        UsageTracker.addInterval(b, dayStart + 5 * hour, dayStart + 2 * hour, dayStart)
        assertEquals(0L, b.sum())
    }

    // --- the 25-hour day: these assert the loop TERMINATES ---

    @Test(timeout = 2_000)
    fun `an interval starting past the 24th hour terminates`() {
        // The exact shape that used to hang: 24:12 to 24:30 on a DST fall-back day.
        val b = LongArray(24)
        UsageTracker.addInterval(b, dayStart + 24 * hour + 720_000L, dayStart + 24 * hour + 1_800_000L, dayStart)
        assertEquals(0L, b.sum()) // nowhere to put it, and nothing corrupted
    }

    @Test(timeout = 2_000)
    fun `an interval crossing the 24th hour keeps only what fits`() {
        val b = LongArray(24)
        // 23:30 to 24:30 → the first 30 minutes belong to hour 23; the rest is dropped.
        UsageTracker.addInterval(b, dayStart + 23 * hour + 1_800_000L, dayStart + 24 * hour + 1_800_000L, dayStart)
        assertEquals(1_800_000L, b[23])
        assertEquals(1_800_000L, b.sum())
    }

    @Test(timeout = 2_000)
    fun `a bucket is never given a negative amount`() {
        val b = LongArray(24)
        UsageTracker.addInterval(b, dayStart + 24 * hour + hour / 2, dayStart + 25 * hour, dayStart)
        assertTrue("no bucket may go negative: ${b.toList()}", b.all { it >= 0L })
    }

    @Test(timeout = 2_000)
    fun `a whole 25-hour day of continuous use fills 24 hours and no more`() {
        val b = LongArray(24)
        UsageTracker.addInterval(b, dayStart, dayStart + 25 * hour, dayStart)
        assertEquals(24 * hour, b.sum())
        assertTrue(b.all { it == hour })
    }
}
