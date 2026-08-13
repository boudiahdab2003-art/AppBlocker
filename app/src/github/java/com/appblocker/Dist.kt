package com.appblocker

/**
 * GitHub (sideloaded) distribution: the app updates itself from GitHub releases and offers
 * every schedule type, including the location/Wi-Fi ones.
 */
object Dist {
    const val SELF_UPDATE = true
    const val LOCATION_SCHEDULES = true

    /** The sideloaded build is given away, so it may ask for support. See the `play` flavour's
     *  copy of this flag for why that build does not. */
    const val DONATIONS = true
}
