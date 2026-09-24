package com.appblocker

import com.appblocker.data.SpaceReturn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariant 83: a return from the other space is waited out only when the unbind before it was the
 * switch away, in this boot. Every doubt awaits nothing, because a wait nobody needed hides a real
 * stoppage for as long as it lasts.
 */
class SpaceReturnTest {

    private val boot = 7

    @Test
    fun `an unbind with another space in front, in this boot, is awaited`() {
        assertTrue(SpaceReturn.awaited(awaitingBoot = boot, boot = boot))
    }

    @Test
    fun `a switch away before a restart is not this boot's return`() {
        // After a restart the watcher is bound or not for reasons of its own; the process-start
        // grace covers the bind, and nothing is coming back from a space.
        assertFalse(SpaceReturn.awaited(awaitingBoot = boot, boot = boot + 1))
    }

    @Test
    fun `an unreadable boot count awaits nothing`() {
        // -1 is the absence of an answer (DeviceBoot). Stored and read back as -1 it must still not
        // match, or a failed read would hold every stoppage back.
        assertFalse(SpaceReturn.awaited(awaitingBoot = -1, boot = -1))
        assertFalse(SpaceReturn.awaited(awaitingBoot = boot, boot = -1))
    }

    @Test
    fun `nothing stored awaits nothing`() {
        assertFalse(SpaceReturn.awaited(awaitingBoot = Int.MIN_VALUE, boot = boot))
        assertFalse(SpaceReturn.awaited(awaitingBoot = Int.MIN_VALUE, boot = Int.MIN_VALUE))
    }

    @Test
    fun `a return nobody has seen yet has no age`() {
        // The first check to see this space in front starts the wait; until then there is no clock
        // to read, and read() answers exactly as it did before invariant 83.
        assertNull(SpaceReturn.sinceSeen(seenRt = 0L, nowRt = 5_000_000L))
    }

    @Test
    fun `a seen return has the age of that first sighting`() {
        assertEquals(12_000L, SpaceReturn.sinceSeen(seenRt = 5_000_000L, nowRt = 5_012_000L))
        assertEquals(0L, SpaceReturn.sinceSeen(seenRt = 5_000_000L, nowRt = 5_000_000L))
    }

    @Test
    fun `a stamp ahead of the clock is not this return's`() {
        // The monotonic clock restarts at boot; a stamp larger than now cannot be from this boot's
        // clock, and an age computed from it would be negative — inside any grace, forever.
        assertNull(SpaceReturn.sinceSeen(seenRt = 9_000_000L, nowRt = 5_000_000L))
    }
}
