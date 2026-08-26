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
     *  A distinct state on purpose — see [NetworkFilter.shouldShutBrowsers]. */
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
 * So the app's job here is not to *be* the filter. It is to **guide it, verify it, and defend it**.
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

    /** The resolver he chose. CleanBrowsing's family filter also forces SafeSearch on the big
     *  search engines, which the other lists here do not. */
    const val RECOMMENDED = "family-filter-dns.cleanbrowsing.org"

    /**
     * Resolvers this app accepts as actually filtering adult content.
     *
     * ⚠️ **Membership is the whole protection, so the bar is "this operator publishes it as an
     * adult filter"** — not "this is a good resolver". `security.cloudflare-dns.com` and
     * `dns.adguard-dns.com` are deliberately absent: they block malware and ads and let everything
     * here straight through, so accepting them would mean reading a switched-off protection as on.
     *
     * ⚠️ Verify each one resolves on a real device before shipping. A hostname in this list that
     * the phone cannot reach is a phone with no working DNS — the one failure worse than no filter.
     */
    val KNOWN_FAMILY_RESOLVERS = listOf(
        "family-filter-dns.cleanbrowsing.org", // CleanBrowsing family: adult + forced SafeSearch
        "adult-filter-dns.cleanbrowsing.org",  // CleanBrowsing adult-only
        "family.cloudflare-dns.com",           // Cloudflare for Families: adult + malware
        "family.adguard-dns.com",              // AdGuard family
    )

    /**
     * How long a reading has to hold still before it costs him anything.
     *
     * The state churns on every network change — Wi-Fi to mobile, a tunnel coming up, the screen
     * waking. Acting on the first frame would shut every browser for a few seconds several times a
     * day, and a protection that misfires that often is one that gets switched off. A minute is
     * long enough to outlast a handover and far too short to be worth using as a way out.
     */
    const val SETTLE_MS = 60_000L

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
     * **Whether to shut every browser on the phone**, which is what he chose should happen when the
     * filter stops: *"i dont want the capability to turn it off"*. Android does not let an app be
     * un-switchable, so switching it off costs the browsers instead — immediately, and only the
     * browsers (`decideBlock` gates it on the strict self-declared browser set, invariant 13, so
     * maps, the dialer, Settings and the bank app are never touched).
     *
     * Three refusals, each one a way this could otherwise leave him with a phone he cannot use:
     *
     *  - **[FilterState.CANT_TELL] never costs anything.** Invariant 4 says a failed measurement is
     *    not permission; the reverse is just as true — a failed measurement is not a punishment.
     *  - **An unvalidated network is not judged.** A captive portal (hotel, airport) resolves
     *    nothing until you log in and breaks Private DNS by design. Shutting the browsers there
     *    would leave him unable to reach the login page *and* unable to fix the filter. The
     *    screen-reading blocker still covers that network; only this layer stands down.
     *  - **A reading has to hold still for [SETTLE_MS].**
     *
     * @param armed the filter has been seen working on this phone at least once, and he has
     *   not since spent the cooling-off to switch the guard off. **Without this an update would
     *   shut the browsers of every phone that never set the filter up** — the app would arrive,
     *   find no filter, and take the browsers away from someone who was never told why. A
     *   protection may only defend a thing it has watched working.
     * @param offForMs how long the state has continuously not been protecting. Passed in rather
     *   than read here so the caller can anchor it monotonically (invariant 9) and so this stays
     *   a pure function — unit tests return 0 from `SystemClock.elapsedRealtime()`.
     */
    fun shouldShutBrowsers(
        state: FilterState,
        networkValidated: Boolean,
        offForMs: Long,
        armed: Boolean,
    ): Boolean {
        if (!armed) return false
        if (state == FilterState.CANT_TELL) return false
        if (protecting(state)) return false
        if (!networkValidated) return false
        return offForMs >= SETTLE_MS
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
