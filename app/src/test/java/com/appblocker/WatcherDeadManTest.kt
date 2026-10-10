package com.appblocker

import com.appblocker.service.SERVICE_BIND_GRACE_MS
import com.appblocker.service.WatcherDeadMan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The dead-man alarm** (invariants 82, 85, 88). Invariant 85 made it seconds while the screen was
 * on; on 10 Oct 2026 he chose the battery instead (invariant 88), so the alarm is two minutes again,
 * pushed by the heartbeat alone. What 85 left behind costs nothing and stays pinned here: the shorter
 * wait only where the silent repair can act on it and no install is about to bind the watcher.
 */
class WatcherDeadManTest {

    /** Two minutes, screen on or off: two heartbeats, so one late tick is forgiven (invariant 88). */
    @Test fun theAlarmIsTwoMinutesAway() = assertEquals(2 * 60_000L, WatcherDeadMan.DELAY_MS)

    /**
     * The five seconds only where they buy something: the silent repair is there to act on the
     * answer, and no install is about to bind the watcher. Anywhere else the full grace, as before —
     * a faster alert is not worth a false one.
     */
    @Test fun theShortWaitNeedsTheRepairAndNoFreshInstall() {
        assertEquals(
            WatcherDeadMan.KILLED_BIND_WAIT_MS,
            WatcherDeadMan.bindWaitMs(repairPermitted = true, justInstalled = false),
        )
        assertEquals(SERVICE_BIND_GRACE_MS, WatcherDeadMan.bindWaitMs(repairPermitted = true, justInstalled = true))
        assertEquals(SERVICE_BIND_GRACE_MS, WatcherDeadMan.bindWaitMs(repairPermitted = false, justInstalled = false))
        assertEquals(SERVICE_BIND_GRACE_MS, WatcherDeadMan.bindWaitMs(repairPermitted = false, justInstalled = true))
    }

    /**
     * Shorter than the grace it replaces, or it replaces nothing — and still longer than a phone that
     * restarts a crashed watcher takes to bind it into the process our alarm starts (about a second
     * on the emulator), or the repair would run over Android's own rebind and be credited with it.
     */
    @Test fun theShortWaitStillOutlastsAStockRebind() {
        assertTrue(WatcherDeadMan.KILLED_BIND_WAIT_MS < SERVICE_BIND_GRACE_MS)
        assertTrue(WatcherDeadMan.KILLED_BIND_WAIT_MS >= 3_000L)
    }
}
