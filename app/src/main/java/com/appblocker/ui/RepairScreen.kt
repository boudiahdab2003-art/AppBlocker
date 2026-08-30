package com.appblocker.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appblocker.R
import com.appblocker.data.DeviceVendor
import com.appblocker.data.FilterState
import com.appblocker.data.NetworkFilter
import com.appblocker.data.OutageLog
import com.appblocker.service.BlockerAccessibilityService
import com.appblocker.service.ProtectionState
import com.appblocker.service.ProtectionWatchdog
import com.appblocker.ui.theme.AppCard
import com.appblocker.ui.theme.Space
import com.appblocker.ui.theme.appBackground
import com.appblocker.ui.theme.pageWidth

/** Test tags for the rendering test. */
const val REPAIR_BUTTON_TAG = "repair_open_switch"
const val REPAIR_STEPS_TAG = "repair_steps"
const val REPAIR_STATUS_TAG = "repair_status"
const val REPAIR_NOTIFS_TAG = "repair_notification_settings"
const val REPAIR_LIST_TAG = "repair_list"
const val REPAIR_RECORD_TAG = "repair_record"
const val REPAIR_SHORTCUT_TAG = "repair_shortcut"

/**
 * **The screen for "it says it's on, and it isn't blocking anything".**
 *
 * The owner finds AppBlocker doing nothing while Android's accessibility page still shows the
 * switch on. It is not a display bug: `ENABLED_ACCESSIBILITY_SERVICES` records the user's *choice*
 * rather than the service's state, so the setting tells the truth about a decision and a lie about
 * reality.
 *
 * ⚠️ **What stops it is not known, and this screen must not pretend otherwise.** It used to open
 * by blaming Xiaomi's Second Space. He ruled that out himself on 28 Aug 2026 — *"our app doesnt
 * stop just because of second space i rarely use second space these days and it still turn off"* —
 * and naming the wrong cause confidently is worse than naming none, because it sends him to fix a
 * setting that was never the problem. `OutageLog` exists to find the real one; invariant 31.
 *
 * **Android does not allow the app to fix this itself.** Writing that setting needs
 * `WRITE_SECURE_SETTINGS`, which is granted over adb and to system apps only — the rule that stops
 * a malicious app from switching its own accessibility service on, and it stops this one from
 * switching itself back. Nor does the app switch *itself off* to force an honest toggle: a false
 * positive would disable the owner's own protection, and it would hand Strict Mode an exit.
 *
 * So this screen does the three things that are left, in order of how much they help — **and that
 * order is now the order they appear in**, which it was not until 30 Aug 2026:
 *
 * 1. **Lands on the right switch.** `ACTION_ACCESSIBILITY_SETTINGS` plus the `fragment_args`
 *    extras opens AppBlocker's own page, not the list of every accessibility service. The steps
 *    and that button are the first thing on the screen, followed by the volume-key shortcut that
 *    makes the next outage cost six seconds.
 * 2. **Says whether it worked.** It re-reads [ProtectionWatchdog] on every resume, so coming back
 *    from Settings turns the card green rather than leaving him to guess — which is the whole
 *    difference between a fix and a ritual.
 * 3. **Explains, once, why it keeps happening** and what makes it rarer, without pretending any of
 *    it is a cure — now behind [ExpandableNote] headings rather than in front of the button. The
 *    explanations were about two hundred words and they sat *above* the fix, on the screen someone
 *    opens when they want blocking back immediately.
 */
@Composable
fun RepairScreen(
    onBack: () -> Unit,
    /**
     * Which branch to draw, or null to ask the device — which is what production always does.
     *
     * A test seam, and it exists because the alternative was invisible. Everything this screen is
     * *for* — the four steps and the one button that ends the problem — lives in the unhealthy
     * branch, so a rendering test that merely composes the screen asserts whatever state the
     * device happens to be in. `RepairScreenTest` said so out loud ("the emulator's accessibility
     * service is not running during the test"), which made the tests pass on the release-gate
     * emulator **because the app was broken there**, and fail on the first real phone where
     * blocking actually worked. Nothing was wrong with either the screen or the phone.
     */
    healthyOverride: Boolean? = null,
) {
    val context = LocalContext.current
    // Re-read on every return from Settings — this screen's job is to notice the moment the
    // toggle takes, and a snapshot frozen at open would report the broken state forever.
    val tick = resumeTick()
    // Read unconditionally, override after: skipping the remember when the seam is set would make
    // the composition structure depend on the parameter.
    val deviceHealthy = remember(tick) { ProtectionWatchdog.state(context) == ProtectionState.OK }
    val healthy = healthyOverride ?: deviceHealthy
    val vendor = remember { DeviceVendor.advice() }
    // Re-read on the same tick as the status: he comes back from Settings having just ended an
    // outage, and the count that does not include the one he just fixed reads as broken.
    val totals = remember(tick) { OutageLog.totals(context) }
    val lastMinutes = remember(tick) {
        OutageLog.last(context)?.durationMs?.takeIf { it >= 0L }?.let { (it / 60_000L).toInt() }
    }
    val filtering = remember(tick) {
        runCatching { NetworkFilter.read(context).state == FilterState.FILTERING }
            .getOrDefault(false)
    }

    Column(Modifier.fillMaxSize().background(appBackground()).safeDrawingPadding()) {
        EditorTopBar(title = stringResource(R.string.repair_title), onBack = onBack)
        LazyColumn(
            Modifier.fillMaxHeight().pageWidth().padding(horizontal = 20.dp)
                .testTag(REPAIR_LIST_TAG),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            item { StatusCard(healthy) }

            if (!healthy) {
                // ⚠️ **The fix comes first, and it did not used to.**
                //
                // This screen is reached by someone whose blocking has stopped and who wants it
                // back — from the alert, the red banner, or the Profile pill. It used to open with
                // about two hundred words of explanation and put the button that ends the problem
                // in the fourth card down. *"a lot of talk a lot of unneeded"* (30 Aug 2026).
                //
                // Now: the four steps and the button, then the volume-key shortcut that makes the
                // next one cost six seconds, and only then everything the app has to say about it
                // — each of which is one tap away rather than in the way. Nothing was deleted.
                item {
                    AppCard(modifier = Modifier.testTag(REPAIR_STEPS_TAG)) {
                        Text(
                            stringResource(R.string.repair_steps_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(Space.sm))
                        Step(1, stringResource(R.string.repair_step_1))
                        Step(2, stringResource(R.string.repair_step_2))
                        Step(3, stringResource(R.string.repair_step_3))
                        Step(4, stringResource(R.string.repair_step_4))
                        Spacer(Modifier.height(Space.md))
                        GradientButton(
                            text = stringResource(R.string.repair_open_switch),
                            onClick = { openOurAccessibilityPage(context) },
                            modifier = Modifier.testTag(REPAIR_BUTTON_TAG),
                        )
                    }
                }

                item { ShortcutCard() }

                item {
                    ExpandableNote(title = stringResource(R.string.repair_what_title)) {
                        Text(
                            stringResource(R.string.repair_what_body_1),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Space.sm))
                        Text(
                            stringResource(R.string.repair_what_body_2),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // What this phone has actually recorded, rather than what the app assumes about
                // phones in general. The count was always there; the length is new, and it is the
                // half that says what an outage costs.
                //
                // Left open: it is short, it is *his* data rather than our explaining, and it is
                // the one thing on this screen that changes between visits.
                item {
                    AppCard(modifier = Modifier.testTag(REPAIR_RECORD_TAG)) {
                        Text(
                            stringResource(R.string.repair_record_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(Space.sm))
                        if (totals.count <= 0) {
                            Text(
                                stringResource(R.string.repair_record_first),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                stringResource(R.string.repair_record_count, totals.count),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // Only when it was actually measured: a reboot mid-outage leaves the
                            // duration unknown, and inventing one would be worse than saying
                            // nothing (invariant 11).
                            lastMinutes?.let {
                                Spacer(Modifier.height(Space.sm))
                                Text(
                                    stringResource(R.string.repair_record_last, it),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // Only when the filter is genuinely doing something. Told he was still covered
                // when he wasn't would be the worst sentence on this screen — see invariant 27,
                // which is the same rule pointed the other way: never judge on an answer you
                // don't have, and never reassure on one either.
                if (filtering) {
                    item {
                        ExpandableNote(title = stringResource(R.string.repair_held_title)) {
                            Text(
                                stringResource(R.string.repair_held_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // The button stays above the fold: this card is one of only two on the screen that
                // *do* something, and the sixty-five words explaining why are not what makes it
                // worth tapping.
                item {
                    ExpandableNote(
                        title = stringResource(R.string.repair_floating_title),
                        alwaysVisible = {
                            GradientButton(
                                text = stringResource(R.string.repair_notification_settings),
                                onClick = { openOurNotificationSettings(context) },
                                modifier = Modifier.testTag(REPAIR_NOTIFS_TAG),
                            )
                        },
                    ) {
                        Text(
                            stringResource(R.string.repair_floating_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                item {
                    ExpandableNote(title = stringResource(R.string.repair_why_title)) {
                        Text(
                            stringResource(R.string.repair_why_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // When blocking is healthy the shortcut is still worth setting up — it is the thing
            // that makes the NEXT one quick. When it is not healthy it has already been drawn
            // directly under the steps, where it belongs: it is the same repair, faster.
            if (healthy) item { ShortcutCard() }

            vendor.spacesWarning?.let { warning ->
                item {
                    ExpandableNote(title = stringResource(R.string.repair_vendor_title)) {
                        Text(
                            warning,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(Space.xxl)) }
        }
    }
}

/**
 * The live answer, and the reason this screen is worth opening twice.
 *
 * Green is claimed only when the watchdog says OK — which now means the watcher is genuinely
 * bound and running, not merely listed in Settings (see
 * [BlockerAccessibilityService.isConnected]). Claiming it from the setting alone would reproduce
 * the exact lie this screen exists to correct.
 */
/**
 * The volume-key accessibility shortcut — **the only part of this screen that makes the next
 * outage cheap.**
 *
 * Android will not let the app switch its own blocking back on, and it never will. What it does
 * allow is a shortcut the user assigns himself: with it on, holding both volume keys for three
 * seconds toggles the service from wherever he is. The repair stops being a hunt through Settings
 * and becomes two button-holds.
 *
 * Extracted so it can be drawn in two places — directly under the fix steps while blocking is
 * down, and on its own when everything is fine — without the text existing twice.
 */
@Composable
private fun ShortcutCard() {
    AppCard(modifier = Modifier.testTag(REPAIR_SHORTCUT_TAG)) {
        Text(
            stringResource(R.string.repair_shortcut_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Space.sm))
        Text(
            stringResource(R.string.repair_shortcut_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusCard(healthy: Boolean) {
    val color =
        if (healthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    AppCard(modifier = Modifier.testTag(REPAIR_STATUS_TAG)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(
                        if (healthy) R.string.repair_status_on else R.string.repair_status_off,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(
                        if (healthy) R.string.repair_status_on_body
                        else R.string.repair_status_off_body,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = Space.xs)) {
        Text(
            "$number.",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(Space.md))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Opens **AppBlocker's own** accessibility page rather than the list of every service on the
 * phone.
 *
 * `:settings:fragment_args_key` (plus the same key inside a `:settings:show_fragment_args` bundle,
 * which is what the older Settings builds read) is how Android's own Settings app deep-links and
 * highlights a single row. It is not public API, so it is best-effort by design and falls back to
 * the plain list — the same shape as [DeviceVendor]'s keep-alive deep links, and for the same
 * reason: an OEM ignoring an extra must cost the user an extra scroll, never a dead button.
 *
 * The written steps above are what actually gets him there; this only shortens the walk.
 */
private fun openOurAccessibilityPage(context: Context) {
    val component = ComponentName(context, BlockerAccessibilityService::class.java)
        .flattenToString()
    val args = Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, component) }
    val deepLink = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(EXTRA_FRAGMENT_ARG_KEY, component)
        .putExtra(EXTRA_SHOW_FRAGMENT_ARGS, args)
    if (runCatching { context.startActivity(deepLink) }.isSuccess) return
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * Opens AppBlocker's notification settings — the one switch the app cannot flip for itself.
 *
 * **HyperOS/MIUI keeps "Floating notifications" as a separate per-app permission, off by default
 * for most apps.** While it is off, nothing the app does makes the "blocking has stopped" alert
 * pop up: not `IMPORTANCE_HIGH`, not its own channel, not re-posting. So the honest thing is to
 * say so and put him one tap from it, rather than quietly failing to be seen.
 *
 * `ACTION_APP_NOTIFICATION_SETTINGS` is public API and every OEM honours it; the vendor's own
 * floating switch lives on that page (or one level into the channel) on the builds that have one.
 * Falls back to the app details page — same rule as [openOurAccessibilityPage]: a missing screen
 * costs a scroll, never a dead button.
 */
internal fun openOurNotificationSettings(context: Context) {
    val direct = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    if (runCatching { context.startActivity(direct) }.isSuccess) return
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

// Hidden framework constants (@SystemApi in Settings). Spelt out rather than referenced so this
// compiles against the public SDK; the strings have not changed since Android 8.
private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
private const val EXTRA_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"
