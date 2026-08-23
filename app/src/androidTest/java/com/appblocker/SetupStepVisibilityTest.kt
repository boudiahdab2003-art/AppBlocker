package com.appblocker

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblocker.ui.ACCESSIBILITY_PERM
import com.appblocker.ui.EssentialStep
import com.appblocker.ui.Perm
import com.appblocker.ui.SETUP_GUIDE_IMAGE_TAG
import com.appblocker.ui.theme.AppBlockerTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Is the guidance actually in front of the user, or merely present?**
 *
 * `SetupGuideTest` proves the pictures render and stay reachable by scrolling. That is not the same
 * question as this one. A first-time user who does not scroll sees only whatever the first screenful
 * holds, and for most of this screen's life that was a large round icon, a headline, a chip and a
 * centred paragraph — with the first instruction below the fold. Someone who reads nothing and
 * scrolls nothing has to end up with blocking switched on anyway; that is the whole design goal,
 * and it is a *layout* property, so only a device can check it.
 *
 * The window is sized like the content area of an ordinary phone: a 914dp-tall screen, less the
 * system bars, the wizard's padding and its progress header.
 */
@RunWith(AndroidJUnit4::class)
class SetupStepVisibilityTest {

    @get:Rule
    val compose = createComposeRule()

    // A SHORT window on purpose: a small phone, or — much more common — an ordinary phone whose
    // owner has turned the display size up. That is the case where a step's decoration decides
    // whether the instructions are on screen at all, and it is the owner's own setting.
    private val phoneContentHeight = 560.dp

    private val accessibilityPerm = Perm(
        key = ACCESSIBILITY_PERM,
        label = "Accessibility (the blocker)",
        desc = "Required. Lets AppBlocker see which app is open and block it.",
        granted = false,
        essential = true,
        onFix = {},
    )

    private fun setStep(fontScale: Float = 1f) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                AppBlockerTheme(darkTheme = true) {
                    Box(Modifier.fillMaxWidth().height(phoneContentHeight)) {
                        EssentialStep(
                            perm = accessibilityPerm,
                            onContinue = {},
                            onSkip = {},
                            onRequestDisclosure = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * **A guard, and honestly labelled as one.** A picture must be fully on screen before anyone
     * scrolls, with no `performScrollTo` — using it here would test the opposite of the question.
     *
     * It passed before the step's decoration was trimmed as well as after, so it is not evidence
     * that the trimming helped; the "instructions below the fold" problem it was written for had
     * already been fixed by moving the four-paragraph Android-13 note below the pictures. What it
     * does earn its place doing is stopping the next person putting something big back on top.
     */
    @Test
    fun theFirstPictureIsOnScreenWithoutScrolling() {
        setStep()

        val picture = compose.onAllNodesWithTag(SETUP_GUIDE_IMAGE_TAG)[0]
        picture.assertIsDisplayed()
        // Not merely peeking over the bottom edge: the WHOLE first picture has to be there, or
        // it is a sliver of a screenshot that tells nobody anything. Unclipped bounds, so a
        // picture scrolled out of view is caught rather than reported at its clipped size.
        val bottom = picture.getUnclippedBoundsInRoot().bottom
        assertTrue(
            "the first picture ends at $bottom, past the bottom of a $phoneContentHeight window " +
                "— the step's decoration has pushed the instructions off screen",
            bottom <= phoneContentHeight,
        )
    }

    /**
     * At a large system font the pictures are allowed to be pushed down — there is only so much
     * room, and the owner runs his phone this way. What must NOT be pushed away is the line saying
     * there are instructions here at all, and that the button leaves the app.
     */
    @Test
    fun atALargeFontTheInstructionsAreStillAnnounced() {
        setStep(fontScale = 1.5f)

        compose.onNodeWithText("What to do in Settings", substring = true).assertIsDisplayed()
    }

    /**
     * **Nobody should be surprised by leaving the app.** Being thrown into Android's Settings is the
     * most disorienting moment in the product, and the step used to say nothing about it at all.
     */
    @Test
    fun theStepWarnsThatTheButtonLeavesTheApp() {
        setStep()

        // "Settings" alone matches three nodes on this screen — the description, the heading and
        // this warning. The phrase that only the warning can say is the one worth asserting.
        compose.onNodeWithText("leaves AppBlocker", substring = true).assertIsDisplayed()
    }

    /**
     * **The button has to say what pressing it does.** "Grant" is this app's word, not the
     * reader's, and it gives no hint that the next thing to happen is being moved into a
     * different app. This one does discriminate: it fails against every build before today.
     */
    @Test
    fun theButtonSaysWhatItDoes() {
        setStep()

        compose.onNodeWithText("Turn on blocking").assertIsDisplayed()
        compose.onAllNodesWithText("Grant").assertCountEquals(0)
    }
}
