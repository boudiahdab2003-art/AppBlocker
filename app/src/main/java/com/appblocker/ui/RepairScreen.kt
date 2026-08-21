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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appblocker.data.DeviceVendor
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

/**
 * **The screen for "it says it's on, and it isn't blocking anything".**
 *
 * The owner switches to Xiaomi's Second Space and back, and finds AppBlocker doing nothing while
 * Android's accessibility page still shows the switch on. It is not a display bug: switching space
 * stops every app in this space, HyperOS does not always rebind the accessibility service on the
 * way back, and `ENABLED_ACCESSIBILITY_SERVICES` records the user's *choice* rather than the
 * service's state. So the setting is telling the truth about a decision and a lie about reality.
 *
 * **Android does not allow the app to fix this itself.** Writing that setting needs
 * `WRITE_SECURE_SETTINGS`, which is granted over adb and to system apps only — the rule that stops
 * a malicious app from switching its own accessibility service on, and it stops this one from
 * switching itself back. Nor does the app switch *itself off* to force an honest toggle: a false
 * positive would disable the owner's own protection, and it would hand Strict Mode an exit.
 *
 * So this screen does the three things that are left, in order of how much they help:
 *
 * 1. **Lands on the right switch.** `ACTION_ACCESSIBILITY_SETTINGS` plus the `fragment_args`
 *    extras opens AppBlocker's own page, not the list of every accessibility service.
 * 2. **Says whether it worked.** It re-reads [ProtectionWatchdog] on every resume, so coming back
 *    from Settings turns the card green rather than leaving him to guess — which is the whole
 *    difference between a fix and a ritual.
 * 3. **Explains, once, why it keeps happening** and what makes it rarer, without pretending any
 *    of it is a cure.
 */
@Composable
fun RepairScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // Re-read on every return from Settings — this screen's job is to notice the moment the
    // toggle takes, and a snapshot frozen at open would report the broken state forever.
    val tick = resumeTick()
    val healthy = remember(tick) { ProtectionWatchdog.state(context) == ProtectionState.OK }
    val vendor = remember { DeviceVendor.advice() }

    Column(Modifier.fillMaxSize().background(appBackground()).safeDrawingPadding()) {
        EditorTopBar(title = "Blocking has stopped", onBack = onBack)
        LazyColumn(
            Modifier.fillMaxHeight().pageWidth().padding(horizontal = 20.dp)
                .testTag(REPAIR_LIST_TAG),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            item { StatusCard(healthy) }

            if (!healthy) {
                item {
                    AppCard {
                        Text(
                            "What happened",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(Space.sm))
                        Text(
                            "Your phone shut AppBlocker down. On Xiaomi this usually happens " +
                                "when you switch between spaces — switching space stops every " +
                                "app in the space you left, and your phone doesn't always start " +
                                "the blocker again when you come back.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Space.sm))
                        Text(
                            "Android still shows the switch as on, which is why it looked fine " +
                                "and blocked nothing. That switch remembers what you chose — it " +
                                "doesn't check whether the blocker is actually running. Now the " +
                                "app checks, and tells you.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                item {
                    AppCard(modifier = Modifier.testTag(REPAIR_STEPS_TAG)) {
                        Text(
                            "Switch it back on",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(Space.sm))
                        Step(1, "Tap the button below — it opens AppBlocker's own page.")
                        Step(2, "Turn AppBlocker OFF.")
                        Step(3, "Turn it ON again, and accept the prompt.")
                        Step(4, "Come back here. This page turns green when blocking is running.")
                        Spacer(Modifier.height(Space.md))
                        GradientButton(
                            text = "Open the switch",
                            onClick = { openOurAccessibilityPage(context) },
                            modifier = Modifier.testTag(REPAIR_BUTTON_TAG),
                        )
                    }
                }

                item {
                    AppCard {
                        Text(
                            "So you actually see this next time",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(Space.sm))
                        Text(
                            "The alert that brought you here pops up over whatever you're doing " +
                                "and comes back every few minutes until blocking is running " +
                                "again. But your phone keeps “Floating notifications” as its own " +
                                "switch, and it's off by default for most apps — while it's off, " +
                                "the alert can only sit silently in the shade. This is the one " +
                                "thing AppBlocker can't switch on for you.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Space.md))
                        GradientButton(
                            text = "Notification settings",
                            onClick = { openOurNotificationSettings(context) },
                            modifier = Modifier.testTag(REPAIR_NOTIFS_TAG),
                        )
                    }
                }

                item {
                    AppCard {
                        Text(
                            "Why the app can't do this for you",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(Space.sm))
                        Text(
                            "Android doesn't let an app switch its own blocking back on. That " +
                                "rule is what stops a bad app from quietly switching itself on " +
                                "and watching your screen — and it's the same rule that stops " +
                                "this one from switching itself back. It has to be your tap.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                AppCard {
                    Text(
                        "A much faster way to do it",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(Space.sm))
                    Text(
                        "In Settings ▸ Accessibility ▸ AppBlocker there's a Shortcut option. " +
                            "Turn it on and pick the volume keys. After that, holding both " +
                            "volume keys for three seconds switches the blocker off, and " +
                            "holding them again switches it back on — from anywhere, without " +
                            "hunting through Settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            vendor.spacesWarning?.let { warning ->
                item {
                    AppCard {
                        Text(
                            "Making it happen less often",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(Space.sm))
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
                    if (healthy) "Blocking is back on" else "Blocking is not running",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (healthy) {
                        "The blocker is running and watching again. Nothing else to do."
                    } else {
                        "It's switched on in Settings, but it isn't running — so nothing is " +
                            "being blocked right now."
                    },
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
