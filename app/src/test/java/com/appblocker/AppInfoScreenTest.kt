package com.appblocker

import com.appblocker.service.AppInfoScreen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The App-info matcher behind the Force-stop guard (Strict, and since invariant 89 the everyday guard).
 *
 * Guarding this page is the change the owner asked for after reaching Force stop mid-session, and
 * it is also the change most likely to go wrong: the broad version of this guard bounced every
 * app's App-info page and the whole Accessibility section, which is what made Strict "block the
 * whole Settings" across v1.104→v1.110. So the negatives below matter more than the positive.
 */
class AppInfoScreenTest {

    private val version = "1.119"

    /** As HyperOS renders it — see the owner's screenshot. */
    private val ourAppInfo =
        "app info appblocker version: $version storage 4.33 mb network access data usage 0 b " +
            "power permissions force stop uninstall clear cache"

    @Test
    fun `our app info page is recognised by the version it prints`() {
        assertTrue(AppInfoScreen.looksLikeAppInfo("com.miui.SomeOemActivity", ourAppInfo, version))
    }

    @Test
    fun `an app info page is recognised by its activity class even without a version`() {
        assertTrue(
            AppInfoScreen.looksLikeAppInfo(
                "com.android.settings.applications.InstalledAppDetailsTop",
                "appblocker force stop uninstall",
                version,
            )
        )
    }

    /** HyperOS's App info, by the class read off the owner's phone on 16 Oct 2026 (invariant 89). */
    @Test
    fun `hyperos app info is recognised by its activity class`() {
        assertTrue(
            AppInfoScreen.looksLikeAppInfo(
                "com.miui.appmanager.ApplicationsDetailsActivity",
                "app info appblocker force stop uninstall",
                version,
            )
        )
    }

    // --- the negatives: what must NOT bounce -------------------------------------------------

    /** HyperOS's app LIST lives beside its App info page and names every app, ours included. */
    @Test
    fun `the hyperos app list is not an app info page`() {
        val appList = "manage apps appblocker appblock calculator chrome 25.3 mb 4.33 mb"
        assertFalse(
            AppInfoScreen.looksLikeAppInfo(
                "com.miui.appmanager.AppManagerMainActivity", appList, version,
            )
        )
    }

    /**
     * The Settings app LIST names every installed app, ours included, so "is AppBlocker on this
     * screen" is not enough on its own — matching it would bounce the owner out of the app list
     * for the whole session.
     */
    @Test
    fun `the settings app list is not an app info page`() {
        val appList = "apps appblocker appblock calculator calendar camera chrome clock contacts"
        assertFalse(
            AppInfoScreen.looksLikeAppInfo(
                "com.android.settings.applications.ManageApplications", appList, version,
            )
        )
    }

    @Test
    fun `another app's app info page does not carry our version`() {
        val other = "app info calculator version: 8.2.1 storage force stop uninstall"
        assertFalse(AppInfoScreen.looksLikeAppInfo("com.miui.SomeOemActivity", other, version))
    }

    /** A generic container shared by many Settings pages must never match on class alone. */
    @Test
    fun `a shared settings container is not enough`() {
        assertFalse(
            AppInfoScreen.looksLikeAppInfo(
                "com.android.settings.spa.SpaActivity", "battery no restrictions", version,
            )
        )
    }

    @Test
    fun `an unreadable screen is not an app info page`() {
        assertFalse(AppInfoScreen.looksLikeAppInfo("com.miui.SomeOemActivity", "", version))
        assertFalse(AppInfoScreen.looksLikeAppInfo("", "   ", version))
    }

    /** A blank version must not turn `text.contains("")` into "every screen matches". */
    @Test
    fun `a missing version never matches everything`() {
        assertFalse(AppInfoScreen.looksLikeAppInfo("com.miui.SomeOemActivity", "anything", ""))
    }
}
