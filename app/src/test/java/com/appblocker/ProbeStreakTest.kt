package com.appblocker

import com.appblocker.data.ServiceHealth
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The streak behind the probe arm of `protectionState`.
 *
 * Small, and load-bearing out of proportion to its size: it is the counter that lets a bound
 * watcher be called dead in about fifteen minutes instead of two hours, so what resets it decides
 * whether that arm can ever fire at all.
 *
 * ⚠️ **The rule that is not stated here, because it cannot be:** *a successful nudge must never
 * clear the streak.* That is a fact about the caller — `heartbeatRunnable` — rather than about
 * this arithmetic, and it matters because the nudge fires on the same three-minute schedule as the
 * probe watching it. Clearing on a successful nudge would mean the streak could never reach two:
 * a threshold measured against a clock the act of measuring resets, which is invariant 30 and has
 * already cost this project one release. There is no honest regex for it, so it is a paragraph
 * here, a paragraph on [ServiceHealth.probeFailStreak], and the reason the heartbeat clears the
 * streak only when *silence breaks*.
 */
class ProbeStreakTest {

    /** Failures only mean something in a row. One is a moment; five is a condition. */
    @Test fun failuresAccumulate() {
        var streak = 0
        repeat(5) { streak = ServiceHealth.nextProbeStreak(streak, ok = false) }
        assertEquals(5, streak)
    }

    /**
     * A passing probe wipes the count outright rather than decrementing it.
     *
     * It is direct evidence the connection is alive *now*, and no number of earlier failures
     * survives that. Decaying instead would let a phone that fails four times an hour, forever,
     * drift up to the limit and report an outage that is not happening.
     */
    @Test fun anyPassClearsTheStreak() =
        assertEquals(0, ServiceHealth.nextProbeStreak(4, ok = true))

    /** Clearing an already-clear streak is still clear — the caller skips the write. */
    @Test fun clearingWhenNothingIsWrongChangesNothing() =
        assertEquals(0, ServiceHealth.nextProbeStreak(0, ok = true))
}
