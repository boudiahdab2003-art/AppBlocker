package com.appblocker

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
// DpRect.height is an extension property, not a member — getUnclippedBoundsInRoot().height does
// not resolve without this.
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblocker.ui.ScheduleTile
import com.appblocker.ui.theme.AppBlockerTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Screens must survive a large system font.
 *
 * This is a whole class of bug the app has already shipped: **v1.54** went out with the
 * New-schedule tile labels ("Usage limit", "Launch count") clipped in half, and it was found from a
 * photo of the owner's phone — his HyperOS is set to a larger font than the emulator's default, so
 * every screenshot taken during development was of a configuration he does not use. The fix made
 * the tile's height font-scale-aware; nothing has held it there since.
 *
 * The assertion is deliberately about *behaviour*, not pixels: the tile must actually grow when the
 * font grows. A test that only checked "the label is displayed at 1.0" would have passed on the
 * broken build.
 */
@RunWith(AndroidJUnit4::class)
class FontScaleTest {

    @get:Rule
    val compose = createComposeRule()

    private val tag = "schedule-tile"

    /** The labels that actually clipped on the owner's phone in v1.54. */
    private val labels = listOf("Usage limit", "Launch count", "Location", "Time")

    /**
     * One composition whose font scale the test can move, so the same tile can be measured at
     * several scales and compared. Width is pinned to the tile's real width in the schedule row —
     * a tile measured with unlimited width would never wrap and could never clip.
     */
    private fun setTiles(initialScale: Float): () -> Unit {
        var scale by mutableStateOf(initialScale)
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) {
                AppBlockerTheme(darkTheme = true) {
                    Row {
                        labels.forEachIndexed { i, label ->
                            ScheduleTile(
                                modifier = Modifier.width(116.dp)
                                    .then(if (i == 0) Modifier.testTag(tag) else Modifier),
                                label = label,
                                icon = Icons.Filled.Schedule,
                                enabled = true,
                                onClick = {},
                            )
                        }
                    }
                }
            }
        }
        return { scale = 1.5f }
    }

    /**
     * The v1.54 regression. The tile has to get taller when the font does — that growth is the
     * whole fix, and it is invisible to every other kind of test.
     */
    @Test
    fun scheduleTileGrowsWithTheSystemFont() {
        val goLarge = setTiles(initialScale = 1f)

        val atDefault = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().height

        compose.runOnUiThread { goLarge() }
        compose.waitForIdle()
        val atLarge = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().height

        assertTrue(
            "The tile must grow with the system font (v1.54 shipped clipped labels because it " +
                "did not): $atDefault at scale 1.0, $atLarge at scale 1.5",
            atLarge > atDefault,
        )
    }

    /** Every label still renders at the owner's kind of font size, not just the short one. */
    @Test
    fun everyTileLabelSurvivesALargeFont() {
        val goLarge = setTiles(initialScale = 1f)
        compose.runOnUiThread { goLarge() }
        compose.waitForIdle()

        labels.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    /**
     * The extreme end of Android's font slider, which is a setting real people use and one nobody
     * here has ever tested against. The tile's growth is capped (`coerceIn(0f, 1.2f)`), so this is
     * the case most likely to clip — worth knowing about deliberately rather than from a photo.
     */
    @Test
    fun tileLabelsSurviveTheLargestFontAndroidOffers() {
        setTiles(initialScale = 2f)

        labels.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }
}
