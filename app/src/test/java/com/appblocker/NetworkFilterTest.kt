package com.appblocker

import com.appblocker.data.FilterState
import com.appblocker.data.NetworkFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The network filter's two decisions, both pure: **what is the phone's DNS doing**, and **is that
 * worth shutting the browsers over**.
 *
 * The second one is why this file is careful. Every other layer in the app answers "should this
 * screen be covered"; this one answers "should every browser on the phone stop working", from a
 * reading of a system setting that can be missing, stale or mid-change. A wrong `true` here is not
 * a false-positive block — it is a phone that cannot reach the internet and cannot be told why.
 */
class NetworkFilterTest {

    private val family = "family-filter-dns.cleanbrowsing.org"

    // ---- classify: what is the phone actually doing --------------------------------------

    @Test fun `an old phone can never be read, so it is never judged`() {
        // Private DNS arrived in Android 9 and minSdk is 24. "I cannot tell" is the honest answer
        // and it must not decay into "off" — see enforcement below, where off has consequences.
        assertEquals(
            FilterState.CANT_TELL,
            NetworkFilter.classify(apiSupported = false, linkKnown = true, privateDnsActive = true, hostname = family),
        )
    }

    @Test fun `no readable network is not the same as no filter`() {
        assertEquals(
            FilterState.CANT_TELL,
            NetworkFilter.classify(apiSupported = true, linkKnown = false, privateDnsActive = false, hostname = null),
        )
    }

    @Test fun `the switch being off is off`() {
        assertEquals(
            FilterState.OFF,
            NetworkFilter.classify(apiSupported = true, linkKnown = true, privateDnsActive = false, hostname = null),
        )
    }

    @Test fun `a known family resolver is the one state that counts as protected`() {
        assertEquals(
            FilterState.FILTERING,
            NetworkFilter.classify(apiSupported = true, linkKnown = true, privateDnsActive = true, hostname = family),
        )
        assertTrue(NetworkFilter.protecting(FilterState.FILTERING))
    }

    /**
     * **The bypass this state exists to close.** Android's "automatic" Private DNS encrypts the
     * lookup and filters nothing, and a hand-typed `dns.google` does the same. Both leave the
     * switch reading as ON while the protection is gone — so the app must not read the switch, it
     * must read *which resolver*.
     */
    @Test fun `encrypted but not filtering is not protected`() {
        for (host in listOf(null, "dns.google", "one.one.one.one", "dns.quad9.net")) {
            val state = NetworkFilter.classify(
                apiSupported = true, linkKnown = true, privateDnsActive = true, hostname = host,
            )
            assertEquals("$host must not read as filtering", FilterState.ON_BUT_UNKNOWN, state)
            assertFalse(NetworkFilter.protecting(state))
        }
    }

    @Test fun `a hostname is matched however it was typed`() {
        for (host in listOf("  FAMILY-Filter-DNS.CleanBrowsing.org ", "family-filter-dns.cleanbrowsing.org.")) {
            assertEquals(
                "$host is the same resolver",
                FilterState.FILTERING,
                NetworkFilter.classify(true, true, true, host),
            )
        }
    }

    /** A lookalike must not pass: the check is the whole name, not a piece of it. */
    @Test fun `a hostname that merely contains a known one does not count`() {
        assertEquals(
            FilterState.ON_BUT_UNKNOWN,
            NetworkFilter.classify(true, true, true, "family-filter-dns.cleanbrowsing.org.example.net"),
        )
    }

    // ---- enforce: is this worth shutting every browser over -------------------------------

    private fun enforce(
        state: FilterState,
        validated: Boolean = true,
        offForMs: Long = NetworkFilter.SETTLE_MS,
        armed: Boolean = true,
    ) = NetworkFilter.shouldShutBrowsers(state, validated, offForMs, armed)

    @Test fun `not being able to tell never shuts anything`() {
        assertFalse(enforce(FilterState.CANT_TELL))
        assertFalse(enforce(FilterState.CANT_TELL, validated = false))
        assertFalse(enforce(FilterState.CANT_TELL, offForMs = Long.MAX_VALUE))
    }

    @Test fun `a working filter shuts nothing`() {
        assertFalse(enforce(FilterState.FILTERING))
    }

    @Test fun `the filter being off shuts the browsers`() {
        assertTrue(enforce(FilterState.OFF))
        // …and so does the encrypted-but-not-filtering case, or it becomes the way out.
        assertTrue(enforce(FilterState.ON_BUT_UNKNOWN))
    }

    /**
     * **The hotel wifi case.** A captive portal is a network that exists but resolves nothing until
     * you log in, and Private DNS breaks on it by design. Shutting the browsers there leaves him
     * unable to reach the login page *and* unable to fix the filter — a phone with no way out.
     *
     * The screen-reading blocker still applies on that network. Only this layer stands down.
     */
    @Test fun `an unvalidated network is not judged`() {
        assertFalse(enforce(FilterState.OFF, validated = false))
        assertFalse(enforce(FilterState.ON_BUT_UNKNOWN, validated = false))
    }

    /**
     * The reading churns every time the phone changes network — Wi-Fi to mobile, a tunnel coming
     * up, the screen waking. Acting on the first frame of that would shut the browsers for a few
     * seconds several times a day, which is how a protection becomes the thing he switches off.
     */
    @Test fun `a reading has to hold still before it costs anything`() {
        assertFalse(enforce(FilterState.OFF, offForMs = 0L))
        assertFalse(enforce(FilterState.OFF, offForMs = NetworkFilter.SETTLE_MS - 1))
        assertTrue(enforce(FilterState.OFF, offForMs = NetworkFilter.SETTLE_MS))
    }

    // ---- the shipped list ------------------------------------------------------------------

    @Test fun `every accepted resolver is a hostname and is the recommended one or better`() {
        assertTrue("the list must not be empty", NetworkFilter.KNOWN_FAMILY_RESOLVERS.isNotEmpty())
        for (h in NetworkFilter.KNOWN_FAMILY_RESOLVERS) {
            assertEquals("$h must be stored lowercase and trimmed", h.trim().lowercase(), h)
            assertTrue("$h must be a hostname", "." in h)
        }
        assertTrue(
            "the resolver the setup screen tells him to paste must be one the app accepts",
            NetworkFilter.RECOMMENDED in NetworkFilter.KNOWN_FAMILY_RESOLVERS,
        )
    }

    /**
     * **The update case, and the worst thing this feature could do.** v1.142 arrives on a phone
     * that has never set the filter up: the reading is honestly OFF, the network is fine, and it
     * has been that way for months. Without arming, every browser closes on first launch and the
     * only screen that could explain it is behind a browser-shaped hole.
     *
     * So the guard defends what it has watched working, and nothing else.
     */
    @Test fun `a phone that never set the filter up is never punished for it`() {
        assertFalse(enforce(FilterState.OFF, armed = false))
        assertFalse(enforce(FilterState.ON_BUT_UNKNOWN, armed = false, offForMs = Long.MAX_VALUE))
        assertTrue("and once it has been seen working, it is defended", enforce(FilterState.OFF, armed = true))
    }

}
