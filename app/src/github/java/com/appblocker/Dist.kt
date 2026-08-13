package com.appblocker

/**
 * GitHub (sideloaded) distribution: the app updates itself from GitHub releases and offers
 * every schedule type, including the location/Wi-Fi ones.
 */
object Dist {
    const val SELF_UPDATE = true
    const val LOCATION_SCHEDULES = true

    /**
     * **Off because GitHub Sponsors has not been set up yet, not because this build shouldn't
     * ask.** The sideloaded build is given away and may ask for support; the row, `SPONSOR_URL`
     * and `.github/FUNDING.yml` are all written and tested. What is missing is the page at the
     * other end — shipping a visible button to a 404 is worse than shipping nothing.
     *
     * Switching it on is this one word, once
     * `https://github.com/sponsors/boudiahdab2003-art` loads. Nothing else needs rewriting.
     *
     * Note both flavours read `false` today for **different** reasons — see the `play` copy,
     * which is a policy decision and stays false regardless. Do not conclude from the matching
     * values that the split is pointless and collapse it.
     */
    const val DONATIONS = false
}
