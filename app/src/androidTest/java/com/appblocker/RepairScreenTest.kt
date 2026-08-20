package com.appblocker

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblocker.ui.REPAIR_BUTTON_TAG
import com.appblocker.ui.REPAIR_LIST_TAG
import com.appblocker.ui.REPAIR_STATUS_TAG
import com.appblocker.ui.REPAIR_STEPS_TAG
import com.appblocker.ui.RepairScreen
import com.appblocker.ui.theme.AppBlockerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The repair screen, drawn for real.
 *
 * **Why this one has to run on a device.** It is the screen the owner reaches from a notification
 * at the moment nothing is being blocked, and its whole value is one button plus four numbered
 * steps. If the button sits below the fold on a large system font, the screen has failed at
 * exactly the moment it exists for — and that is the defect this project has now shipped three
 * separate times (a tile clipped at a large font, a Grant button under the keyboard, a "Got it"
 * pushed off the bottom of the block screen). None of it is visible to a JVM test.
 *
 * **Scrolling is done with `performScrollToNode` on the list, not `performScrollTo` on the node.**
 * A `LazyColumn` does not compose what is off screen, so the node genuinely does not exist to be
 * scrolled to — which is how the first run of this test failed, and is worth keeping written down
 * because `SetupAdviceTest` next door uses the other form correctly on a `verticalScroll` column.
 *
 * The emulator's accessibility service is not running during the test, so the screen renders its
 * unhealthy branch — which is the branch that matters.
 */
@RunWith(AndroidJUnit4::class)
class RepairScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setScreen(scale: Float) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) {
                AppBlockerTheme(darkTheme = true) {
                    RepairScreen(onBack = {})
                }
            }
        }
    }

    /**
     * The one control that ends the problem. 2.0 is the top of Android's font slider and the
     * owner already runs above default, so "readable at 2×" is the real bar, not a stress test.
     */
    @Test
    fun theFixButtonIsReachableAtTheLargestFont() {
        setScreen(scale = 2f)

        compose.onNodeWithTag(REPAIR_LIST_TAG)
            .performScrollToNode(hasTestTag(REPAIR_BUTTON_TAG))
        compose.onNodeWithTag(REPAIR_BUTTON_TAG).assertIsDisplayed()
    }

    /** The steps are the fallback for when the deep link lands on the plain list instead of our
     *  own page, so they have to be legible in their own right — including the last one. */
    @Test
    fun everyStepIsShown() {
        setScreen(scale = 1.5f)

        compose.onNodeWithTag(REPAIR_LIST_TAG)
            .performScrollToNode(hasTestTag(REPAIR_STEPS_TAG))
        compose.onNodeWithText("Turn AppBlocker OFF.").assertIsDisplayed()
        compose.onNodeWithText("Turn it ON again, and accept the prompt.").assertIsDisplayed()
    }

    /**
     * The status card is the answer to "did that work?", and it is the first thing on the screen —
     * so it must be visible without scrolling at any font size. A confirmation the user has to go
     * looking for is not a confirmation.
     */
    @Test
    fun theStatusCardIsVisibleWithoutScrolling() {
        setScreen(scale = 2f)

        compose.onNodeWithTag(REPAIR_STATUS_TAG).assertIsDisplayed()
    }

    /** Everything below the fix — the shortcut tip and the per-brand prevention advice — must
     *  still be reachable, the same guard SetupAdviceTest keeps on its own scrolling column. */
    @Test
    fun theBottomOfTheScreenIsStillReachable() {
        setScreen(scale = 1f)

        compose.onNodeWithTag(REPAIR_LIST_TAG)
            .performScrollToNode(hasText("A much faster way to do it"))
        compose.onNodeWithText("A much faster way to do it").assertIsDisplayed()
    }
}
