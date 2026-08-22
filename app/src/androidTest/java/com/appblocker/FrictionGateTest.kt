package com.appblocker

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblocker.ui.FrictionGate
import com.appblocker.ui.GATE_PARAGRAPH_TAG
import com.appblocker.ui.theme.AppBlockerTheme
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The screen that cost five consecutive releases (v1.113 → v1.117), given the regression test it
 * never had.
 *
 * Every one of those releases fixed the same shape of defect and none of them could be caught by a
 * unit test, because the defect only exists once something has been **measured**: the paragraph had
 * `weight(1f)` — not a size, but whatever is left over — so the explanation ate it, then the
 * keyboard ate what remained, and the screen whose entire job is "read this and type it" rendered
 * one clipped line of it and then none at all. `docs/BLOCKING_INVARIANTS.md` records the same lesson
 * under "A layout rule, learnt three times in one day."
 *
 * So these tests assert the rule the file now states in its own KDoc, rather than asserting that
 * pixels look a certain way: **the element a screen exists for must never be the flexible one.**
 * Squeezing the gate must cost scrolling, never the paragraph. Restore `weight(1f)` on the phrase
 * card and [paragraphKeepsItsFloorWhenSqueezed] fails.
 *
 * The heights are stated in dp and applied with a wrapper, which reproduces the *effect* of the
 * keyboard (a much shorter viewport) without needing a real IME — the failure was always about how
 * little height the gate was handed, never about the keyboard as such. That is also exactly how the
 * bug reached the owner: emitted inside a tab scaffold, the gate was handed roughly 300dp where it
 * had assumed the window.
 */
@RunWith(AndroidJUnit4::class)
class FrictionGateTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * **Stop the clock before touching this screen.**
     *
     * The gate runs a live countdown — a `LaunchedEffect` that ticks once a second for three
     * minutes and then starts a fresh attempt — so the composition is never finished changing.
     * Every Compose assertion first waits for the app to go idle, and on a CI runner's software
     * renderer it never does: the first version of this class timed out at exactly 60 seconds in
     * GitHub Actions and took the emulator down with it, while passing locally where the frames
     * were fast enough to land between ticks. A test that depends on how quickly the machine
     * draws is not a test, it is a coin toss.
     *
     * These are *layout* tests: what is measured is where things are, which the countdown does
     * not affect. Freezing the clock removes the ticking entirely and makes them deterministic.
     * Frames are then pumped by hand — see [settle].
     */
    @Before
    fun freezeTheCountdown() {
        compose.mainClock.autoAdvance = false
    }

    /** Lays the screen out once, with the clock stopped. */
    private fun settle() {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private val confirmLabel = "Start the wait"
    private val placeholder = "Type the paragraph above"

    /** The paragraph card's stated floor — see FrictionGate's `coerceIn(200.dp, 420.dp)`. */
    private val paragraphFloor = 200.dp

    /**
     * Hosts the gate at a caller-controlled height, so one composition can be re-measured at
     * several viewports. [height] of null means "the whole window", which is the gate's actual
     * contract: AppRoot composes it over everything.
     */
    /**
     * The window this run actually got, in dp. A fixed-height `Box` is clipped by it, so a case
     * that asks for a viewport taller than the window measures the window instead while its name
     * still claims the height — see the same note in `AccountScreenTest`.
     */
    private val windowHeightDp: Int
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.configuration.screenHeightDp

    /** Skip rather than measure the wrong viewport; the squeezed cases cover the short window. */
    private fun assumeWindowFits(height: Dp) = assumeTrue(
        "this window is ${windowHeightDp}dp tall, too short to host the " +
            "${height.value.toInt()}dp viewport this case is about; the squeezed cases cover it",
        windowHeightDp >= height.value,
    )

    private fun setGate(height: Dp?, fontScale: Float = 1f) {
        if (height != null) assumeWindowFits(height)
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, fontScale),
            ) {
                AppBlockerTheme(darkTheme = true) {
                    val modifier = if (height == null) Modifier.fillMaxWidth()
                    else Modifier.fillMaxWidth().height(height)
                    Box(modifier) {
                        FrictionGate(
                            title = "Turn off Prevent uninstall",
                            blurb = "Type the paragraph to ask for this to be turned off.",
                            detail = "A wait follows before anything actually changes.",
                            confirmLabel = confirmLabel,
                            onDismiss = {},
                            onConfirm = {},
                        )
                    }
                }
            }
        }
        settle()
    }

    /**
     * The gate's own contract: given the window, everything is on screen at once with no scrolling.
     * If this fails the screen has grown past the space it has, which is how v1.113 shipped.
     */
    @Test
    fun givenTheWindow_paragraphFieldAndButtonAreAllVisibleAtOnce() {
        // The gate's no-scrolling contract is a phone-portrait contract. Turn the phone on its
        // side and 384dp cannot hold a paragraph, a field and a button at once — which is not a
        // broken screen: it scrolls, with the button pinned, asserted by
        // fieldAndButtonStayReachableWhenSqueezed. Measured on a Galaxy S24 FE lying down.
        assumeWindowFits(600.dp)
        setGate(height = null)

        compose.onNodeWithTag(GATE_PARAGRAPH_TAG).assertIsDisplayed()
        compose.onNodeWithText(placeholder).assertIsDisplayed()
        compose.onNodeWithText(confirmLabel).assertIsDisplayed()
    }

    /**
     * **The v1.115 regression.** Squeezed to roughly what the keyboard leaves, the paragraph must
     * still be at least its stated floor. A `weight(1f)` here reaches zero; a computed height with
     * a clamp cannot.
     */
    @Test
    fun paragraphKeepsItsFloorWhenSqueezed() {
        setGate(height = 360.dp)

        compose.onNodeWithTag(GATE_PARAGRAPH_TAG).assertHeightIsAtLeast(paragraphFloor)
    }

    /**
     * **The v1.117 regression.** With the viewport short, the field and the button may scroll —
     * that is the intended cost — but they must still be reachable and, once scrolled to, actually
     * on screen. The owner's bug was typing into a field that was below the fold with no way to
     * bring it up.
     */
    @Test
    fun fieldAndButtonStayReachableWhenSqueezed() {
        setGate(height = 360.dp)

        // Existence and a real height, rather than scrolling to them and asserting they are
        // visible. Scrolling is animated, and with the countdown frozen (see freezeTheCountdown)
        // an animation would never run. It is also not what the bug was: everything below the
        // clock now lives in ONE scroll container, so a child of it that exists and has a real
        // height is reachable by definition. What v1.117 actually shipped was a field and a
        // button pushed below the fold of an area that could not scroll at all, and the
        // paragraph's floor above is what proves the scroller is doing its job.
        compose.onNodeWithText(placeholder).assertExists().assertHeightIsAtLeast(40.dp)
        compose.onNodeWithText(confirmLabel).assertExists().assertHeightIsAtLeast(40.dp)
    }

    /**
     * The floor holds across every viewport the gate can be handed, not just the one that was
     * debugged. A height that happens to work at 360dp and collapses at 300dp is the same bug with
     * a different trigger, and that is precisely how this screen kept coming back.
     */
    @Test
    fun paragraphNeverCollapsesAtAnyViewport() {
        var height by mutableStateOf(300.dp)
        compose.setContent {
            AppBlockerTheme(darkTheme = true) {
                Box(Modifier.fillMaxWidth().height(height)) {
                    FrictionGate(
                        title = "Turn off Prevent uninstall",
                        blurb = "Type the paragraph.",
                        detail = "A wait follows.",
                        confirmLabel = confirmLabel,
                        onDismiss = {},
                        onConfirm = {},
                    )
                }
            }
        }

        settle()

        listOf(300.dp, 420.dp, 560.dp, 700.dp, 900.dp).forEach { viewport ->
            compose.runOnUiThread { height = viewport }
            settle()
            compose.onNodeWithTag(GATE_PARAGRAPH_TAG).assertHeightIsAtLeast(paragraphFloor)
        }
    }

    /**
     * The owner runs a larger system font ([[feedback-big-fonts]] is not a preference here, it is
     * how his phone is set), and a bigger font is the other way a screen runs out of room. The
     * paragraph must survive that too — the clamp is in dp, so it should, and this is what says so.
     */
    @Test
    fun paragraphKeepsItsFloorAtALargeSystemFont() {
        setGate(height = 420.dp, fontScale = 1.5f)

        compose.onNodeWithTag(GATE_PARAGRAPH_TAG).assertHeightIsAtLeast(paragraphFloor)
        compose.onNodeWithText(confirmLabel).assertExists().assertHeightIsAtLeast(40.dp)
    }
}
