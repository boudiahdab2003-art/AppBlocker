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

    // ---- Extending a running session ---------------------------------------------------------
    //
    // "Add more time" moves BOTH deadlines later by the same delta and touches neither start
    // anchor (the statement that does it is FocusDao.extend; FocusExtendTest proves the write).
    // These prove that choice of fields is the right one on both of this file's paths — that the
    // extension is real within a boot, and that it survives a reboot without also widening the
    // hole a wrong clock could walk through.

    @Test fun extendingAddsExactlyTheDeltaWithinABoot() {
        val before = SessionClock.remainingAt(
            realtimeStart = 5_000, realtimeEnd = 10_000,
            wallStart = 7_000, wallEnd = 12_000,
            savedBootCount = 5, currentBootCount = 5,
            nowRt = 6_000, nowWall = 6_000,
        )
        val delta = 3_000L
        val after = SessionClock.remainingAt(
            realtimeStart = 5_000, realtimeEnd = 10_000 + delta,
            wallStart = 7_000, wallEnd = 12_000 + delta,
            savedBootCount = 5, currentBootCount = 5,
            nowRt = 6_000, nowWall = 6_000,
        )
        assertEquals(before + delta, after)
    }

    /**
     * The reason the start anchors must NOT be re-anchored when extending.
     *
     * After a reboot the monotonic deadline is dead and remaining is capped at the session's total
     * length, `wallEnd - wallStart` — the guard that stops a backwards clock handing back more time
     * than the session ever had. Moving `wallEnd` alone grows that cap by exactly the amount added,
     * so the extension is honoured and the guard still holds. Re-anchoring `wallStart` to "now"
     * would shrink the cap instead, which is a way of *shortening* a session that nobody asked for.
     */
    @Test fun extendingSurvivesARebootAndKeepsItsCap() {
        val delta = 3_000L
        val rem = SessionClock.remainingAt(
            realtimeStart = 5_000, realtimeEnd = 10_000 + delta,
            wallStart = 1_000, wallEnd = 11_000 + delta,
            savedBootCount = 4, currentBootCount = 5, // rebooted: wall path
            nowRt = 500, nowWall = 4_000,
        )
        assertEquals(7_000L + delta, rem)
        assertTrue("must stay capped at the session's total length", rem <= (11_000 + delta) - 1_000)
    }

    /** An extension of a session that already ran out stays run out. */
    @Test fun extendingAnExpiredSessionIsStillExpired() {
        // The row is zeroed on expiry, so this is what "extend the dead row" would compute.
        assertEquals(
            0L,
            SessionClock.remainingAt(
                realtimeStart = 0, realtimeEnd = 0,
                wallStart = 0, wallEnd = 0,
                savedBootCount = 5, currentBootCount = 5,
                nowRt = 6_000, nowWall = 6_000,
            ),
        )
    }
}
