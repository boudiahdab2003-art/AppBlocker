package com.appblocker

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblocker.data.FilterState
import com.appblocker.data.NetworkFilter
import com.appblocker.ui.NETDNS_COPY_TAG
import com.appblocker.ui.NETDNS_LIST_TAG
import com.appblocker.ui.NETDNS_SETTINGS_TAG
import com.appblocker.ui.NETDNS_STATUS_TAG
import com.appblocker.ui.NETDNS_STEPS_TAG
import com.appblocker.ui.NetworkFilterScreen
import com.appblocker.ui.theme.AppBlockerTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The family-filter setup screen, drawn for real.
 *
 * **Why this one has to run on a device.** The whole screen is a hostname he has to copy and two
 * buttons he has to reach, and a mistyped hostname does not degrade gracefully — it produces a
 * phone that resolves nothing. If the address or the Copy button sits below the fold at a large
 * system font, he types it by hand from memory, and that is the failure mode this screen exists to
 * remove. No JVM test can see any of it.
 *
 * The branch is passed in through `stateOverride` for the reason `RepairScreenTest` records: a test
 * that reaches its interesting branch by relying on the machine's own state passes *because the
 * machine is misconfigured*, and fails on the first device where the thing actually works.
 */
@RunWith(AndroidJUnit4::class)
class NetworkFilterScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val windowHeightDp: Int
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.configuration.screenHeightDp

    private fun assumeWindowFits(height: Dp) = assumeTrue(
        "this window is ${windowHeightDp}dp tall, too short to host the " +
            "${height.value.toInt()}dp viewport this case is about",
        windowHeightDp >= height.value,
    )

    /**
     * The state the screen is drawn from, held OUTSIDE the composition so a test can move it.
     *
     * `setContent` may be called once per test — a rule worth stating here because the obvious
     * way to cover four states is a loop that calls it four times, which is what the first
     * version of this file did and what the emulator rejected. Driving a `MutableState` instead
     * is not just a workaround: it exercises the case that actually matters, which is the screen
     * being **re-read after he changes the setting and comes back**.
     */
    private lateinit var state: MutableState<FilterState>

    private fun setScreen(scale: Float, initial: FilterState = FilterState.OFF) {
        compose.setContent {
            state = remember { mutableStateOf(initial) }
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) {
                AppBlockerTheme(darkTheme = true) {
                    NetworkFilterScreen(onBack = {}, stateOverride = state.value)
                }
            }
        }
    }

    /** Move the reading the way the network would, and let the screen settle. */
    private fun show(next: FilterState) {
        compose.runOnUiThread { state.value = next }
        compose.waitForIdle()
    }

    /** The two controls that finish the job, at the top of Android's font slider. */
    @Test
    fun bothButtonsAreReachableAtTheLargestFont() {
        setScreen(scale = 2f)

        compose.onNodeWithTag(NETDNS_LIST_TAG).performScrollToNode(hasTestTag(NETDNS_COPY_TAG))
        compose.onNodeWithTag(NETDNS_COPY_TAG).assertExists().assertHeightIsAtLeast(40.dp)
        compose.onNodeWithTag(NETDNS_LIST_TAG).performScrollToNode(hasTestTag(NETDNS_SETTINGS_TAG))
        compose.onNodeWithTag(NETDNS_SETTINGS_TAG).assertExists().assertHeightIsAtLeast(40.dp)
    }

    /**
     * **The address has to be readable, not just present.** Copy is the intended route, but the
     * clipboard is exactly the kind of thing an OEM breaks, and the fallback is his eyes. A
     * hostname he cannot read is a hostname he types wrong.
     */
    @Test
    fun theAddressIsShownInFullAtTheLargestFont() {
        assumeWindowFits(600.dp)
        setScreen(scale = 2f)

        compose.onNodeWithTag(NETDNS_LIST_TAG).performScrollToNode(hasTestTag(NETDNS_STEPS_TAG))
        compose.onNodeWithText(NetworkFilter.RECOMMENDED).assertIsDisplayed()
    }

    /** Every state draws a status card, including the two that are nobody's fault. */
    @Test
    fun everyStateSaysSomething() {
        setScreen(scale = 1f)
        for (next in FilterState.entries) {
            show(next)
            compose.onNodeWithTag(NETDNS_STATUS_TAG).assertIsDisplayed()
        }
    }

    /**
     * **When it is already working, the screen must not still be asking for it.** A setup screen
     * that shows its steps after the job is done reads as "it didn't work" — the exact anxiety this
     * screen is meant to end. Same for a phone too old to have the setting at all: instructions it
     * cannot follow are worse than none.
     */
    @Test
    fun aWorkingFilterHidesTheSetupSteps() {
        setScreen(scale = 1f)
        compose.onNodeWithTag(NETDNS_STEPS_TAG).assertExists()

        // The one that matters most: he pastes the address, comes back, and the screen must stop
        // asking. A setup screen still showing its steps after the job is done reads as "it did
        // not work" - the exact anxiety this screen exists to end.
        show(FilterState.FILTERING)
        compose.onNodeWithTag(NETDNS_STEPS_TAG).assertDoesNotExist()

        // And instructions a phone cannot follow are worse than none.
        show(FilterState.CANT_TELL)
        compose.onNodeWithTag(NETDNS_STEPS_TAG).assertDoesNotExist()

        // Back to asking when it stops, without a fresh screen.
        show(FilterState.OFF)
        compose.onNodeWithTag(NETDNS_STEPS_TAG).assertExists()
    }
}
