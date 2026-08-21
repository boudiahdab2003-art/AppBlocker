package com.appblocker

import com.appblocker.service.GuardPackages
import com.appblocker.service.namesAnActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The package lists behind the off-switch guard.
 *
 * These sat inside the watcher, which has no test coverage and cannot have any as written — and
 * between them they quietly limited the uninstall guard to three OEMs' installers. On a Samsung,
 * `uninstallConfirmation` saw a package it didn't recognise, answered "not a threat", and the
 * uninstall went through mid-Strict-session with nothing on screen to say the guard had been
 * skipped. That is the invisible under-block from docs/BLOCKING_INVARIANTS.md, shipped by a
 * vendor list rather than by logic.
 */
class GuardPackagesTest {

    /**
     * **The rule that made extracting these worth doing.** `handleSettingsGuard` returns early for
     * any package outside [GuardPackages.GUARD], so `uninstallConfirmation` — which only ever runs
     * after that check — can never see an installer that isn't also in GUARD. An entry added to
     * INSTALLERS alone is dead code that looks like protection.
     */
    @Test
    fun `every installer is also a guarded package`() {
        val orphans = GuardPackages.INSTALLERS - GuardPackages.GUARD
        assertEquals(
            "These installers can never be reached: handleSettingsGuard returns early for any " +
                "package outside GUARD, so an installer missing from it is dead code",
            emptySet<String>(), orphans,
        )
    }

    /** The AOSP/Google pair, which is what most phones actually use. */
    @Test
    fun `the stock installers are covered`() {
        assertTrue(GuardPackages.INSTALLERS.contains("com.android.packageinstaller"))
        assertTrue(GuardPackages.INSTALLERS.contains("com.google.android.packageinstaller"))
    }

    /**
     * The gap this change closes. Named one by one rather than by count, so deleting Samsung's
     * entry to "tidy up" fails loudly instead of leaving that phone silently less protected.
     */
    @Test
    fun `the OEM installers are covered`() {
        for (pkg in listOf(
            "com.miui.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.oppo.packageinstaller",
            "com.oplus.packageinstaller",
            "com.vivo.packageinstaller",
            "com.huawei.packageinstaller",
        )) {
            assertTrue("Missing OEM installer: $pkg", GuardPackages.INSTALLERS.contains(pkg))
        }
    }

    /** System Settings hosts Accessibility, device admin and App info on every phone worth naming. */
    @Test
    fun `system settings is guarded`() {
        assertTrue(GuardPackages.GUARD.contains("com.android.settings"))
    }

    /**
     * The deliberate absences, pinned so a future sweep doesn't "complete the set" by adding the
     * OEM battery centres. None of them shows our accessibility page, our App-info page or a
     * device-admin screen; adding them would expose `deviceAdminRemoval`'s text fallback — which
     * fires on "deactivate" beside our name — to pages that are nothing but app names and toggles.
     * Wrong bounces are this guard's entire history (v1.104→v1.110). Add one only with a
     * screenshot showing a real off-switch on it, and delete the entry here when you do.
     */
    @Test
    fun `OEM battery centres are deliberately not guarded`() {
        for (pkg in listOf(
            "com.samsung.android.lool",   // Samsung Device Care
            "com.coloros.safecenter",     // ColorOS
            "com.huawei.systemmanager",   // Huawei Phone Manager
        )) {
            assertTrue(
                "$pkg was added to GUARD. That is a decision, not a tidy-up — read the KDoc in " +
                    "GuardPackages before removing this assertion",
                !GuardPackages.GUARD.contains(pkg),
            )
        }
    }

    // ---- the other half of report #6: which class names mean anything ------------------

    /**
     * `uninstallConfirmation` tells an install from an uninstall by the activity's name, because
     * that name is the one thing on the screen no OEM translates. But only a window-state event
     * carries an activity — a content or scroll event carries the *view* that changed, and three
     * of the guard's four call sites are those. `android.widget.FrameLayout` is not evidence that
     * this is not an install; it is not evidence of anything.
     */
    @Test
    fun `an installer activity names itself`() {
        assertTrue(namesAnActivity("com.miui.packageInstaller.ui.InstallProgressActivity"))
        assertTrue(namesAnActivity("com.android.packageinstaller.UninstallerActivity"))
        assertTrue(namesAnActivity("com.android.settings.SubSettings"))
    }

    /** The classes a content or scroll event actually carries. Silence, not a "no". */
    @Test
    fun `a view class names nothing we can reason about`() {
        assertFalse(namesAnActivity("android.widget.FrameLayout"))
        assertFalse(namesAnActivity("android.widget.TextView"))
        assertFalse(namesAnActivity("android.view.ViewGroup"))
        assertFalse(namesAnActivity("androidx.recyclerview.widget.RecyclerView"))
        assertFalse(namesAnActivity("androidx.viewpager.widget.ViewPager"))
        assertFalse(namesAnActivity("com.google.android.material.appbar.AppBarLayout"))
        assertFalse(namesAnActivity("android.webkit.WebView"))
        assertFalse(namesAnActivity(""))
        assertFalse(namesAnActivity("   "))
    }

    /** Prefix, not substring: an app whose own package merely contains one of these still names
     *  an activity, or a vendor's spelling could switch the check off. */
    @Test
    fun `only the framework prefixes are discounted`() {
        assertTrue(namesAnActivity("com.example.android.widget.SomethingActivity"))
        assertTrue(namesAnActivity("com.vivo.packageinstaller.PackageInstallerActivity"))
    }
}
