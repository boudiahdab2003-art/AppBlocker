package com.appblocker

import com.appblocker.data.dayGap
import com.appblocker.data.prevDayStamp
import com.appblocker.data.stampDaysAgo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Day-stamps are `yyyy * 1000 + dayOfYear`, which is fine to compare for equality but is **not**
 * a linear day count. Five stores subtracted two of them anyway — to prune old data, and in
 * MoodStore's case to read history — so once the year rolled over the arithmetic silently meant
 * nothing. These state the year-boundary behaviour so it can't quietly come back.
 */
class DayStampTest {

    // 2026 is not a leap year, so its last day-of-year is 365; 2024 was, so 366.
    private val dec31_2026 = 2026 * 1000 + 365
    private val jan01_2027 = 2027 * 1000 + 1
    private val dec31_2024 = 2024 * 1000 + 366
    private val jan01_2025 = 2025 * 1000 + 1

    @Test
    fun `consecutive days are one apart inside a year`() {
        assertEquals(1, dayGap(2026 * 1000 + 200, 2026 * 1000 + 199))
        assertEquals(35, dayGap(2026 * 1000 + 200, 2026 * 1000 + 165))
    }

    @Test
    fun `new year's day is one day after new year's eve`() {
        // The raw stamps differ by 636 — this is the whole point of dayGap.
        assertEquals(636, jan01_2027 - dec31_2026)
        assertEquals(1, dayGap(jan01_2027, dec31_2026))
    }

    @Test
    fun `a leap year's extra day is counted`() {
        assertEquals(1, dayGap(jan01_2025, dec31_2024))
        assertEquals(366, dayGap(jan01_2025, 2024 * 1000 + 1))
    }

    @Test
    fun `data from late December is not stale in early January`() {
        // The bug: `today - stamp > 35` was true for everything, so the first weeks of January
        // pruned every stored day. 20 Dec is 12 days before 1 Jan, well inside a 35-day window.
        val dec20 = 2026 * 1000 + 354
        assertEquals(12, dayGap(jan01_2027, dec20))
        assertEquals(true, dayGap(jan01_2027, dec20) <= 35)
    }

    @Test
    fun `stamps really are stale once they are old enough`() {
        assertEquals(true, dayGap(jan01_2027, 2026 * 1000 + 300) > 35)
    }

    @Test
    fun `stepping back lands on real days across the year boundary`() {
        assertEquals(dec31_2026, stampDaysAgo(jan01_2027, 1))
        assertEquals(2026 * 1000 + 364, stampDaysAgo(jan01_2027, 2))
        assertEquals(dec31_2024, stampDaysAgo(jan01_2025, 1))
    }

    @Test
    fun `stepping back zero days is today`() {
        assertEquals(jan01_2027, stampDaysAgo(jan01_2027, 0))
        assertEquals(jan01_2027, stampDaysAgo(jan01_2027, -3))
    }

    @Test
    fun `stepping back agrees with prevDayStamp and with dayGap`() {
        val start = 2027 * 1000 + 3
        var walked = start
        repeat(7) { walked = prevDayStamp(walked) }
        assertEquals(walked, stampDaysAgo(start, 7))
        assertEquals(7, dayGap(start, walked))
    }
}
