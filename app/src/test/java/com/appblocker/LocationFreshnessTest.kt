package com.appblocker

import com.appblocker.service.LOCATION_MAX_AGE_MS
import com.appblocker.service.locationFixUsable
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The location fix's expiry. There was none: `lastLocation` was set once and trusted forever, so a
 * fix taken at the blocked place kept blocking everywhere the user went next, and a fix taken away
 * from it meant coming back never started blocking.
 *
 * Everything here is in `elapsedRealtime` nanos deliberately — a location's age must not be
 * measurable with a clock the user can change (invariant 9).
 */
class LocationFreshnessTest {

    private val ms = 1_000_000L // nanos per millisecond
    private val boot = 90L * 60_000L * ms // an arbitrary "now", 90 minutes after boot

    @Test fun `a fix from this instant is usable`() =
        assertTrue(locationFixUsable(boot, boot))

    @Test fun `a fix from a minute ago is usable`() =
        assertTrue(locationFixUsable(boot - 60_000L * ms, boot))

    @Test fun `a fix just inside the ceiling is usable`() =
        assertTrue(locationFixUsable(boot - (LOCATION_MAX_AGE_MS - 1) * ms, boot))

    @Test fun `a fix exactly at the ceiling is still usable`() =
        assertTrue(locationFixUsable(boot - LOCATION_MAX_AGE_MS * ms, boot))

    @Test fun `a fix past the ceiling is not usable`() =
        assertFalse(locationFixUsable(boot - (LOCATION_MAX_AGE_MS + 1) * ms, boot))

    @Test
    fun `an hours-old fix is not usable`() {
        // The whole bug: the phone sat still, no new fix arrived, and this one decided anyway.
        assertFalse(locationFixUsable(boot - 60L * 60_000L * ms, boot))
    }

    @Test
    fun `a fix from the future is not usable`() {
        // Impossible, so it means something is wrong with the reading — treat it as unknown
        // rather than as infinitely fresh, which is what a plain "age <= max" test would do.
        assertFalse(locationFixUsable(boot + 5_000L * ms, boot))
    }

    @Test
    fun `a fix taken right after boot is usable while still young`() {
        // elapsedRealtime starts at 0 on boot, so this is the genuine edge, not a contrived one.
        assertTrue(locationFixUsable(0L, 30_000L * ms))
        assertFalse(locationFixUsable(0L, (LOCATION_MAX_AGE_MS + 1) * ms))
    }

    @Test
    fun `the ceiling is long enough not to fight the refresh cadence`() {
        // The service pulls a fresh fix at most once a minute and subscribes at 30s intervals, so
        // the ceiling must be comfortably above both or it would expire fixes in normal use.
        assertTrue("ceiling must exceed the refresh interval by a wide margin",
            LOCATION_MAX_AGE_MS >= 5 * 60_000L)
    }
}
