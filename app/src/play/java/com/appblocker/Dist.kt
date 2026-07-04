package com.appblocker

/**
 * Google Play distribution: Play delivers updates itself (self-updating APKs are forbidden
 * there), and the location/Wi-Fi schedule types are left out so the build needs no location
 * permissions (background location triggers Google's heaviest review).
 */
object Dist {
    const val SELF_UPDATE = false
    const val LOCATION_SCHEDULES = false
}
