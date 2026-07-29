package com.appblocker

import com.appblocker.data.PinStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the PIN is asked for again.
 *
 * It used to be asked **once**: the gate held "unlocked" for the life of the composition, which
 * survives backgrounding and process-death restore, so on a phone where AppBlocker sits in recents
 * the lock opened once and stayed open for days. The setting says "lock your settings so blocks
 * can't be removed on a whim" — and the whim arrives hours later, by which time it was already open.
 *
 * The tension is real in both directions, which is why the rule is a function and not a constant:
 * the app sends the user out to system screens constantly (accessibility, device admin, battery),
 * and re-locking on every one of those would teach him to switch the PIN off.
 */
class PinRelockTest {

    @Test
    fun `a trip to Settings and back does not ask again`() {
        // Granting accessibility means leaving the app, hunting through a list, and coming back.
        // A PIN prompt at the end of that is how a protection gets turned off for good.
        assertFalse(PinStore.shouldRelock(pinSet = true, awayMs = 5_000L))
        assertFalse(PinStore.shouldRelock(pinSet = true, awayMs = 90_000L))
    }

    @Test
    fun `coming back later asks again`() {
        assertTrue(PinStore.shouldRelock(pinSet = true, awayMs = 10 * 60_000L))
        assertTrue(PinStore.shouldRelock(pinSet = true, awayMs = 8 * 60 * 60_000L))
    }

    @Test
    fun `no PIN set means nothing to ask for`() {
        assertFalse(PinStore.shouldRelock(pinSet = false, awayMs = Long.MAX_VALUE))
    }

    @Test
    fun `unreadable clocks re-lock rather than stay open`() {
        // A restore across a reboot restarts the monotonic clock, so the stored stamp can be in
        // the future. Asking for a PIN that wasn't needed costs seconds; skipping one that was
        // costs the thing the PIN protects.
        assertTrue(PinStore.shouldRelock(pinSet = true, awayMs = -1L))
        assertTrue(PinStore.shouldRelock(pinSet = true, awayMs = -8 * 60 * 60_000L))
    }

    @Test
    fun `the grace is long enough to be usable and short enough to matter`() {
        // Pinned so a later tweak can't quietly turn the lock back into a one-time question.
        assertTrue(PinStore.RELOCK_AFTER_MS >= 60_000L)
        assertTrue(PinStore.RELOCK_AFTER_MS <= 5 * 60_000L)
    }
}
