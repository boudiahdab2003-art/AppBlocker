package com.appblocker.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/** What the phone's DNS is doing, as far as this app can tell. */
enum class FilterState {
    /** Private DNS is on and pointed at a resolver we know filters adult content. */
    FILTERING,

    /** Private DNS is on, but at a resolver that filters nothing — or at "automatic", which
     *  encrypts and filters nothing. Encrypted is not the same as protected. */
    ON_BUT_UNKNOWN,

    /** Private DNS is off. */
    OFF,

    /** No answer available: Android 8 or older, no readable network, a system call that threw.
     *  A distinct state on purpose: "can't tell" is not his failure and must not read as one. */
    CANT_TELL,
}

/**
 * Blocking one layer below the screen.
 *
 * Asked for by the owner on 26 Aug 2026: *"cant we include a dns in our app it will take our app to
 * another level?"*, and then exactly: **a strong family-filtering DNS on by default, blocking bad
 * sites automatically, with no capability to turn it off.** He chose the resolver himself —
 * *"i want this dns family-filter-dns.cleanbrowsing.org"* — which is [RECOMMENDED].
 *
 * ## Why a system setting rather than a VPN of our own
 *
 * Everything else in this app depends on the accessibility service being alive and looking at a
 * rendered screen, and the failure this codebase has spent the most releases on is that service
 * being killed while Android still reports it as on. Private DNS has none of that exposure: **it is
 * not an app**, so no battery manager can kill it, it costs nothing to run because the phone was
 * making those lookups anyway, and it is encrypted end to end. A `VpnService` of our own would
 * carry a permanent connection (against *"keep the battery as it is"*), collide with any real VPN,
 * and draw Play review. The one thing it would win is setting itself — and it cannot be set for
 * him anyway without `WRITE_SECURE_SETTINGS`, which means a cable, which this app does not ask for.
 *
 * So the app's job here is not to *be* the filter. It is to **guide it and verify it**.
 *
 * ## It no longer defends it
 *
 * From v1.142 a filter that stopped (switched off, or pointed at another resolver) shut every
 * browser after a minute. Removed on 22 Oct 2026 at his request: his university requires a VPN,
 * and a VPN carries its own DNS, so the phone reads as "filter off" for as long as it is connected.
 * Defending the setting would have closed his browsers for every hour of study. The screen still
 * shows whether the filter is running and how to set it up; nothing is shut over it any more.
 *
 * ## What this cannot do
 *
 * It blocks whole sites. It cannot block a search — the search happens on a site he needs — it
 * cannot read a page, and it cannot name the word he typed. [com.appblocker.service.WebContentFilter]
 * remains the layer that catches those. This one catches what the screen never shows: an in-app
 * browser, an image load, an app that draws no readable text.
 *
 * It also cannot close a browser's *own* DNS-over-HTTPS setting, which routes around the system
 * resolver entirely. That is the concrete job of the phase-2 filter and the reason it is worth
 * building; it is not a reason to delay this.
 */
internal object NetworkFilter {

    /**
     * The resolver, fixed. His own choice — *"i want this dns family-filter-dns.cleanbrowsing.org"*
     * — and already set on his phone and tablet before this feature existed, which is why the app
     * arms itself on first launch with nothing for him to do.
     *
     * CleanBrowsing's family filter also forces SafeSearch on the big search engines, which the
     * alternatives considered here do not.
     */
    const val RECOMMENDED = "family-filter-dns.cleanbrowsing.org"

    /**
     * The resolvers this app accepts as actually filtering adult content — **exactly one**.
     *
     * It began as four (Cloudflare's family filter, AdGuard's, CleanBrowsing's two) and the owner
     * closed it on 26 Aug 2026: *"the dns i gave you is already set on my android phone and tablet
     * so just make it fixed"*. His phone and tablet were already pointed at [RECOMMENDED] before
     * any of this shipped, so accepting alternatives bought nothing he wanted and cost the one
     * thing he asked for: **changing the resolver is now indistinguishable from switching the
     * filter off**, and costs the browsers the same way. A list of four is a list of four ways to
     * quietly end up somewhere weaker.
     *
     * ⚠️ **Membership is the whole protection.** The bar is "this operator publishes it as an
     * adult filter" — never "this is a good resolver". `security.cloudflare-dns.com` and
     * `dns.adguard-dns.com` block malware and ads and pass every bit of this straight through;
     * accepting one would read a switched-off protection as on.
     *
     * ⚠️ If this ever grows again, verify each entry resolves on a real device first. A hostname
     * the phone cannot reach is a phone with no working DNS — the one failure worse than no filter.
     */
    val KNOWN_FAMILY_RESOLVERS = listOf(RECOMMENDED)

    /** True only for the one state that means adult content is actually being filtered. */
    fun protecting(state: FilterState) = state == FilterState.FILTERING

    /**
     * What the phone's DNS is doing. Pure, so the matrix is testable — the reader below is the
     * only part that touches Android.
     *
     * @param apiSupported Private DNS arrived in Android 9; `minSdk` here is 24.
     * @param linkKnown whether link properties could be read for an active network at all.
     */
    fun classify(
        apiSupported: Boolean,
        linkKnown: Boolean,
        privateDnsActive: Boolean,
        hostname: String?,
    ): FilterState = when {
        !apiSupported || !linkKnown -> FilterState.CANT_TELL
        !privateDnsActive -> FilterState.OFF
        isFamilyResolver(hostname) -> FilterState.FILTERING
        // On, but "automatic" (no hostname) or a resolver that filters nothing. Encrypted, and
        // wide open. This is the state the whole design turns on: reading the *switch* would call
        // this protected, so the app reads *which resolver* instead.
        else -> FilterState.ON_BUT_UNKNOWN
    }

    /** Whole-name match, tolerant of how a hostname gets typed and of the trailing root dot —
     *  never a substring, so `family-filter-dns.cleanbrowsing.org.example.net` cannot pass. */
    private fun isFamilyResolver(hostname: String?): Boolean {
        val h = hostname?.trim()?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return false
        return h in KNOWN_FAMILY_RESOLVERS
    }

    /**
     * Reads the live state off the active network.
     *
     * Wrapped in `runCatching` for the same reason [com.appblocker.service.ProtectionWatchdog] is:
     * this is called from the watchdog and from composables, OEM system services do throw, and the
     * thing that reports on a protection must never be able to take the app down. A failure is
     * [FilterState.CANT_TELL] — "can't tell", which by the rule above costs nothing.
     */
    fun read(context: Context): Reading {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return Reading(FilterState.CANT_TELL, null, validated = false)
        }
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return Reading(FilterState.CANT_TELL, null, validated = false)
            val network = cm.activeNetwork ?: return Reading(FilterState.CANT_TELL, null, false)
            val link = cm.getLinkProperties(network)
                ?: return Reading(FilterState.CANT_TELL, null, validated = false)
            val caps = cm.getNetworkCapabilities(network)
            val validated = caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
            val host = link.privateDnsServerName
            Reading(
                classify(
                    apiSupported = true,
                    linkKnown = true,
                    privateDnsActive = link.isPrivateDnsActive,
                    hostname = host,
                ),
                host,
                validated,
            )
        }.getOrElse { Reading(FilterState.CANT_TELL, null, validated = false) }
    }

    /** One reading of the network: what it is doing, which resolver, and whether the network is
     *  working at all (a captive portal is not). */
    data class Reading(
        val state: FilterState,
        val hostname: String?,
        val validated: Boolean,
    )
}
