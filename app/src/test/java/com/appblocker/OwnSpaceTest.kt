package com.appblocker

import com.appblocker.data.OwnSpace
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariant 79: a check stands down only when Android positively says another space is in front.
 * Every doubt answers "in front", because the guard's failure the other way is a stoppage nobody sees.
 */
class OwnSpaceTest {

    private val s = 31

    @Test
    fun `another space in front stands the check down`() {
        assertFalse(OwnSpace.resolve(s, answer = false))
        assertFalse(OwnSpace.resolve(36, answer = false))
    }

    @Test
    fun `our own space in front lets the check run`() {
        assertTrue(OwnSpace.resolve(s, answer = true))
    }

    @Test
    fun `no answer lets the check run`() {
        // No UserManager, or the call threw: never a reason to hide a stoppage.
        assertTrue(OwnSpace.resolve(s, answer = null))
    }

    @Test
    fun `before Android 12 there is nothing to ask, so the check runs`() {
        // Even a stray false cannot count there: the method does not exist below API 31.
        assertTrue(OwnSpace.resolve(30, answer = false))
        assertTrue(OwnSpace.resolve(24, answer = null))
    }
}
