package com.appblocker

import com.appblocker.data.SessionClock
import com.appblocker.data.StrictSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Strict Mode in the window before the database answers.**
 *
 * Rules, the Strict session, the blocked words and the schedules all arrive on one flow, so all
 * four are empty until its first emission \u2014 and only the rules had a fallback. Strict read as
 * *not running* for that window, on a phone whose service was revived 67 times in two days.
 */
class StrictSnapshotTest {

    private val active = StrictSnapshot.Session(
        realtimeStart = 1_000L, realtimeEnd = 3_600_000L,
        wallStart = 1_700_000_000_000L, wallEnd = 1_700_003_600_000L,
        bootCount = 7,
    )

    /** The leak itself: nothing loaded, live is zeroes, and a session really was running. */
    @Test fun beforeTheRulesLandTheSnapshotIsEnforced() =
        assertEquals(
            active,
            StrictSnapshot.sessionFor(loaded = false, live = StrictSnapshot.NONE, snapshot = active),
        )

    /** Once loaded the live value is the only truth, or an ended session would never lift. */
    @Test fun afterTheRulesLandAnEndedSessionIsNotResurrected() =
        assertEquals(
            StrictSnapshot.NONE,
            StrictSnapshot.sessionFor(loaded = true, live = StrictSnapshot.NONE, snapshot = active),
        )

    /** A live value that arrived early still wins over the snapshot. */
    @Test fun aLiveSessionBeatsTheSnapshot() {
        val live = active.copy(wallEnd = active.wallEnd + 60_000L)
        assertEquals(live, StrictSnapshot.sessionFor(false, live, active))
    }

    @Test fun noSessionAnywhereStaysNoSession() =
        assertEquals(
            StrictSnapshot.NONE,
            StrictSnapshot.sessionFor(false, StrictSnapshot.NONE, StrictSnapshot.NONE),
        )

    @Test fun zeroesAreNotASession() {
        assertTrue(StrictSnapshot.isSet(active))
        assertTrue(!StrictSnapshot.isSet(StrictSnapshot.NONE))
    }

    /**
     * **The snapshot cannot resurrect an expired session**, which is what makes enforcing it in
     * the dark safe. SessionClock is anchored to both clocks and the boot counter.
     */
    @Test fun anExpiredSnapshotEnforcesNothing() {
        val expired = StrictSnapshot.Session(
            realtimeStart = 0L, realtimeEnd = 1_000L,
            wallStart = 1_700_000_000_000L, wallEnd = 1_700_000_001_000L,
            bootCount = 7,
        )
        val s = StrictSnapshot.sessionFor(false, StrictSnapshot.NONE, expired)
        assertEquals(
            0L,
            SessionClock.remainingAt(
                s.realtimeStart, s.realtimeEnd, s.wallStart, s.wallEnd, s.bootCount,
                currentBootCount = 7,
                nowRt = 60_000L,
                nowWall = 1_700_000_060_000L,
            ).coerceAtLeast(0L),
        )
    }
}
