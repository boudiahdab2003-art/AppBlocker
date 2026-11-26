package com.appblocker.service

/**
 * What the coach is told was just blocked, when "Talk to coach" is tapped on a cover
 * (invariant 114).
 *
 * ⚠️ **Never the word or the address.** A page cover can be raised by a blocked word or an adult
 * site, and neither may leave the phone ([BlockLog] keeps the same rule, and so does the coach's
 * prompt). So every page-level block becomes the plain category "a website"; only a whole-app block
 * names the app — the coach already sees app names in the usage data. Pure, for the test.
 */
internal object CoverCoach {

    fun label(why: String?, appLabel: String?): String? = when (why) {
        BlockWhy.INSTAGRAM -> "Instagram"
        BlockWhy.SHORTS -> "YouTube Shorts"
        BlockWhy.GEMINI_TEMP, BlockWhy.GEMINI_INSTRUCTIONS -> "Gemini"
        BlockWhy.DISCOVER -> "the Google news feed"
        BlockWhy.ULTRA_SAVER -> "Ultra battery saver"
        BlockWhy.LOW_BATTERY -> "apps while the battery is low"
        BlockWhy.PURCHASE -> "an in-app purchase"
        BlockWhy.WORD, BlockWhy.SITE, BlockWhy.ADULT -> "a website"
        BlockWhy.GUARD, BlockWhy.GUARD_SERVICE, BlockWhy.GUARD_UNINSTALL, BlockWhy.GUARD_ADMIN,
        BlockWhy.GUARD_APPINFO, BlockWhy.GUARD_LAUNCHER -> "a protection setting"
        // The danger hour shuts the browser itself — say so, still without a word.
        BlockWhy.DANGER -> appLabel?.let { "$it (the danger hour)" } ?: "the browser (the danger hour)"
        else -> appLabel
    }
}
