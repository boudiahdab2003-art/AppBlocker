package com.appblocker

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
// DpRect.height is an extension property, not a member.
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblocker.data.CleanStreak
import com.appblocker.ui.COUNTER_HERO_TAG
import com.appblocker.ui.COUNTER_RESET_TAG
import com.appblocker.ui.CleanCounterScreen
import com.appblocker.ui.theme.AppBlockerTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The clean counter, measured.
 *
 * Two properties only a device can check, and one of them is not about pixels at all:
 *
 * 1. **The number has to be whole and on screen without scrolling**, at the owner's font size as
 *    well as the default. Four unit blocks side by side is exactly the layout that clips at a
 *    large system font — v1.54 shipped the New-schedule tiles with their labels cut in half for
 *    that reason, and it was found from a photograph rather than from a test.
 * 2. **The day of a reset must not read as a scoreboard.** "Your longest run so far was 41 days"
 *    printed under a row of zeroes is the app kicking somebody on the worst day they will ever
 *    have with it. That rule lives in a layout condition, so this is where it can be pinned.
 *
 * **The clock is frozen throughout.** The screen ticks once a second forever; with the test clock
 * auto-advancing, the composition is never finished changing and `waitForIdle` never returns. Same
 * trap and same fix as `FrictionGateTest`, which timed out at sixty seconds in CI and took the
 * emulator with it before its clock was stopped.
 *
 * **This writes to the app's own preferences on the device under test** and clears them afterwards.
 * Rendering tests run on the emulator and in CI, never on the owner's phone.
 */
@RunWith(AndroidJUnit4::class)
class CleanCounterTest {

    @get:Rule
    val compose = createComposeRule()

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val day = 86_400_000L
    private val hour = 3_600_000L

    /** A short window: a small phone, or — far more common — an ordinary one whose owner has
     *  turned the display size up, which is the owner's own setting. */
    private val phoneWindow = 620.dp

    /** Driven as state so one composition can be re-measured at two font scales; `setContent`
     *  may only be called once per test. */
    private val fontScale = mutableFloatStateOf(1f)

    /** The window this run actually got. A fixed-height Box is clipped by it, so a case asking for
     *  a taller viewport would silently measure the window instead. */
    private val windowHeightDp: Int
        get() = ctx.resources.configuration.screenHeightDp

    @Before
    fun freezeTheTick() {
        compose.mainClock.autoAdvance = false
        fontScale.floatValue = 1f
    }

    @After
    fun forgetTheSeededCount() {
        ctx.getSharedPreferences("recovery", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** Twelve days in, with a longer run behind it — the ordinary case. Seeded through the real
     *  API rather than by writing preference keys, so the test cannot drift from the store. */
    private fun seedEstablishedRun() {
        val now = System.currentTimeMillis()
        CleanStreak.setStart(ctx, now - 41 * day, now)
        // `now = at`, so the reset is recorded as having happened then — not as one just tapped,
        // which would leave the undo card standing twelve days later.
        CleanStreak.relapse(ctx, at = now - 12 * day, now = now - 12 * day)
    }

    /** A reset two hours ago, with forty-one days behind it: the worst moment to open this. */
    private fun seedFreshReset() {
        val now = System.currentTimeMillis()
        CleanStreak.setStart(ctx, now - 41 * day, now)
        CleanStreak.relapse(ctx, at = now - 2 * hour, now = now - 2 * hour)
    }

    /** [height] of null means the whole window, which is the screen's real contract. */
    private fun show(height: Dp? = phoneWindow) {
        if (height != null) {
            assumeTrue(
                "this window is ${windowHeightDp}dp tall, shorter than the " +
                    "${height.value.toInt()}dp viewport this case is about",
                windowHeightDp >= height.value,
            )
        }
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale.floatValue)) {
                AppBlockerTheme(darkTheme = true) {
                    Box(
                        if (height == null) Modifier.fillMaxSize()
                        else Modifier.fillMaxWidth().height(height)
                    ) {
                        CleanCounterScreen(onBack = {})
                    }
                }
            }
        }
        settle()
    }

    /** Lay out once, by hand, with the clock stopped. */
    private fun settle() {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    // ─────────────────────────────────────────────────── the number is actually there

    @Test
    fun theCountIsWhollyOnScreenWithoutScrolling() {
        seedEstablishedRun()
        show()

        val hero = compose.onNodeWithTag(COUNTER_HERO_TAG)
        hero.assertIsDisplayed()
        // Unclipped bounds, so a hero pushed out of view is caught rather than reported at its
        // clipped size.
        val bottom = hero.getUnclippedBoundsInRoot().bottom
        assertTrue(
            "the counter ends at $bottom, past the bottom of a $phoneWindow window — the one " +
                "thing this screen exists to show is off it",
            bottom <= phoneWindow,
        )
    }

    /**
     * **Every unit has to survive the owner's font.** Not "the hero is displayed" — a clipped
     * block is still displayed. Each of the four labels must be there in its own right, which is
     * what stops holding when the row runs out of width and the text is cut.
     */
    @Test
    fun allFourUnitsSurviveALargeSystemFont() {
        seedEstablishedRun()
        fontScale.floatValue = 1.5f
        show()

        compose.onNodeWithTag(COUNTER_HERO_TAG).assertIsDisplayed()
        listOf("days", "hours", "min", "sec").forEach { unit ->
            compose.onNodeWithText(unit).assertIsDisplayed()
        }
    }

    /**
     * The hero must *grow* with the font rather than hold its size and clip — the v1.54 failure
     * shape, measured rather than assumed.
     */
    @Test
    fun theCounterGrowsWithTheFontInsteadOfClipping() {
        seedEstablishedRun()
        show(height = null)
        val normal = compose.onNodeWithTag(COUNTER_HERO_TAG).getUnclippedBoundsInRoot().height

        fontScale.floatValue = 1.5f
        settle()
        val large = compose.onNodeWithTag(COUNTER_HERO_TAG).getUnclippedBoundsInRoot().height

        assertTrue(
            "the counter is $large at a 1.5x font and $normal at 1.0x — it is not growing, which " +
                "means the numbers are being clipped instead",
            large > normal,
        )
    }

    // ─────────────────────────────────────────────────── and it is not a scoreboard

    /**
     * **The one that matters most.** On the day of a reset the record is not drawn at all.
     *
     * Delete the `elapsed >= DAY_MS` guard in `CleanCounterScreen` and this fails — which is how
     * it was shown to discriminate rather than merely pass.
     */
    @Test
    fun theDayOfAResetDoesNotShowTheRecord() {
        seedFreshReset()
        show()

        compose.onAllNodesWithText("longest run", substring = true).assertCountEquals(0)
    }

    /** …and once the run is established again the record is allowed back. Without this, "hide the
     *  record" could be implemented by never showing it and the case above would still pass. */
    @Test
    fun anEstablishedRunDoesShowTheRecord() {
        seedEstablishedRun()
        show(height = null)

        compose.onNodeWithText("longest run", substring = true).assertIsDisplayed()
    }

    // ─────────────────────────────────────────────────── the way to record a slip exists

    /** On this device's real window, at the default font. Not a claim about small screens: the
     *  actions sit below the fold on a short one by design, and are scrolled to. */
    @Test
    fun theResetControlIsOnTheScreen() {
        seedEstablishedRun()
        show(height = null)

        compose.onNodeWithTag(COUNTER_RESET_TAG).assertIsDisplayed()
    }
}
