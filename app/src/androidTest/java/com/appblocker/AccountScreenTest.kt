package com.appblocker

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblocker.data.SettingsStore
import com.appblocker.ui.ACCOUNT_NAME_FIELD_TAG
import com.appblocker.ui.ACCOUNT_SAVE_TAG
import com.appblocker.ui.AccountScreen
import com.appblocker.ui.ONBOARDING_NAME_CONTINUE_TAG
import com.appblocker.ui.ONBOARDING_NAME_FIELD_TAG
import com.appblocker.ui.OnboardingScreen
import com.appblocker.ui.theme.AppBlockerTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two screens where somebody types their name, measured on a device.
 *
 * Both are text-entry screens with an action button below the field, which is the exact shape that
 * has cost this app the most releases. `FrictionGateTest` exists for the five (v1.113 → v1.117) it
 * took to make the typed gate usable, and the rule they produced is written into
 * `docs/BLOCKING_INVARIANTS.md`: **the element a screen exists for must never be the flexible
 * one.** A JVM test structurally cannot catch this — the defect does not exist until something has
 * been laid out against a real width, height, density and font scale.
 *
 * The specific fault being guarded is why the name is edited on a full screen at all. It used to be
 * an `AlertDialog` with a text field in it, and dialog windows report **zero insets** while drawing
 * edge-to-edge on the owner's HyperOS phone (`CLAUDE.md`, and the note on `DurationPickerDialog`) —
 * so the keyboard draws straight over a dialog's field with nothing to push it up. Moving to a full
 * screen only helps if the layout actually holds when the space runs out, and that is what these
 * assert: **squeeze the screen, and the field and the button are still there.**
 *
 * The short heights reproduce the *effect* of the keyboard without needing a real IME — the fault
 * was always about how little height the screen was handed. 1.5× font is the other way a screen
 * runs out of room, and the owner runs a large system font.
 */
@RunWith(AndroidJUnit4::class)
class AccountScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    /** This composes against the real prefs on the test device — put the owner's name back. */
    private var savedName: String? = null

    @Before
    fun rememberOwnersName() {
        savedName = SettingsStore.userName(context)
        SettingsStore.setUserName(context, "Test Person")
    }

    @After
    fun restoreOwnersName() {
        savedName?.let { SettingsStore.setUserName(context, it) }
    }

    /**
     * Hosts a screen at a caller-controlled height, so one screen can be measured at several
     * viewports. [height] of null means "the whole window" — what the screen normally gets.
     */
    private fun setScreen(
        height: Dp?,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                AppBlockerTheme(darkTheme = true) {
                    val modifier = if (height == null) Modifier.fillMaxWidth()
                    else Modifier.fillMaxWidth().height(height)
                    Box(modifier) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    // ---- Profile ▸ Your profile ------------------------------------------------------------

    /** Given the window, the whole page is usable with nothing hidden. */
    @Test
    fun givenTheWindow_nameFieldAndSaveAreBothVisible() {
        setScreen(height = null) { AccountScreen(onBack = {}) }

        compose.onNodeWithTag(ACCOUNT_NAME_FIELD_TAG).assertIsDisplayed()
        compose.onNodeWithTag(ACCOUNT_SAVE_TAG).assertIsDisplayed()
    }

    /**
     * **The dialog bug, in its new home.** Squeezed to roughly what the keyboard leaves, the field
     * and Save must both still be on screen — that is the entire reason this is not a Dialog. Put
     * the Save button inside the scrolling column and this fails.
     */
    @Test
    fun nameFieldAndSaveSurviveTheKeyboard() {
        setScreen(height = 320.dp) { AccountScreen(onBack = {}) }

        compose.onNodeWithTag(ACCOUNT_NAME_FIELD_TAG).assertIsDisplayed()
        compose.onNodeWithTag(ACCOUNT_SAVE_TAG).assertIsDisplayed().assertHeightIsAtLeast(40.dp)
    }

    /** The owner runs a large system font; that is the other way this screen runs out of room. */
    @Test
    fun saveStaysOnScreenAtALargeSystemFont() {
        setScreen(height = 360.dp, fontScale = 1.5f) { AccountScreen(onBack = {}) }

        compose.onNodeWithTag(ACCOUNT_SAVE_TAG).assertIsDisplayed().assertHeightIsAtLeast(40.dp)
    }

    // ---- The same question, asked during first-run setup ------------------------------------

    /** The wizard opens on Welcome; the name step is one tap further in. */
    private fun openWizardNameStep(height: Dp, fontScale: Float = 1f) {
        setScreen(height = height, fontScale = fontScale) { OnboardingScreen(onDone = {}) }
        compose.onNodeWithText("Get started").performClick()
        compose.waitForIdle()
    }

    /**
     * **What the squeeze is allowed to cost, and what it is not.**
     *
     * `StepScaffold` puts the step's content in a scroller and pins the footer below it, so on a
     * viewport this short the field legitimately sits below the fold — that is the trade the
     * scaffold exists to make, and `FrictionGateTest` states the same rule: squeezing must cost
     * scrolling, never the element the screen exists for. So the field is asserted **reachable**
     * (scroll to it, and it really is on screen once there), while the buttons are asserted
     * **displayed**, because they are pinned and must never be the thing that scrolls away.
     *
     * The first version of this test asserted the field was displayed here and failed on the
     * emulator. It was the assertion that was wrong, not the screen — but it did surface a real
     * problem, fixed in `NameStep`: the field used to sit below a four-line paragraph, so on a
     * short screen a new user saw a question with no visible answer box.
     */
    @Test
    fun wizardNameStepKeepsItsFieldReachableAndItsButtonsPinnedWhenSqueezed() {
        openWizardNameStep(height = 380.dp)

        compose.onNodeWithTag(ONBOARDING_NAME_FIELD_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ONBOARDING_NAME_CONTINUE_TAG)
            .assertIsDisplayed().assertHeightIsAtLeast(40.dp)
        // Skipping has to stay pinned too: the step is optional, and a "Skip" that scrolls away
        // is a required question wearing a disguise.
        compose.onNodeWithText("Skip").assertIsDisplayed()
    }

    /**
     * At a viewport like an ordinary phone with the keyboard up, no scrolling should be needed at
     * all: the very first thing a new user is asked for must be visible along with the box to type
     * it into. This is what the field's position in `NameStep` is actually for — assert it, or the
     * paragraph creeps back above the field and nothing notices.
     */
    @Test
    fun wizardNameStepShowsItsFieldWithoutScrollingAtKeyboardHeight() {
        openWizardNameStep(height = 560.dp)

        compose.onNodeWithTag(ONBOARDING_NAME_FIELD_TAG).assertIsDisplayed()
        compose.onNodeWithTag(ONBOARDING_NAME_CONTINUE_TAG).assertIsDisplayed()
    }
}
