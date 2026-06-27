package com.appblocker

import com.appblocker.service.timeWindowContains
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeWindowTest {
    // Normal window 09:00–17:00 (540–1020).
    @Test fun insideNormal() = assertTrue(timeWindowContains(600, 540, 1020))   // 10:00
    @Test fun beforeNormal() = assertFalse(timeWindowContains(480, 540, 1020))  // 08:00
    @Test fun startIsInclusive() = assertTrue(timeWindowContains(540, 540, 1020))
    @Test fun endIsExclusive() = assertFalse(timeWindowContains(1020, 540, 1020))

    // Wrap past midnight 22:00–06:00 (1320–360).
    @Test fun lateNightInside() = assertTrue(timeWindowContains(1380, 1320, 360))  // 23:00
    @Test fun earlyMorningInside() = assertTrue(timeWindowContains(120, 1320, 360)) // 02:00
    @Test fun middayOutside() = assertFalse(timeWindowContains(720, 1320, 360))     // 12:00
    @Test fun wrapEndIsExclusive() = assertFalse(timeWindowContains(360, 1320, 360))// 06:00
}
