package com.appblocker

import com.appblocker.data.DeviceVendor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-brand keep-alive advice.
 *
 * **What this is really guarding.** The advice used to be one hardcoded paragraph addressed to
 * Xiaomi owners, shown on every phone. The failure it caused is invisible in the way this project
 * keeps being bitten by: a Samsung owner is never told to stop the phone sleeping the app, the
 * phone sleeps it, blocking stops, and nothing on screen says so.
 *
 * So the tests below are mostly about the *fallback* rather than the happy path. Getting a brand
 * wrong costs a paragraph; claiming a deep link that doesn't exist, or shipping an entry with
 * nothing written in it, costs the user the one instruction they came for.
 */
class DeviceVendorTest {

    @Test
    fun `each brand gets its own advice`() {
        assertEquals("Xiaomi", DeviceVendor.advice("Xiaomi").brand)
        assertEquals("Samsung", DeviceVendor.advice("samsung").brand)
        assertEquals("Huawei", DeviceVendor.advice("HUAWEI").brand)
        assertEquals("Oppo", DeviceVendor.advice("OPPO").brand)
        assertEquals("Vivo", DeviceVendor.advice("vivo").brand)
    }

    /** Sub-brands ship the same battery manager under the same package name as the parent. */
    @Test
    fun `sub-brands fold into their parent`() {
        assertEquals("Xiaomi", DeviceVendor.advice("Redmi").brand)
        assertEquals("Xiaomi", DeviceVendor.advice("POCO").brand)
        assertEquals("Huawei", DeviceVendor.advice("HONOR").brand)
        assertEquals("Oppo", DeviceVendor.advice("realme").brand)
        assertEquals("Oppo", DeviceVendor.advice("OnePlus").brand)
        assertEquals("Vivo", DeviceVendor.advice("iQOO").brand)
    }

    /** The real-world spelling: Build.MANUFACTURER is whatever the OEM felt like that year. */
    @Test
    fun `matching ignores case and surrounding words`() {
        assertEquals("Samsung", DeviceVendor.advice("Samsung Electronics Co., Ltd.").brand)
        assertEquals("Xiaomi", DeviceVendor.advice("Xiaomi Communications").brand)
    }

    /**
     * The case the owner's own emulator hits, and every Pixel: no brand match, so no instructions
     * that name a page the phone doesn't have, and **no deep link** — a button that opens nothing
     * is worse than a paragraph telling the user what to look for.
     */
    @Test
    fun `an unknown phone gets generic advice and no deep link`() {
        for (unknown in listOf("Google", "Nothing", "Fairphone", "", null)) {
            val advice = DeviceVendor.advice(unknown)
            assertEquals("Unknown brand should not be named: $unknown", "", advice.brand)
            assertTrue("Unknown brand must not deep-link: $unknown", advice.deepLinks.isEmpty())
            assertNull("Unknown brand must not name a cloning feature: $unknown",
                advice.clonedAppsFeature)
        }
    }

    /**
     * An entry with an empty field is a card that renders as a heading over nothing — which is
     * exactly what the user sees when someone adds a brand and fills in half of it.
     */
    @Test
    fun `every entry says something in every field`() {
        val brands = listOf("Xiaomi", "Samsung", "Huawei", "Oppo", "Vivo", "Google")
        for (b in brands) {
            val advice = DeviceVendor.advice(b)
            assertTrue("$b: blank keepAliveLabel", advice.keepAliveLabel.isNotBlank())
            assertTrue("$b: blank keepAliveDesc", advice.keepAliveDesc.isNotBlank())
            assertTrue("$b: blank extraTips", advice.extraTips.isNotBlank())
            assertTrue(
                "$b: deep link with a blank package or class",
                advice.deepLinks.all { it.first.isNotBlank() && it.second.isNotBlank() },
            )
            // The wizard reads this out when someone comes back from Settings without having
            // switched the service on, so a blank one leaves that rescue mid-sentence.
            assertTrue("$b: blank accessibilityPath", advice.accessibilityPath.isNotBlank())
            assertTrue(
                "$b: accessibilityPath should name both Settings and Accessibility",
                advice.accessibilityPath.contains("Settings") &&
                    advice.accessibilityPath.contains("ccessibility"),
            )
        }
    }

    /**
     * The brands with an app-cloning feature must name it, because that note is the app admitting
     * to a hole it cannot close: a cloned app runs as another Android user, where the accessibility
     * service receives no events at all.
     */
    @Test
    fun `brands that clone apps name the feature`() {
        for (b in listOf("Samsung", "Xiaomi", "Oppo", "Vivo", "Huawei")) {
            val advice = DeviceVendor.advice(b)
            assertNotNull("$b clones apps and must say so", advice.clonedAppsFeature)
            assertTrue("$b: blank clonedAppsFeature", advice.clonedAppsFeature!!.isNotBlank())
        }
    }

    /**
     * **Every deep link must be declared, or the button silently goes nowhere.**
     *
     * Android 11+ filters an explicit component exactly as it filters a query: a `startActivity`
     * on a package the manifest has not declared throws, and `openAutostart` falls through to the
     * app-details page — so the button opens somewhere other than what its own label says, with
     * nothing to indicate it. That was true of the MIUI link for this app's entire life.
     *
     * This can only pin the Kotlin half; a JVM test cannot read AndroidManifest.xml. So it is a
     * checklist, not a proof: **adding a brand means editing two files**, and if this test fails,
     * the missing entry is almost certainly the manifest one.
     */
    @Test
    fun `every deep link package is declared`() {
        for (b in listOf("Xiaomi", "Samsung", "Huawei", "Oppo", "Vivo")) {
            for ((pkg, _) in DeviceVendor.advice(b).deepLinks) {
                assertTrue(
                    "$b deep-links to $pkg, which is not in DECLARED_KEEP_ALIVE_PACKAGES — and " +
                        "must also be a <package> entry in AndroidManifest.xml's <queries>",
                    pkg in DeviceVendor.DECLARED_KEEP_ALIVE_PACKAGES,
                )
            }
        }
    }

    /** The setting Samsung ships switched on is the whole reason this file exists. */
    @Test
    fun `Samsung advice points at the sleeping-apps setting`() {
        val desc = DeviceVendor.advice("samsung").keepAliveDesc.lowercase()
        assertTrue("Samsung advice must mention sleep", desc.contains("sleep"))
    }

    /**
     * The reported failure: switching to Second Space and back leaves the watcher dead with
     * Android's toggle still reading "on". Xiaomi must name it — that is the owner's own phone.
     */
    @Test
    fun `Xiaomi warns about switching spaces`() {
        val warning = DeviceVendor.advice("xiaomi").spacesWarning
        assertNotNull("Xiaomi must warn about Second Space", warning)
        assertTrue(
            "the warning must name the feature the owner uses",
            warning!!.contains("Second Space"),
        )
    }

    /**
     * **And it must admit it is not a cure.** Locking the app in Recents makes a space switch
     * kill it less often; nothing an app can set survives the whole space being stopped. Advice
     * that implies otherwise is the kind of promise this project has had to walk back before,
     * and it would leave the owner trusting a blocker that isn't running.
     */
    @Test
    fun `the spaces warning does not promise a cure`() {
        for (b in listOf("Xiaomi", "Samsung")) {
            val warning = DeviceVendor.advice(b).spacesWarning!!.lowercase()
            assertTrue(
                "$b's warning must say it only reduces the problem",
                warning.contains("less often") || warning.contains("rarer"),
            )
        }
    }

    /** The generic fallback promises nothing, here as everywhere else: naming a feature a phone
     *  may not have reads as a bug. */
    @Test
    fun `the generic fallback has no spaces warning`() {
        assertNull(DeviceVendor.advice("Pixel").spacesWarning)
        assertNull(DeviceVendor.advice(null).spacesWarning)
    }
}
