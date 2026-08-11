package com.appblocker

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblocker.ui.ADD_TIME_CHIP_TAG
import com.appblocker.ui.ADD_TIME_CHOOSE_TAG
import com.appblocker.ui.AddTimeRow
import com.appblocker.ui.theme.AppBlockerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Add more time" is the only control a running Strict session has, and it appears on a screen
 * whose whole content — a 56sp countdown, the explanation, the locked list — is already tall.
 *
 * Four chips in one row is exactly the shape that breaks at a large system font: the owner runs
 * one, and `FontScaleTest` exists because v1.54 shipped labels clipped at that size. A chip that
 * has been squeezed to nothing is not a smaller button, it is a session that cannot be extended.
 */
@RunWith(AndroidJUnit4::class)
class AddTimeRowTest {

    @get:Rule
    val compose = createComposeRule()

    /** Two hours left — a realistic running session, and the base for the "Ends …" line. */
    private val remaining = 2 * 60 * 60 * 1000L

    private fun setRow(height: Dp?, fontScale: Float = 1f) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                AppBlockerTheme(darkTheme = true) {
                    val modifier = if (height == null) Modifier.fillMaxWidth()
                    else Modifier.fillMaxWidth().height(height)
                    Box(modifier) { AddTimeRow(remaining = remaining, onAdd = {}) }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun allFourWaysToAddTimeAreOnScreen() {
        setRow(height = null)

        compose.onAllNodesWithTag(ADD_TIME_CHIP_TAG).assertCountEquals(QUICK_CHIPS)
        compose.onNodeWithTag(ADD_TIME_CHOOSE_TAG).assertIsDisplayed()
    }

    /**
     * Three chips sharing a row at 1.5× font is where this would fail — each one still has to be a
     * real, tappable target, not a sliver. 40dp is Android's own minimum touch size.
     *
     * There were four here at first, with "Choose…" as the fourth. That put the longest label in
     * the narrowest column, so it moved to its own full-width row; this asserts the split holds.
     */
    @Test
    fun chipsStayTappableAtALargeSystemFont() {
        setRow(height = null, fontScale = 1.5f)

        val chips = compose.onAllNodesWithTag(ADD_TIME_CHIP_TAG)
        chips.assertCountEquals(QUICK_CHIPS)
        repeat(QUICK_CHIPS) { i -> chips[i].assertIsDisplayed().assertHeightIsAtLeast(40.dp) }
        compose.onNodeWithTag(ADD_TIME_CHOOSE_TAG).assertIsDisplayed().assertHeightIsAtLeast(40.dp)
    }

    /** And on a short viewport, which is what a small phone hands the running-session column. */
    @Test
    fun chipsSurviveAShortViewport() {
        setRow(height = 200.dp)

        compose.onAllNodesWithTag(ADD_TIME_CHIP_TAG).assertCountEquals(QUICK_CHIPS)
        compose.onNodeWithTag(ADD_TIME_CHOOSE_TAG).assertIsDisplayed().assertHeightIsAtLeast(40.dp)
    }

    private companion object {
        /** +15m, +30m, +1h. "Choose another amount" is a separate full-width row, not a chip. */
        const val QUICK_CHIPS = 3
    }
}
