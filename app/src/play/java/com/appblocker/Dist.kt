package com.appblocker

/**
 * Google Play distribution: Play delivers updates itself (self-updating APKs are forbidden
 * there), and the location/Wi-Fi schedule types are left out so the build needs no location
 * permissions (background location triggers Google's heaviest review).
 */
object Dist {
    const val SELF_UPDATE = false
    const val LOCATION_SCHEDULES = false

    /**
     * No silent repair here: it rests on `WRITE_SECURE_SETTINGS`, which a Play user cannot grant
     * without a computer and `adb`, and which this flavour does not declare. Revisit only if the
     * Play build ever gets a supported way to hand that permission over.
     */
    const val SELF_TOGGLE = false

    /**
     * No donate link here, and **this is a decision, not an oversight** — delete the flag and
     * the row appears in a Play build.
     *
     * Two reasons. Google's payments policy is strict about money leaving an app outside Play
     * Billing; donations that unlock nothing are usually tolerated, but "usually" is not worth
     * betting a first submission on. And the Play build is the one meant to be *paid for*
     * (`docs/PLAY_VERSION_PLAN.md`), so asking that user for a donation as well is the wrong ask.
     *
     * Revisit with Phase 3 of the Play plan, where the paid model is settled — not before.
     */
    const val DONATIONS = false
}
