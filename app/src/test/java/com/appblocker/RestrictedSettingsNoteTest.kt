package com.appblocker

import android.os.Build
import com.appblocker.ui.needsRestrictedSettingsNote
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the app explains Android 13's "Allow restricted settings".
 *
 * **The dead end it exists for.** On Android 13+, an app installed from a browser or a file
 * manager cannot have Accessibility switched on until the user opens App info ▸ ⋮ ▸ *Allow
 * restricted settings*. Android greys the toggle out and explains nothing. The onboarding wizard
 * sends every new user of the sideloaded build to exactly that screen, so without this note the
 * first thing a new user meets is a switch that won't move — and, like the v1.127 guard bug, none
 * of them would report it. They would decide the app is broken and delete it.
 *
 * The condition is three ANDs and is still worth pinning, because two of them are what stop it
 * becoming a permanent banner on a phone it cannot apply to.
 */
class RestrictedSettingsNoteTest {

    private val tiramisu = Build.VERSION_CODES.TIRAMISU

    @Test
    fun `sideloaded, Android 13+, not yet granted - show it`() {
        assertTrue(needsRestrictedSettingsNote(tiramisu, true, accessibilityGranted = false))
        assertTrue(needsRestrictedSettingsNote(35, true, accessibilityGranted = false))
    }

    /** Self-clearing: the moment it stops being the user's problem, it stops being on screen. */
    @Test
    fun `once accessibility is on, it is gone`() {
        assertFalse(needsRestrictedSettingsNote(tiramisu, true, accessibilityGranted = true))
    }

    /** A Play install is not restricted, so the note would send that user on an errand. */
    @Test
    fun `the Play build never shows it`() {
        assertFalse(needsRestrictedSettingsNote(tiramisu, false, accessibilityGranted = false))
    }

    /** The restriction did not exist before Android 13; the toggle simply works there. */
    @Test
    fun `phones older than Android 13 never show it`() {
        assertFalse(needsRestrictedSettingsNote(tiramisu - 1, true, accessibilityGranted = false))
        assertFalse(needsRestrictedSettingsNote(24, true, accessibilityGranted = false))
    }
}
