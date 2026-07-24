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
        assertEquals(10_000L, SessionClock.remaining(realtimeStart = 0, realtimeEnd = 10_000, wallStart = 0, wallEnd = 0, savedBootCount = 1, currentBootCount = 1))
    }

    @Test fun wallFallbackWhenNoRealtimeAnchor() {
        // realtimeEnd == 0 -> wall branch. Future wall end -> positive remaining.
        val rem = SessionClock.remaining(0, 0, 0, System.currentTimeMillis() + 100_000, 1, 1)
        assertTrue("expected ~100s remaining, got $rem", rem in 90_000..100_000)
    }

    @Test fun expiredWallIsZero() {
        assertEquals(0L, SessionClock.remaining(0, 0, 0, System.currentTimeMillis() - 100_000, 1, 1))
    }

    @Test fun rebootDetectedFallsBackToWall() {
        // nowRt(0) < realtimeStart(5000) => reboot detected => wall branch; wall end in past => 0.
        assertEquals(0L, SessionClock.remaining(realtimeStart = 5_000, realtimeEnd = 10_000, wallStart = 0, wallEnd = 0, savedBootCount = 1, currentBootCount = 1))
    }

    @Test fun remainingNeverNegative() {
        assertEquals(0L, SessionClock.remaining(0, 0, 0, 0, 1, 1))
    }

    @Test fun clockBeforeSessionStartIsInactive() {
        // Reboot branch (nowRt 0 < realtimeStart) + wall clock reading BEFORE the session
        // started = impossible clock -> the session must NOT resurrect.
        val now = System.currentTimeMillis()
        assertEquals(
            0L,
            SessionClock.remaining(
                realtimeStart = 5_000, realtimeEnd = 10_000,
                wallStart = now + 50_000, wallEnd = now + 110_000,
                savedBootCount = 1, currentBootCount = 1,
            ),
        )
    }

    @Test fun rebootMidSessionStaysLocked() {
        // Reboot mid-session: clock within [wallStart, wallEnd] -> remaining stays positive.
        val now = System.currentTimeMillis()
        val rem = SessionClock.remaining(
            realtimeStart = 5_000, realtimeEnd = 10_000,
            wallStart = now - 60_000, wallEnd = now + 60_000,
            savedBootCount = 1, currentBootCount = 1,
        )
        assertTrue("expected ~60s remaining, got $rem", rem in 50_000..60_000)
    }

    @Test fun remainingCappedAtOriginalDuration() {
        // Even if the clock somehow reads before wallStart's raw math suggests more time,
        // remaining can never exceed the session's original duration (wallEnd - wallStart).
        val now = System.currentTimeMillis()
        val rem = SessionClock.remaining(
            realtimeStart = 5_000, realtimeEnd = 10_000,
            wallStart = now - 1_000, wallEnd = now + 9_000,
            savedBootCount = 1, currentBootCount = 1,
        )
        assertTrue("expected <= 10s, got $rem", rem <= 10_000)
    }

    @Test fun elapsedWallBranchNeverNegative() {
        // realtimeStart == 0 -> wall branch. wallStart in the future would be negative -> clamped 0.
        assertEquals(0L, SessionClock.elapsed(realtimeStart = 0, wallStart = System.currentTimeMillis() + 100_000, savedBootCount = 1, currentBootCount = 1))
    }

    @Test fun mismatchedBootCountUsesWallClockEvenWhenUptimeLooksValid() {
        assertEquals(
            0L,
            SessionClock.remainingAt(
                realtimeStart = 5_000, realtimeEnd = 10_000,
                wallStart = 100, wallEnd = 200,
                savedBootCount = 4, currentBootCount = 5,
                nowRt = 6_000, nowWall = 250,
            ),
        )
    }

    @Test fun matchingBootCountKeepsMonotonicDeadline() {
        assertEquals(
            4_000L,
            SessionClock.remainingAt(
                realtimeStart = 5_000, realtimeEnd = 10_000,
                wallStart = 7_000, wallEnd = 12_000,
                savedBootCount = 5, currentBootCount = 5,
                nowRt = 6_000, nowWall = 6_000,
            ),
        )
    }

    @Test fun unknownLegacyBootCountUsesWallClock() {
        assertEquals(
            0L,
            SessionClock.remainingAt(
                realtimeStart = 5_000, realtimeEnd = 10_000,
                wallStart = 100, wallEnd = 200,
                savedBootCount = -1, currentBootCount = 5,
                nowRt = 6_000, nowWall = 250,
            ),
        )
    }
}
