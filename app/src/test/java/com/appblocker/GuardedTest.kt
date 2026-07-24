package com.appblocker

import com.appblocker.service.runGuarded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watcher's safety net. Before it existed, one unexpected exception anywhere in the event
 * path killed the process and ended all blocking silently.
 */
class GuardedTest {

    @Test
    fun anErrorIsSwallowedAndTheCallerCarriesOn() {
        var reported: Pair<String, Throwable>? = null

        runGuarded("event", { where, t -> reported = where to t }) {
            throw IllegalStateException("node recycled")
        }
        val reachedTheEnd = true

        assertTrue("execution must continue past a failing block", reachedTheEnd)
        assertEquals("event", reported?.first)
        assertEquals("node recycled", reported?.second?.message)
    }

    @Test
    fun successfulWorkIsUntouched() {
        var ran = false
        runGuarded("event", { _, _ -> throw AssertionError("must not report on success") }) { ran = true }
        assertTrue(ran)
    }

    /** A broken reporter must never become the thing that crashes the watcher. */
    @Test
    fun aFailingReporterIsAlsoContained() {
        runGuarded("event", { _, _ -> throw IllegalStateException("prefs unavailable") }) {
            throw RuntimeException("original")
        }
    }

    /** The runtime can't continue past these, so surviving them would only fake health. */
    @Test(expected = OutOfMemoryError::class)
    fun fatalRuntimeErrorsAreRethrown() {
        runGuarded("event", { _, _ -> }) { throw OutOfMemoryError() }
    }
}
