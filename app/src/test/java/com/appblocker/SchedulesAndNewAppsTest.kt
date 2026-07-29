package com.appblocker

import com.appblocker.data.NewAppWatcher
import com.appblocker.service.timeScheduleCanFire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A schedule you can save that can never fire, and a new app that never gets blocked.
 *
 * Both are the same failure: the owner believes he is protected and is not. Nothing errors,
 * nothing looks wrong, and the only way to find out is the way he must never find out.
 */
class ScheduleCanFireTest {

    private val allDays = 0b1111111

    @Test
    fun `no days selected can never fire`() {
        // The day chips are free toggles, so the mask really can reach zero, and Save used to
        // accept it — leaving a schedule sitting in the list looking switched on.
        assertFalse(timeScheduleCanFire(daysMask = 0, startMinutes = 9 * 60, endMinutes = 17 * 60))
    }

    @Test
    fun `start equal to end can never fire`() {
        // The window is half-open, so 09:00-09:00 contains no minute at all. It reads like
        // "all day" and behaves like "never".
        assertFalse(timeScheduleCanFire(allDays, 9 * 60, 9 * 60))
        assertFalse(timeScheduleCanFire(allDays, 0, 0))
    }

    @Test
    fun `ordinary and overnight schedules are fine`() {
        assertTrue(timeScheduleCanFire(allDays, 9 * 60, 17 * 60))
        assertTrue(timeScheduleCanFire(allDays, 22 * 60, 6 * 60)) // wraps midnight
        assertTrue(timeScheduleCanFire(0b0000001, 22 * 60, 6 * 60)) // one day is enough
        assertTrue(timeScheduleCanFire(allDays, 0, 23 * 60 + 59)) // the "whole day" advice
    }
}

/**
 * The backstop behind "Add newly installed apps".
 *
 * The receiver it backs up is a single broadcast with no retry, and Android withholds it from an
 * app in the stopped state — what a force stop produces, which v1.109 stopped guarding. So an app
 * installed at the wrong moment is never blocked, silently, on the feature whose whole job is to
 * stop a new app becoming a way round the others.
 *
 * The tests below are mostly about the *other* direction, because getting this wrong blocks every
 * app on the phone at once — much worse than the hole being closed.
 */
class NewAppWatcherTest {

    @Test
    fun `with no baseline, nothing is new`() {
        // THE load-bearing case. Every phone starts with no record, and reading that as "every
        // app is new" would block the lot on first run.
        assertEquals(
            emptySet<String>(),
            NewAppWatcher.newlyInstalled(null, setOf("com.a", "com.b", "com.c")),
        )
    }

    @Test
    fun `only what appeared since last time is new`() {
        assertEquals(
            setOf("com.new"),
            NewAppWatcher.newlyInstalled(setOf("com.a", "com.b"), setOf("com.a", "com.b", "com.new")),
        )
    }

    @Test
    fun `an unchanged phone yields nothing`() {
        val same = setOf("com.a", "com.b")
        assertEquals(emptySet<String>(), NewAppWatcher.newlyInstalled(same, same))
    }

    @Test
    fun `uninstalling is not installing`() {
        // A shrinking set must not produce anything to block, or removing an app would look like
        // an event.
        assertEquals(
            emptySet<String>(),
            NewAppWatcher.newlyInstalled(setOf("com.a", "com.b"), setOf("com.a")),
        )
    }

    @Test
    fun `an empty baseline is not the same as no baseline`() {
        // Empty means "we looked and there was nothing" — rare, but it is a real answer, and
        // everything present now is genuinely new. Null means "we never looked".
        assertEquals(setOf("com.a"), NewAppWatcher.newlyInstalled(emptySet(), setOf("com.a")))
        assertEquals(emptySet<String>(), NewAppWatcher.newlyInstalled(null, setOf("com.a")))
    }
}
