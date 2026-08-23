package com.appblocker

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblocker.data.SetupGuides
import com.appblocker.ui.SETUP_GUIDE_IMAGE_TAG
import com.appblocker.ui.SETUP_GUIDE_SHOT_TAG
import com.appblocker.ui.SETUP_GUIDE_TAG
import com.appblocker.ui.SetupGuideStrip
import com.appblocker.ui.theme.AppBlockerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The setup pictures, on a device — the half a JVM test cannot reach.
 *
 * `SetupGuidesTest` already pins the data (rings inside their images, captions present). What only
 * a real composition can answer is whether the thing is *usable*: the pictures are the only part of
 * setup a non-technical user is going to follow, so a caption clipped at a large system font or a
 * picture that pushes the instructions off the screen is the whole feature failing quietly.
 */
@RunWith(AndroidJUnit4::class)
class SetupGuideTest {

    @get:Rule
    val compose = createComposeRule()

    private val guide = SetupGuides.forPermission("accessibility", "")!!

    private fun setStrip(fontScale: Float = 1f, height: Dp? = null) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                AppBlockerTheme(darkTheme = true) {
                    val sized = if (height == null) Modifier.fillMaxWidth()
                    else Modifier.fillMaxWidth().height(height)
                    Box(sized.verticalScroll(rememberScrollState())) { SetupGuideStrip(guide) }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun everyStepOfTheGuideIsDrawn() {
        setStrip()

        compose.onNodeWithTag(SETUP_GUIDE_TAG).assertIsDisplayed()
        assertEquals(
            "one card per shot, or a picture is missing from the strip",
            guide.shots.size,
            compose.onAllNodesWithTag(SETUP_GUIDE_SHOT_TAG).fetchSemanticsNodes().size,
        )
    }

    /**
     * **The caption carries the instruction, so it is the thing that must survive.** The owner runs
     * a large system font and has asked more than once for text not to be shrunk; a caption clipped
     * to one line here would leave "Find AppBlocker in the list —" and nothing else.
     */
    @Test
    fun captionsSurviveALargeSystemFont() {
        setStrip(fontScale = 1.5f)

        for (shot in guide.shots) {
            // The first few words are enough to find the node and prove it was not dropped.
            val head = shot.caption.take(24)
            compose.onNodeWithText(head, substring = true).performScrollTo().assertIsDisplayed()
        }
    }

    /**
     * Every guide has to admit which phone it was photographed on. A Samsung owner looking at a
     * stock-Android screenshot needs that sentence, and it is the only thing standing between
     * "helpful picture" and "this app is describing a phone I do not own".
     */
    @Test
    fun theGuideSaysWhichPhoneThePicturesCameFrom() {
        setStrip()

        compose.onNodeWithText(guide.takenOn.take(30), substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    /**
     * Squeezed into a short window — a small phone, or a large display-size setting — the strip
     * must still be scrollable rather than clipped, so the last picture is reachable.
     */
    @Test
    fun theLastPictureIsReachableInAShortWindow() {
        setStrip(height = 320.dp)

        val last = guide.shots.last()
        compose.onNodeWithText(last.caption.take(24), substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    /**
     * A picture with no height would pass every assertion above while showing nothing — the
     * failure mode of a drawable that did not load, or a ContentScale that collapsed it.
     *
     * **Measured on the picture, not on the card.** The card holds the numbered caption too, and a
     * two-line caption alone is already ~60dp tall, so a card-level height check goes green with
     * the screenshot entirely missing. That was this test's first draft, and it protected nothing.
     */
    @Test
    fun thePicturesActuallyOccupySpace() {
        setStrip()

        val pictures = compose.onAllNodesWithTag(SETUP_GUIDE_IMAGE_TAG)
        assertEquals(
            "one picture per shot",
            guide.shots.size,
            pictures.fetchSemanticsNodes().size,
        )
        for (i in guide.shots.indices) {
            val h = pictures[i].getUnclippedBoundsInRoot().height
            assertTrue("picture $i is only ${h.value}dp tall — the screenshot is missing", h > 40.dp)
        }
    }
}
