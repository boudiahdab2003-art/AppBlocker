package com.appblocker

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appblocker.ui.TEMPLATE_CARD_TAG
import com.appblocker.ui.TemplateCard
import com.appblocker.ui.appTemplates
import com.appblocker.ui.theme.AppBlockerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The template cards, measured — which they never have been.
 *
 * These cards carry **two** comments about font-scale bugs that already shipped: the grid's
 * `IntrinsicSize.Min` was added because time labels were being clipped, and "Active" has
 * `softWrap = false` because at a large font it broke letter by letter into "Ac/tiv/e". Both were
 * found the way this project keeps finding them — by the owner, on his phone, after a release —
 * and `FontScaleTest` covers `ScheduleTile` only, so nothing guarded these.
 *
 * The redesign is exactly when that matters. The title went from `titleSmall` (16sp) to
 * `titleLarge` (25sp) and the card gained a centred emoji and a schedule line, so there is far
 * more content to overflow. At 1.5× — roughly what the owner runs — a card that cannot grow
 * clips the line telling you *when* the template blocks, which is the one fact that distinguishes
 * one from another.
 */
@RunWith(AndroidJUnit4::class)
class TemplateCardTest {

    @get:Rule
    val compose = createComposeRule()

    /** The real width a card gets: full-bleed inside the tab's 20dp side padding on a 360dp phone. */
    private val cardWidth = 320.dp

    private fun setCards(fontScale: Float = 1f, active: Boolean = false) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                AppBlockerTheme(darkTheme = true) {
                    Box(Modifier.width(cardWidth)) {
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                            appTemplates.forEach { t ->
                                TemplateCard(Modifier, t, active = active, onEditApps = {}) {}
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Every template says what it is and when it runs — the two facts that tell them apart. */
    @Test
    fun everyTemplateShowsItsTitleAndSchedule() {
        setCards()

        appTemplates.forEach { t ->
            compose.onNodeWithText(t.title).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(t.scheduleLine).assertExists()
        }
    }

    /**
     * The regression this file exists for. At the owner's font size the cards must **grow**, not
     * clip — so the title and the schedule line both have to survive, on all six.
     */
    @Test
    fun titlesAndSchedulesSurviveALargeSystemFont() {
        setCards(fontScale = 1.5f)

        appTemplates.forEach { t ->
            compose.onNodeWithText(t.title).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(t.scheduleLine).assertExists()
        }
    }

    /** The card's own floor, at both sizes — a card shorter than this has clipped something. */
    @Test
    fun everyCardKeepsItsHeight() {
        setCards()
        val cards = compose.onAllNodesWithTag(TEMPLATE_CARD_TAG)
        cards.assertCountEquals(appTemplates.size)
        repeat(appTemplates.size) { i -> cards[i].assertHeightIsAtLeast(168.dp) }
    }

    /**
     * "Active" is the word that used to break into "Ac/tiv/e". It is also now the only *stated*
     * form of the applied state — the border and the glow say it in colour, which a screen reader
     * cannot read — so it has to render, on every card, at the size that broke it.
     */
    @Test
    fun theAppliedLabelRendersOnOneLineAtALargeFont() {
        setCards(fontScale = 1.5f, active = true)

        compose.onAllNodesWithText("Active").assertCountEquals(appTemplates.size)
    }
}
