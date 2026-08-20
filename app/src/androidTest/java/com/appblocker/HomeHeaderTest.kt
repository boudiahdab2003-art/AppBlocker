package com.appblocker

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import com.appblocker.ui.HomeHeader
import com.appblocker.ui.theme.AppBlockerTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Blocking header must hold its countdown on one line, however long the session is.
 *
 * **Reported from the phone (20 Aug 2026):** a running session with about 24 days left put
 * `35507:29` in the header pill — the countdown had minutes and seconds and no upper bound — and
 * it wrapped onto two lines and spilled out of its own rounded shape. "The number at the top right
 * is very ugly."
 *
 * Two separate faults met there, and only one of them is a string. The formatter had no day band;
 * the row also measured its **unweighted** children first, so the title took its full width and
 * the pill got the leftovers. `TimeFormatTest` covers the first. This covers the second, and it is
 * the only layer that can: the wrap is a *measured* fact, and a formatter test on a build with the
 * old row would still have passed while the pill was two lines tall on his phone.
 *
 * **Every case here is measured at two font scales, and that is not padding.** Run against the
 * broken build, the 1.0 legs pass — the emulator's default font leaves just enough room at this
 * width — and only the large-font legs fail. That is v1.54 exactly: his HyperOS is set larger than
 * the emulator default, so a check at 1.0 alone is a check of a configuration he does not use, and
 * would have called this build clean.
 */
@RunWith(AndroidJUnit4::class)
class HomeHeaderTest {

    @get:Rule
    val compose = createComposeRule()

    private val headerTag = "home-header"

    /** The Blocking tab's content width on a phone: the page less its 20.dp side padding. */
    private val headerWidth = 320.dp

    /** Default, and the kind of size the owner actually runs. */
    private val scales = listOf(1f, 1.5f)

    private val fiveMinutes = 5 * 60_000L

    /** The wheel's maximum (`DurationWheel` offers 0–30 days), i.e. the longest real session. */
    private val thirtyDays = 30 * 24 * 60 * 60_000L

    /** The session in the report: 35507 minutes and 29 seconds. */
    private val reported = 35_507 * 60_000L + 29_000L

    private class Header(val setRemaining: (Long) -> Unit, val setScale: (Float) -> Unit)

    /**
     * One composition holding the real [HomeHeader], pinned to a phone's content width so the row
     * genuinely runs out of room. Measured with unlimited width it could never wrap, and the test
     * would pass on the broken build at every scale rather than just the small one.
     */
    private fun setHeader(): Header {
        var remaining by mutableLongStateOf(fiveMinutes)
        var scale by mutableFloatStateOf(1f)
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) {
                AppBlockerTheme(darkTheme = true) {
                    Box(Modifier.width(headerWidth).testTag(headerTag)) { HomeHeader(remaining) }
                }
            }
        }
        return Header({ remaining = it }, { scale = it })
    }

    private fun headerHeight() = compose.onNodeWithTag(headerTag).getUnclippedBoundsInRoot().height

    private fun set(header: Header, remaining: Long, scale: Float) {
        compose.runOnUiThread { header.setRemaining(remaining); header.setScale(scale) }
        compose.waitForIdle()
    }

    /**
     * The header at [remaining] must be no taller than the header at five minutes, at every scale.
     *
     * Height is the right measurement because it is the symptom: a countdown that does not fit
     * wraps, a wrapped countdown makes the pill two lines tall, and the row grows with it. It also
     * needs no knowledge of the pill's internals, so it keeps holding if the pill is restyled.
     */
    private fun assertNoTallerThanAShortSession(remaining: Long, what: String) {
        val header = setHeader()
        for (scale in scales) {
            set(header, fiveMinutes, scale)
            val short = headerHeight()
            set(header, remaining, scale)
            val long = headerHeight()
            assertTrue(
                "At font scale $scale the header grew for $what: $short at 5 min, $long at $what",
                long <= short,
            )
        }
    }

    /** The reported bug, at the value that was on his screen. */
    @Test
    fun theReportedSessionStaysOnOneLine() =
        assertNoTallerThanAShortSession(reported, "24d 15h")

    /** The same, at the longest session the duration wheel can actually produce. */
    @Test
    fun theLongestPossibleSessionStaysOnOneLine() =
        assertNoTallerThanAShortSession(thirtyDays, "30d 0h")

    /**
     * The name survives beside it. It is the element that now gives way — it carries the weight
     * and the pill does not — so this is the direction the fix itself could go wrong in.
     */
    @Test
    fun theAppNameIsStillThereBesideTheLongestCountdown() {
        val header = setHeader()
        for (scale in scales) {
            set(header, thirtyDays, scale)
            compose.onNodeWithText("AppBlocker").assertIsDisplayed()
            compose.onNodeWithText("30d 0h").assertIsDisplayed()
        }
    }

    /** With nothing running there is no pill at all, and the header is just the name. */
    @Test
    fun withNoSessionTheHeaderIsJustTheName() {
        compose.setContent {
            AppBlockerTheme(darkTheme = true) {
                Box(Modifier.width(headerWidth)) { HomeHeader(null) }
            }
        }
        compose.onNodeWithText("AppBlocker").assertIsDisplayed()
    }
}
