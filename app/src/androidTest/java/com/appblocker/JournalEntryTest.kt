package com.appblocker

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblocker.ui.JOURNAL_DONE_TAG
import com.appblocker.ui.JOURNAL_FIELD_FLOOR
import com.appblocker.ui.JOURNAL_FIELD_TAG
import com.appblocker.ui.JournalEntryBody
import com.appblocker.ui.theme.AppBlockerTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Writing in the journal, with the keyboard up.
 *
 * **The keyboard is reproduced as a short viewport, not as a real IME.** That is how
 * `FrictionGateTest` does it and for the same reason: the failure was never about the keyboard as
 * such, it was about how little height the page was handed. A real IME on a CI emulator is a
 * timing test dressed up as a layout test.
 *
 * The property being pinned is the one this repo has now learnt three times — **the element a
 * screen exists for must never be the flexible one.** Squeezing this page has to cost scrolling,
 * never the writing area. Put `weight(1f)` back on the field and
 * [theWritingAreaKeepsItsFloorWhenSqueezed] fails.
 *
 * No ViewModel and no database here: [JournalEntryBody] is the page with its storage taken off,
 * so nothing on the device is written to or deleted by running this.
 */
@RunWith(AndroidJUnit4::class)
class JournalEntryTest {

    @get:Rule
    val compose = createComposeRule()

    /** A day far enough back that it can never collide with anything real. Only its label is
     *  drawn; nothing is read or written. */
    private val someDay = 2001001

    private val fontScale = mutableFloatStateOf(1f)

    /** Room to write in on an ordinary phone, keyboard down. */
    private val roomy = 700.dp

    /** What is left of an ordinary phone once the keyboard is up: roughly half the screen. */
    private val keyboardUp = 340.dp

    private val windowHeightDp: Int
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.configuration.screenHeightDp

    private fun show(height: Dp, scale: Float = 1f) {
        assumeTrue(
            "this window is ${windowHeightDp}dp tall, shorter than the ${height.value.toInt()}dp " +
                "viewport this case is about",
            windowHeightDp >= height.value,
        )
        fontScale.floatValue = scale
        compose.setContent {
            var draft by remember { mutableStateOf("") }
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale.floatValue)) {
                AppBlockerTheme(darkTheme = true) {
                    Box(Modifier.fillMaxWidth().height(height)) {
                        JournalEntryBody(
                            day = someDay,
                            draft = draft,
                            onDraft = { draft = it },
                            onBack = {},
                            onDelete = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * **The whole point of the page.** With the keyboard up at the owner's font size, there must
     * still be a real area to write in — not a one-line slot left over after the prompt chips
     * have wrapped onto three rows.
     */
    @Test
    fun theWritingAreaKeepsItsFloorWhenSqueezed() {
        show(keyboardUp, scale = 1.5f)

        compose.onNodeWithTag(JOURNAL_FIELD_TAG).assertHeightIsAtLeast(JOURNAL_FIELD_FLOOR)
    }

    /**
     * **And the way out is never underneath the keyboard.** Done sits in the app bar, above the
     * scrolling area, so no amount of squeezing can cover it. Move it into the page body and this
     * fails at the squeezed height.
     */
    @Test
    fun theWayOutStaysReachableWithTheKeyboardUp() {
        show(keyboardUp, scale = 1.5f)

        compose.onNodeWithTag(JOURNAL_DONE_TAG).assertIsDisplayed()
    }

    @Test
    fun theDateItIsBoundToIsOnTheScreen() {
        show(roomy)

        // The page is titled with the day it belongs to, not with "Journal". The year is
        // asserted rather than the month name, so a device in another locale still measures the
        // thing this case is about.
        compose.onNodeWithText("2001", substring = true).assertIsDisplayed()
    }

    /**
     * A prompt chip must put its heading into the page rather than replacing what is there. The
     * arithmetic is unit-tested in `JournalTextTest`; this checks the chip is actually wired to it
     * and that the result reaches the field.
     */
    @Test
    fun aPromptChipWritesItsHeadingIntoTheField() {
        show(roomy)

        compose.onNodeWithText("What helped").performClick()
        compose.waitForIdle()

        // Asserted on the field's own node: "What helped" is also the chip's label, so a plain
        // text search would pass on a chip that did nothing at all.
        compose.onNodeWithTag(JOURNAL_FIELD_TAG).assert(hasText("What helped", substring = true))
    }
}
