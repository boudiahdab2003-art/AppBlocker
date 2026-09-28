package com.appblocker

import com.appblocker.service.SERVICE_BIND_GRACE_MS
import com.appblocker.service.WatcherDeadMan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Invariant 85: a watcher killed in use is noticed in seconds** — his choice of 28 Sep 2026, "make
 * it notice faster", a little battery for it. These pin the numbers the choice turns on: seconds only
 * while the screen is on, an alarm Android will not defer, and the shorter wait only where the silent
 * repair can act on it and no install is about to bind the watcher.
 */
class WatcherDeadManTest {

    /** Android does not defer an inexact alarm due in less than this (`MIN_FUZZABLE_INTERVAL`). */
    private val notDeferredUnder = 10_000L

    /** …and never sets one closer than this (`MIN_FUTURITY`): a shorter lead buys nothing. */
    private val closestAllowed = 5_000L

    @Test fun theAlarmIsSecondsAwayOnlyWhileTheScreenIsOn() {
        assertEquals(WatcherDeadMan.FAST_DELAY_MS, WatcherDeadMan.delayMs(screenOn = true))
        assertEquals(WatcherDeadMan.DELAY_MS, WatcherDeadMan.delayMs(screenOn = false))
        // Unreadable is not "on": the behaviour before invariant 85, never a guess.
        assertEquals(WatcherDeadMan.DELAY_MS, WatcherDeadMan.delayMs(screenOn = null))
    }

    /**
     * An inexact alarm may go off up to 75% of its lead late — a 30 s one went off at 52 s on the
     * Android 16 emulator — except when it is due in under ten seconds. Ten or more, and "seconds"
     * quietly becomes a quarter of a minute more.
     */
    @Test fun theScreenOnAlarmIsOneAndroidDeliversOnTime() {
        assertTrue(WatcherDeadMan.FAST_DELAY_MS < notDeferredUnder)
        assertTrue(WatcherDeadMan.FAST_DELAY_MS >= closestAllowed)
    }

    /** A push a few seconds late must not set the alarm off — harmless if it did, but not free. */
    @Test fun aLatePushIsForgiven() {
        assertTrue(WatcherDeadMan.FAST_PUSH_MS < WatcherDeadMan.FAST_DELAY_MS)
        assertTrue(WatcherDeadMan.FAST_DELAY_MS - WatcherDeadMan.FAST_PUSH_MS >= 3_000L)
    }

    /** The screen-off side is what it was: the battery there is his "keep it as it is". */
    @Test fun theScreenOffDelayIsUnchanged() = assertEquals(2 * 60_000L, WatcherDeadMan.DELAY_MS)

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
