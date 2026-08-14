package com.appblocker

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblocker.data.DeviceVendor
import com.appblocker.ui.DIAGNOSTICS_PHONE_TAG
import com.appblocker.ui.DiagnosticsScreen
import com.appblocker.ui.PermissionsScreen
import com.appblocker.ui.RestrictedSettingsNote
import com.appblocker.ui.theme.AppBlockerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Setup & permissions, drawn for real — the screen that just gained a per-brand advice card, a
 * cloned-apps note and (on Android 13+ sideloads) the restricted-settings explainer.
 *
 * **Why on a device.** Two of those are new blocks of text on a screen that already ran to a full
 * scroll, and the thing this app keeps shipping is content that measures wrong once it is actually
 * laid out: a tile clipped at a large font (v1.54), a button under the keyboard, a "Got it" pushed
 * off the bottom. None of it is visible to a JVM test.
 *
 * **It also proves the fallback the owner cannot see.** The emulator reports a manufacturer that
 * matches no brand, so this run renders the *generic* advice — the path a Pixel, a Nothing phone
 * or anything unrecognised gets. Asserting against `DeviceVendor.advice()` rather than a literal
 * keeps that true wherever the test runs, including on the owner's Xiaomi.
 */
@RunWith(AndroidJUnit4::class)
class SetupAdviceTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setScreen(scale: Float) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) {
                AppBlockerTheme(darkTheme = true) {
                    PermissionsScreen(onBack = {})
                }
            }
        }
    }

    /**
     * The keep-alive card is the one instruction that stops an OEM battery manager switching
     * blocking off days later, so it has to survive the font sizes people actually use — 2.0 is
     * the top of Android's slider, and the owner already runs larger than default.
     */
    @Test
    fun theKeepAliveCardIsReadableAtTheLargestFont() {
        setScreen(scale = 2f)
        val vendor = DeviceVendor.advice()

        compose.onNodeWithText(vendor.keepAliveLabel).performScrollTo().assertIsDisplayed()
    }

    /**
     * Everything below the new cards must still be reachable. The test alert lives at the very
     * bottom of the scroll, so if added content ever breaks the scrolling column — the failure
     * mode that put a Grant button off-screen once already — this is what notices.
     */
    @Test
    fun theBottomOfTheScreenIsStillReachable() {
        setScreen(scale = 1f)

        compose.onNodeWithText("Send a test alert").performScrollTo().assertIsDisplayed()
    }

    /**
     * The "This phone" section, which exists so a stranger on an unfamiliar phone can answer the
     * questions we had to guess at. On the emulator this exercises the real `ACTION_DELETE`
     * resolution and the unrecognised-brand path — the fallback the owner's own phone never takes.
     *
     * Asserting the heading rather than a row: which rows appear depends on what the device
     * resolves, and a test that demanded a particular verdict would be asserting the emulator's
     * configuration instead of our code.
     */
    @Test
    fun theThisPhoneSectionRenders() {
        compose.setContent {
            AppBlockerTheme(darkTheme = true) { DiagnosticsScreen(onBack = {}) }
        }

        compose.onNodeWithTag(DIAGNOSTICS_PHONE_TAG).performScrollTo().assertIsDisplayed()
    }

    /**
     * The Android 13 explainer, composed on its own because the screen only shows it on a
     * sideloaded build whose accessibility is still off — a combination this run cannot arrange.
     * Its wording is what a stuck user reads, so at minimum it must render whole at a large font.
     */
    @Test
    fun theRestrictedSettingsNoteRendersAtALargeFont() {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 1.5f)) {
                AppBlockerTheme(darkTheme = true) { RestrictedSettingsNote() }
            }
        }

        compose.onNodeWithText("Accessibility toggle greyed out?").assertIsDisplayed()
    }
}
