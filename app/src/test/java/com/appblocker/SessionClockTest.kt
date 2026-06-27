package com.appblocker

import com.appblocker.data.SessionClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs with testOptions.unitTests.isReturnDefaultValues = true, so the un-mocked
 * SystemClock.elapsedRealtime() returns 0. We exploit that fixed "now" to test the branch logic
 * deterministically. System.currentTimeMillis() is the real JVM clock.
 */
class SessionClockTest {

    @Test fun usesRealtimeBranchWhenAnchored() {
        // realtimeEnd > 0 and nowRt(0) >= realtimeStart(0) -> realtime branch -> realtimeEnd - 0.
        assertEquals(10_000L, SessionClock.remaining(realtimeStart = 0, realtimeEnd = 10_000, wallEnd = 0))
    }

    @Test fun wallFallbackWhenNoRealtimeAnchor() {
        // realtimeEnd == 0 -> wall branch. Future wall end -> positive remaining.
        val rem = SessionClock.remaining(0, 0, System.currentTimeMillis() + 100_000)
        assertTrue("expected ~100s remaining, got $rem", rem in 90_000..100_000)
    }

    @Test fun expiredWallIsZero() {
        assertEquals(0L, SessionClock.remaining(0, 0, System.currentTimeMillis() - 100_000))
    }

    @Test fun rebootDetectedFallsBackToWall() {
        // nowRt(0) < realtimeStart(5000) => reboot detected => wall branch; wall end in past => 0.
        assertEquals(0L, SessionClock.remaining(realtimeStart = 5_000, realtimeEnd = 10_000, wallEnd = 0))
    }

    @Test fun remainingNeverNegative() {
        assertEquals(0L, SessionClock.remaining(0, 0, 0))
    }

    @Test fun elapsedWallBranchNeverNegative() {
        // realtimeStart == 0 -> wall branch. wallStart in the future would be negative -> clamped 0.
        assertEquals(0L, SessionClock.elapsed(realtimeStart = 0, wallStart = System.currentTimeMillis() + 100_000))
    }
}
