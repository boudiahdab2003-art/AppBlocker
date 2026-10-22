package com.appblocker

import com.appblocker.data.FilterState
import com.appblocker.data.NetworkFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The network filter's one decision, pure: **what is the phone's DNS doing**. (It used to make a
 * second one — whether to shut the browsers over it — removed on 22 Oct 2026 because his
 * university's VPN reads as "filter off"; see [NetworkFilter].)
 */
class NetworkFilterTest {

    private val family = "family-filter-dns.cleanbrowsing.org"

    // ---- classify: what is the phone actually doing --------------------------------------

    @Test fun `an old phone can never be read, so it is never judged`() {
        // Private DNS arrived in Android 9 and minSdk is 24. "I cannot tell" is the honest answer
        // and it must not decay into "off", which the setup screen paints as his failure.
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
     * **Fixed means fixed.** The list was four resolvers until he pointed out that both his
     * devices were already on one of them: *"just make it fixed"*. So another operator's family
     * filter — genuinely a filter, genuinely blocking this material — no longer counts, and the
     * setup screen reads it as not filtering.
     */
    @Test fun `only his resolver counts, not merely a good one`() {
        assertEquals(listOf(NetworkFilter.RECOMMENDED), NetworkFilter.KNOWN_FAMILY_RESOLVERS)
        for (other in listOf(
            "family.cloudflare-dns.com", "family.adguard-dns.com",
            "adult-filter-dns.cleanbrowsing.org",
        )) {
            assertEquals(
                "$other filters, but it is not the one that is set",
                FilterState.ON_BUT_UNKNOWN,
                NetworkFilter.classify(true, true, true, other),
            )
        }
    }

}
