package com.appblocker

import com.appblocker.data.DeviceVendor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sentence that tells a stuck person where their phone keeps the accessibility list.
 *
 * **The point of this test is that the app must not state a guess as a fact.** Every brand path in
 * `DeviceVendor` was written from recollection, not from a phone — the same footing as
 * `com.samsung.android.packageinstaller`, which was believed for a year and measured wrong in
 * twenty minutes. So an unmeasured path has to be offered as a likelihood and paired with the one
 * instruction that is true on every Android phone ever made: use the Settings search box.
 */
class AccessibilityHintTest {

    private fun hintFor(manufacturer: String) =
        DeviceVendor.accessibilityHint(DeviceVendor.advice(manufacturer))

    @Test
    fun `an unmeasured brand is offered as a likelihood, never as a fact`() {
        // Samsung and Xiaomi are absent on purpose: both have been measured on real phones and
        // have their own case below. These three still have not.
        for (brand in listOf("Huawei", "Oppo", "Vivo")) {
            val hint = hintFor(brand)
            assertTrue(
                "$brand: an unmeasured path must be hedged, not asserted — got: $hint",
                hint.contains("On most"),
            )
            assertFalse(
                "$brand: must not claim to know this phone — got: $hint",
                hint.contains("On your phone"),
            )
        }
    }

    /**
     * The escape hatch, and the only part of this that cannot go stale: every Settings app has a
     * search box, on every brand and every version, however the menu has been renamed.
     */
    @Test
    fun `every hint offers the search box`() {
        for (brand in listOf("Samsung", "Xiaomi", "Huawei", "Oppo", "Vivo", "Google", "")) {
            val hint = hintFor(brand)
            assertTrue(
                "$brand: no search-box fallback — got: $hint",
                hint.contains("search", ignoreCase = true),
            )
        }
    }

    /**
     * An unrecognised phone gets the instruction that works anywhere and no menu path at all —
     * naming a path for a brand we did not even identify would be inventing one twice over.
     */
    @Test
    fun `an unknown phone is told only what is true everywhere`() {
        val hint = hintFor("Nothing Phone")
        assertTrue(hint.contains("search", ignoreCase = true))
        assertFalse("named a menu path for a phone it could not identify", hint.contains("▸"))
        assertFalse(hint.contains("On most"))
    }

    /** Whatever the wording, it has to end up as something a person can act on. */
    @Test
    fun `every hint is a usable sentence`() {
        for (brand in listOf("Samsung", "Xiaomi", "Huawei", "Oppo", "Vivo", "Google", "")) {
            val hint = hintFor(brand)
            assertTrue("$brand: too short to help — got: $hint", hint.length >= 40)
            assertTrue("$brand: does not end as a sentence", hint.trim().endsWith("."))
        }
    }

    /**
     * **Samsung has been looked at, so Samsung is told plainly.** A Galaxy A36 on One UI 8 really
     * does keep the services behind Accessibility ▸ Installed apps — photographed 23 Aug 2026,
     * not recalled. Hedging a fact somebody went and checked would waste the trip.
     */
    @Test
    fun `a measured brand is stated as fact`() {
        for ((brand, section) in mapOf("Samsung" to "Installed apps", "Xiaomi" to "Downloaded apps")) {
            val hint = hintFor(brand)
            assertTrue("$brand was measured; say so — got: $hint", hint.contains("On your phone"))
            assertTrue("$brand: wrong section — got: $hint", hint.contains(section))
            assertFalse("stop hedging a checked fact — got: $hint", hint.contains("On most"))
        }
    }

    /**
     * **The correction that paid for the exercise.** Xiaomi's path was written as "Settings ▸
     * Additional settings ▸ Accessibility ▸ Downloaded apps" — where older MIUI kept it. On the
     * owner's own HyperOS phone Accessibility sits at the top level, so the sentence contained a
     * step that does not exist. That is how a stuck person concludes the app is describing
     * somebody else's phone.
     */
    @Test
    fun `Xiaomi is not sent through a menu that no longer exists`() {
        val hint = hintFor("Xiaomi")

        assertFalse("HyperOS has no Additional settings step — got: $hint",
            hint.contains("Additional settings"))
    }
}
