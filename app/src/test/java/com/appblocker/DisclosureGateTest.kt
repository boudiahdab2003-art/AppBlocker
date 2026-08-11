package com.appblocker

import com.appblocker.ui.ACCESSIBILITY_PERM
import com.appblocker.ui.needsDisclosure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule in front of the biggest permission this app asks for: **an ungranted accessibility
 * permission must be preceded by the disclosure screen.**
 *
 * The rule is two lines long and it still shipped broken, which is the point of testing it. The
 * old consent dialog was correct wherever it was used — but `BlockEditorScreen`'s "Turn on
 * protection" banner called `startActivity(ACTION_ACCESSIBILITY_SETTINGS)` directly and never
 * asked the question at all. So the app had a button that sent people to Android's Accessibility
 * page with no disclosure, appearing exactly when protection was off, which is the moment somebody
 * is most likely to press it.
 *
 * **What this test can and cannot do.** It pins the condition. It cannot see a *new* call site
 * that ignores [needsDisclosure] entirely — that was the actual failure, and no unit test reaches
 * it. The defence there is that all four doors are named in `AccessibilityDisclosureScreen`'s
 * KDoc; this test is what stops the condition itself from being quietly loosened underneath them.
 */
class DisclosureGateTest {

    @Test fun `accessibility, not yet granted, needs the disclosure first`() {
        assertTrue(needsDisclosure(ACCESSIBILITY_PERM, granted = false))
    }

    /**
     * Already granted means the button is a trip back to Settings to *review* or turn it off, and
     * consent has been given. Re-showing the disclosure there would train people to tap past it,
     * which is how a disclosure stops being one.
     */
    @Test fun `accessibility, already granted, goes straight through`() {
        assertFalse(needsDisclosure(ACCESSIBILITY_PERM, granted = true))
    }

    /**
     * Every other permission goes straight to its own settings page. Overlay, usage access and
     * battery are ordinary Android permissions with their own system prompts; only the
     * accessibility service can read the screen, and only it carries this policy.
     */
    @Test fun `other permissions are not gated`() {
        listOf("overlay", "usage", "notifications", "battery", "autostart", "location", "deviceadmin")
            .forEach { key ->
                assertFalse("$key should not be gated", needsDisclosure(key, granted = false))
                assertFalse("$key should not be gated", needsDisclosure(key, granted = true))
            }
    }

    /**
     * The key is compared as a literal string in one place and written as a literal in
     * `rememberPermissions`. If the constant and the `Perm` ever drift apart the gate silently
     * stops matching anything — the failure mode being guarded is "no disclosure", never "an
     * extra one", so it would look exactly like everything working.
     */
    @Test fun `the gated key is the one rememberPermissions actually uses`() {
        assertTrue(needsDisclosure("accessibility", granted = false))
        assertTrue("accessibility" == ACCESSIBILITY_PERM)
    }
}
