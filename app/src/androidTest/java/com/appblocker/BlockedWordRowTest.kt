package com.appblocker

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblocker.data.SiteWord
import com.appblocker.ui.BlockedWordRow
import com.appblocker.ui.theme.AppBlockerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one word Strict Mode can let go of, drawn.
 *
 * The rule itself is unit-tested in `StrictEditsTest`; what cannot be tested there is whether the
 * owner can actually *reach* it. This row is what turns the rule into a tap, and it introduces the
 * two shapes this project keeps shipping broken: a second line under a list row, and an
 * AlertDialog with three paragraphs and two buttons (v1.113-v1.115, and the block screens whose
 * "Got it" had been pushed off the bottom). At a large system font both are exactly where a
 * clipped explanation or an unreachable button would hide.
 */
@RunWith(AndroidJUnit4::class)
class BlockedWordRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val covering = SiteWord("instagram", "Instagram")
    private var removed = 0

    /** The width a row really gets: a 360dp phone inside the screen's 16dp side padding. */
    private fun setRow(
        word: String = "instagram",
        covering: SiteWord? = this.covering,
        strictActive: Boolean = true,
        fontScale: Float = 1f,
    ) {
        removed = 0
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                AppBlockerTheme(darkTheme = true) {
                    Box(Modifier.width(328.dp)) {
                        BlockedWordRow(word, covering, strictActive) { removed++ }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Why this word is removable and the others aren't has to be visible, not inferred. */
    @Test
    fun aCoveredWordSaysWhoIsCoveringIt() {
        setRow()
        compose.onNodeWithText("instagram").assertIsDisplayed()
        compose.onNodeWithText("Instagram is blocked, so its website is too").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove").assertIsEnabled()
    }

    /** The whole point: during a session an uncovered word stays put, with no hint and no bin. */
    @Test
    fun anUncoveredWordStaysLockedDuringASession() {
        setRow(word = "casino", covering = null)
        compose.onNodeWithText("casino").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove").assertIsNotEnabled()
    }

    /** Mid-session the removal is explained first — and "Keep it" really keeps it. */
    @Test
    fun removingDuringASessionAsksFirst() {
        setRow()
        compose.onNodeWithContentDescription("Remove").performClick()
        compose.onNodeWithText("Remove “instagram”?").assertIsDisplayed()
        compose.onNodeWithText("Keep it").performClick()
        assertEquals(0, removed)

        compose.onNodeWithContentDescription("Remove").performClick()
        compose.onNodeWithText("Remove").performClick()
        assertEquals(1, removed)
    }

    /** Outside a session nothing changed: the bin still removes on the first tap. */
    @Test
    fun outsideASessionTheBinIsStillOneTap() {
        setRow(strictActive = false)
        compose.onNodeWithContentDescription("Remove").performClick()
        assertEquals(1, removed)
    }

    /**
     * The regression this file exists for. At the font size the owner runs — and beyond — the
     * dialog must still show both buttons and the sentence that says what the removal costs.
     * A dialog whose text slot cannot scroll pushes its buttons off a short screen.
     */
    @Test
    fun theDialogSurvivesALargeSystemFont() {
        setRow(fontScale = 2f)
        compose.onNodeWithText("Instagram is blocked, so its website is too").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove").performClick()
        compose.onNodeWithText("Remove “instagram”?").assertIsDisplayed()
        compose.onNodeWithText("Remove").assertIsDisplayed()
        compose.onNodeWithText("Keep it").assertIsDisplayed()
    }
}
