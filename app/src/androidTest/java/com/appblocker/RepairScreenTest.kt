package com.appblocker

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblocker.ui.REPAIR_BUTTON_TAG
import com.appblocker.ui.REPAIR_LIST_TAG
import com.appblocker.ui.REPAIR_RECORD_TAG
import com.appblocker.ui.REPAIR_SHORTCUT_TAG
import com.appblocker.ui.REPAIR_STATUS_TAG
import com.appblocker.ui.REPAIR_STEPS_TAG
import com.appblocker.ui.RepairScreen
import com.appblocker.ui.theme.AppBlockerTheme
import org.junit.Assume.assumeTrue
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
 * **Every test here states the branch it means.** The unhealthy branch is the one that matters —
 * it holds the steps and the button — and this class used to reach it by accident, relying on the
 * emulator's accessibility service not running. That is a test that passes *because the app is
 * broken on the machine running it*: on the first real Galaxy, where blocking genuinely worked,
 * the screen drew its healthy branch and three tests failed with nothing wrong anywhere. The
 * branch is now passed in through `healthyOverride`.
 */
@RunWith(AndroidJUnit4::class)
class RepairScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * The window this run actually got, in dp — see the fuller note in `AccountScreenTest`. A case
     * that needs more height than the window has measures the window instead, silently.
     */
    private val windowHeightDp: Int
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.configuration.screenHeightDp

    /** Skip rather than measure the wrong viewport. */
    private fun assumeWindowFits(height: Dp) = assumeTrue(
        "this window is ${windowHeightDp}dp tall, too short to host the " +
            "${height.value.toInt()}dp viewport this case is about",
        windowHeightDp >= height.value,
    )

    /** [healthy] false = the branch with the steps and the fix button, which is why this screen exists. */
    private fun setScreen(scale: Float, healthy: Boolean = false) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) {
                AppBlockerTheme(darkTheme = true) {
                    RepairScreen(onBack = {}, healthyOverride = healthy)
                }
            }
        }
    }

    /**
     * The one control that ends the problem. 2.0 is the top of Android's font slider and the
     * owner already runs above default, so "readable at 2×" is the real bar, not a stress test.
     */
    /**
     * ⚠️ **The fix is at the top, and this is what stops it drifting back down.**
     *
     * Reported 30 Aug 2026: *"sometimes when the app guides you to the accessibility there is a lot
     * of talk a lot of unneeded one can we make it less"*. This screen had grown to ~460 words with
     * roughly **200 of them above the fix button** — on the one screen someone opens when they want
     * blocking back in seconds. It happened one justified paragraph at a time; nobody ever added up
     * the total.
     *
     * The explanations now live behind `ExpandableNote` headings *below* the steps. Layout is what
     * fixed that, and layout is exactly what nothing was checking — so this asserts the button is
     * on screen at default font **with no scrolling at all**. Put two hundred words back above it
     * and this is what goes red.
     *
     * `GuidanceLengthTest` is the other half, capping the words themselves.
     */
    @Test
    fun theFixButtonIsOnScreenWithoutScrolling() {
        // A phone in portrait. A short landscape window is allowed to cost a scroll — that trade is
        // made by the case below, which never skips.
        assumeWindowFits(600.dp)
        setScreen(scale = 1f)

        compose.onNodeWithTag(REPAIR_BUTTON_TAG).assertIsDisplayed()
    }

    @Test
    fun theFixButtonIsReachableAtTheLargestFont() {
        // Being *on screen* at 2x font is a phone-portrait contract: 384dp of height — a phone on
        // its side — cannot show a button below a status card and four numbered steps at double
        // size, and that is the window refusing rather than the screen failing. The part that must
        // hold in every window is asserted by the case below, which never skips.
        assumeWindowFits(600.dp)
        setScreen(scale = 2f)

        compose.onNodeWithTag(REPAIR_LIST_TAG)
            .performScrollToNode(hasTestTag(REPAIR_BUTTON_TAG))
        compose.onNodeWithTag(REPAIR_BUTTON_TAG).assertIsDisplayed()
    }

    /**
     * **Whatever the window, the button must never become unreachable.** Short windows are allowed
     * to cost scrolling — the same trade the friction gate and the onboarding wizard make — but the
     * one control that ends the problem must still be there, still a real tap target, and still
     * something the list will scroll to. This one has no viewport assumption, so it runs on the
     * sideways phone that started all this.
     */
    @Test
    fun theFixButtonStaysReachableInAnyWindow() {
        setScreen(scale = 2f)

        compose.onNodeWithTag(REPAIR_LIST_TAG)
            .performScrollToNode(hasTestTag(REPAIR_BUTTON_TAG))
        compose.onNodeWithTag(REPAIR_BUTTON_TAG).assertExists().assertHeightIsAtLeast(40.dp)
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

    /**
     * **The shortcut card is now drawn from two different places**, and exactly one of them runs:
     * directly under the fix steps while blocking is down, and on its own when it is healthy. That
     * is a branch, and a branch is how a card silently goes missing — so both sides are asserted
     * rather than whichever one happened to be in front of me.
     *
     * It earns the promotion. Android will never let the app switch its own blocking back on, so
     * the volume-key shortcut is the only thing that turns this repair from a hunt through
     * Settings into two button-holds — and it used to sit at the very bottom of this screen,
     * below the explanation of why the app cannot help itself.
     */
    @Test
    fun theShortcutIsReachableWhileBlockingIsDown() {
        setScreen(scale = 1.5f, healthy = false)

        compose.onNodeWithTag(REPAIR_LIST_TAG)
            .performScrollToNode(hasTestTag(REPAIR_SHORTCUT_TAG))
        compose.onNodeWithTag(REPAIR_SHORTCUT_TAG).assertIsDisplayed()
    }

    /** The other side of that branch: setting it up *before* the next outage is the whole point,
     *  and the healthy screen is the only place anyone would ever do that calmly. */
    @Test
    fun theShortcutIsStillThereWhenBlockingIsHealthy() {
        setScreen(scale = 1.5f, healthy = true)

        compose.onNodeWithTag(REPAIR_LIST_TAG)
            .performScrollToNode(hasTestTag(REPAIR_SHORTCUT_TAG))
        compose.onNodeWithTag(REPAIR_SHORTCUT_TAG).assertIsDisplayed()
    }

    /**
     * The card reporting what this phone has actually recorded. Its text varies — a first outage
     * reads differently from the twentieth — so what is pinned is that the card is drawn and
     * reachable at a large font, not which of its sentences ran.
     */
    @Test
    fun theRecordCardIsReachable() {
        setScreen(scale = 1.5f, healthy = false)

        compose.onNodeWithTag(REPAIR_LIST_TAG)
            .performScrollToNode(hasTestTag(REPAIR_RECORD_TAG))
        compose.onNodeWithTag(REPAIR_RECORD_TAG).assertIsDisplayed()
    }
}
