package com.appblocker

import com.appblocker.service.UsageTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How today's screen time is counted.
 *
 * The owner saw **five hours of screen time on a day he had not been awake five hours**, while the
 * hour-by-hour chart beside it looked right. The two were computed from different sources — the
 * chart from Android's event stream, the total from its pre-aggregated daily buckets, which return
 * whole buckets overlapping the range and so can carry yesterday's time into today. Everything now
 * comes from the events, and this is the piece of that path a test can reach: the rest needs a
 * live UsageStatsManager.
 *
 * The number matters beyond the screen it is printed on — it decides whether a screen-time **goal**
 * was hit, it is what the **coach** is told about the day, and (through the range variant) it feeds
 * the watchdog's "blocking has stalled" threshold.
 */
class ScreenTimeTest {

    private fun iv(from: Long, to: Long) = longArrayOf(from, to)
    private fun totalOf(intervals: List<LongArray>) =
        UsageTracker.mergeIntervals(intervals).sumOf { it[1] - it[0] }

    private val minute = 60_000L
    private val hour = 60 * minute

    @Test
    fun `two apps overlapping for an hour is one hour, not two`() {
        // The double-count, in one line. Every app switch produces a small overlap — one app's
        // "paused" lands after the next app's "resumed" — so unmerged addition inflates all day,
        // and an hour of the chart could hold more than sixty minutes.
        val total = totalOf(listOf(iv(0, hour), iv(0, hour)))
        assertEquals(hour, total)
    }

    @Test
    fun `a partial overlap counts the union, not the sum`() {
        // 0–60 and 30–90 is ninety minutes of phone use, not a hundred and fifty.
        val total = totalOf(listOf(iv(0, 60 * minute), iv(30 * minute, 90 * minute)))
        assertEquals(90 * minute, total)
    }

    @Test
    fun `an interval swallowed by another adds nothing`() {
        assertEquals(hour, totalOf(listOf(iv(0, hour), iv(10 * minute, 20 * minute))))
    }

    @Test
    fun `separate stretches stay separate`() {
        // The merge must not become "first to last", or every day would read as one long session.
        val merged = UsageTracker.mergeIntervals(listOf(iv(0, hour), iv(5 * hour, 6 * hour)))
        assertEquals(2, merged.size)
        assertEquals(2 * hour, merged.sumOf { it[1] - it[0] })
    }

    @Test
    fun `touching stretches join`() {
        // "Ended at 10:00, started at 10:00" is one stretch of use, not two — this is what makes
        // "longest continuous use" believable across an app switch.
        val merged = UsageTracker.mergeIntervals(listOf(iv(0, hour), iv(hour, 2 * hour)))
        assertEquals(1, merged.size)
        assertEquals(2 * hour, merged[0][1] - merged[0][0])
    }

    @Test
    fun `order of arrival does not matter`() {
        // Events come back per app, so the list is not sorted by time.
        val shuffled = listOf(iv(5 * hour, 6 * hour), iv(0, hour), iv(30 * minute, 2 * hour))
        assertEquals(3 * hour, totalOf(shuffled))
    }

    @Test
    fun `nothing in, nothing out`() {
        assertEquals(emptyList<LongArray>(), UsageTracker.mergeIntervals(emptyList()))
    }

    @Test
    fun `screen time can never exceed the day so far`() {
        // The property the owner checked by hand and the app failed: you cannot have used the
        // phone for longer than the day has lasted. With merging it is true by construction.
        val dayLength = 7 * hour
        val busy = (0 until 40).map { i ->
            val start = (i * 10 * minute) % dayLength
            iv(start, minOf(start + 25 * minute, dayLength))
        }
        assertTrue(totalOf(busy) <= dayLength)
    }

    @Test
    fun `no hour can hold more than sixty minutes`() {
        // The chart's bars are the merged timeline poured through addInterval, so an impossible
        // bar is now unrepresentable rather than merely unlikely.
        val dayStart = 0L
        val buckets = LongArray(24)
        val overlapping = listOf(iv(0, hour), iv(0, hour), iv(30 * minute, 90 * minute))
        UsageTracker.mergeIntervals(overlapping)
            .forEach { UsageTracker.addInterval(buckets, it[0], it[1], dayStart) }
        buckets.forEach { assertTrue("an hour held ${it / minute} minutes", it <= hour) }
    }
}
