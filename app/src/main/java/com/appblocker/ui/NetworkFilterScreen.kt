package com.appblocker.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appblocker.R
import com.appblocker.data.DeviceBoot
import com.appblocker.data.FilterState
import com.appblocker.data.NetworkFilter
import com.appblocker.data.OffSwitchGuard
import com.appblocker.data.SettingsStore
import com.appblocker.ui.theme.AppCard
import com.appblocker.ui.theme.Space
import com.appblocker.ui.theme.appBackground
import com.appblocker.ui.theme.pageWidth
import kotlinx.coroutines.delay

/** Test tags for the rendering test. */
const val NETDNS_STATUS_TAG = "netdns_status"
const val NETDNS_STEPS_TAG = "netdns_steps"
const val NETDNS_COPY_TAG = "netdns_copy"
const val NETDNS_SETTINGS_TAG = "netdns_settings"
const val NETDNS_LIST_TAG = "netdns_list"

/**
 * **The screen that sets up the one protection this app cannot run itself.**
 *
 * Asked for on 26 Aug 2026 — *"cant we include a dns in our app it will take our app to another
 * level?"* — and it does, for a reason specific to this codebase: every other layer needs the
 * accessibility service alive and a readable screen, and "switched on but dead" is the failure this
 * app has spent the most releases on. A system DNS setting has none of that exposure. It is not an
 * app, so nothing can kill it.
 *
 * The price is that Android will not let an app set it (that needs `WRITE_SECURE_SETTINGS`, which
 * means a cable, which this app does not ask anyone for). So this screen does what the accessibility
 * setup already does: **hand him the exact thing to paste, open the right page, and then prove it
 * worked** rather than claim it did. The status card is read from the live network, never from a
 * flag we set when he tapped the button — the same "prove it, don't claim it" rule the repair
 * screen exists to enforce.
 *
 * @param stateOverride rendering tests drive every card without a network.
 */
@Composable
fun NetworkFilterScreen(onBack: () -> Unit, stateOverride: FilterState? = null) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    val boot = remember { DeviceBoot.count(context) }
    var guardOn by remember { mutableStateOf(SettingsStore.netFilterGuard(context)) }
    var offRequest by remember { mutableStateOf(SettingsStore.netFilterOffRequest(context)) }
    var showGate by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    val untilUnlock = offRequest?.remaining(boot) ?: 0L
    val untilExpiry = offRequest?.remaining(boot, extraMs = OFF_WINDOW_MS) ?: 0L
    // The same tested phase machine the adult pack and the off-switch guard use, called rather
    // than re-derived: two copies of one state machine, one of them incomplete, is the first bug
    // shape in docs/BLOCKING_INVARIANTS.md. In particular `untilUnlock <= 0` alone never asks
    // whether the window has since CLOSED, and a lapsed request must never read as an open door.
    val phase = OffSwitchGuard.phase(offRequest != null, untilUnlock, untilExpiry)
    LaunchedEffect(offRequest, tick) {
        if (offRequest == null) return@LaunchedEffect
        if (untilExpiry <= 0L) {
            offRequest = null
            SettingsStore.clearNetFilterOffRequest(context)
            return@LaunchedEffect
        }
        delay(30_000)
        tick++
    }
    // Recomputed on every recomposition on purpose: coming back from Settings recomposes, and a
    // remembered reading is exactly how a screen ends up telling him it is off after he fixed it.
    val state = stateOverride ?: NetworkFilter.read(context).state

    Column(Modifier.fillMaxSize().background(appBackground()).safeDrawingPadding()) {
        EditorTopBar(title = stringResource(R.string.netdns_title), onBack = onBack)
        LazyColumn(
            Modifier.fillMaxHeight().pageWidth().padding(horizontal = 20.dp)
                .testTag(NETDNS_LIST_TAG),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            item { StatusCard(state) }

            if (state != FilterState.FILTERING && state != FilterState.CANT_TELL) {
                item { Card(R.string.netdns_what_title, R.string.netdns_what_body, R.string.netdns_what_body_2) }

                item {
                    AppCard(modifier = Modifier.testTag(NETDNS_STEPS_TAG)) {
                        Heading(stringResource(R.string.netdns_steps_title))
                        Spacer(Modifier.height(Space.sm))
                        Step(1, stringResource(R.string.netdns_step_1))
                        Step(2, stringResource(R.string.netdns_step_2))
                        Step(3, stringResource(R.string.netdns_step_3))
                        Step(4, stringResource(R.string.netdns_step_4))
                        Step(5, stringResource(R.string.netdns_step_5))
                        Spacer(Modifier.height(Space.md))
                        AddressBox()
                        Spacer(Modifier.height(Space.md))
                        GradientButton(
                            text = stringResource(
                                if (copied) R.string.netdns_copied else R.string.netdns_copy,
                            ),
                            onClick = {
                                copyAddress(context)
                                copied = true
                            },
                            modifier = Modifier.testTag(NETDNS_COPY_TAG),
                        )
                        Spacer(Modifier.height(Space.sm))
                        GradientButton(
                            text = stringResource(R.string.netdns_open_settings),
                            onClick = { openPrivateDnsSettings(context) },
                            modifier = Modifier.testTag(NETDNS_SETTINGS_TAG),
                        )
                    }
                }

                item { Card(R.string.netdns_cost_title, R.string.netdns_cost_body, R.string.netdns_cost_body_2) }
                item { Card(R.string.netdns_why_title, R.string.netdns_why_body) }
            }
            // Only ever offered once the guard is real - before the filter has been seen
            // working it defends nothing, and a switch for turning off something that is not
            // happening is just a way to talk him out of setting it up.
            if (SettingsStore.netFilterSeen(context)) {
                item {
                    AppCard {
                        Heading(stringResource(R.string.netdns_off_title))
                        Spacer(Modifier.height(Space.sm))
                        Text(
                            stringResource(R.string.netdns_off_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Space.md))
                        when {
                            !guardOn -> {
                                Text(
                                    stringResource(R.string.netdns_off_done),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Spacer(Modifier.height(Space.sm))
                                GradientButton(
                                    text = stringResource(R.string.netdns_off_back_on),
                                    // Arming a protection is instant and always allowed, in every
                                    // state including Strict - refusing to let someone turn a
                                    // protection back ON is the v1.127 mistake (invariant 14).
                                    onClick = {
                                        SettingsStore.setNetFilterGuard(context, true)
                                        guardOn = true
                                    },
                                )
                            }
                            phase == OffSwitchGuard.Phase.OPEN -> GradientButton(
                                text = stringResource(R.string.netdns_off_now),
                                onClick = {
                                    SettingsStore.setNetFilterGuard(context, false)
                                    SettingsStore.clearNetFilterOffRequest(context)
                                    guardOn = false
                                    offRequest = null
                                },
                            )
                            phase == OffSwitchGuard.Phase.WAITING -> {
                                Text(
                                    stringResource(
                                        R.string.netdns_off_waiting,
                                        hoursMinutes(untilUnlock),
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(Space.sm))
                                GradientButton(
                                    text = stringResource(R.string.netdns_off_cancel),
                                    onClick = {
                                        SettingsStore.clearNetFilterOffRequest(context)
                                        offRequest = null
                                    },
                                )
                            }
                            else -> GradientButton(
                                text = stringResource(R.string.netdns_off_start),
                                onClick = { showGate = true },
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(Space.lg)) }
        }
    }

    if (showGate) {
        FrictionGate(
            title = stringResource(R.string.gate_netdns_title),
            blurb = stringResource(R.string.gate_netdns_blurb),
            detail = stringResource(R.string.gate_netdns_detail),
            confirmLabel = stringResource(R.string.gate_netdns_confirm),
            onDismiss = { showGate = false },
            onConfirm = {
                // Passing the gate does NOT switch anything off. It starts the wait, and the
                // filter is defended for every minute of it.
                SettingsStore.setNetFilterOffRequest(context, OFF_DELAY_MS, boot)
                offRequest = SettingsStore.netFilterOffRequest(context)
                showGate = false
            },
        )
    }
}

/** The wait before the guard can actually be dropped, and the window to do it in afterwards -
 *  the adult pack's numbers, because it is the same promise about the same kind of protection. */
private const val OFF_DELAY_MS = 24 * 60 * 60_000L
private const val OFF_WINDOW_MS = 15 * 60_000L

/** "3h 20m" / "12m" - a countdown he can act on, never raw millis. */
private fun hoursMinutes(ms: Long): String {
    val mins = ((ms + 59_999L) / 60_000L).toInt()
    return if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
}

@Composable
private fun StatusCard(state: FilterState) {
    val on = state == FilterState.FILTERING
    val color = when (state) {
        FilterState.FILTERING -> MaterialTheme.colorScheme.primary
        // "Can't tell" is not a failure of his, so it must not be painted like one.
        FilterState.CANT_TELL -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }
    val (title, body) = when (state) {
        FilterState.FILTERING -> R.string.netdns_status_on to R.string.netdns_status_on_body
        FilterState.ON_BUT_UNKNOWN ->
            R.string.netdns_status_unknown to R.string.netdns_status_unknown_body
        FilterState.OFF -> R.string.netdns_status_off to R.string.netdns_status_off_body
        FilterState.CANT_TELL ->
            R.string.netdns_status_unknown_device to R.string.netdns_status_unknown_device_body
    }
    AppCard(modifier = Modifier.testTag(NETDNS_STATUS_TAG)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (on) return@AppCard
    }
}

/** The hostname, big and selectable-looking, so a mistyped character is obvious before it becomes
 *  a phone with no working DNS. */
@Composable
private fun AddressBox() {
    Text(
        stringResource(R.string.netdns_address_label),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(Space.xs))
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(Space.md),
    ) {
        Text(
            NetworkFilter.RECOMMENDED,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    Spacer(Modifier.height(Space.sm))
    // Written after this exact filter silently blocked AppBlocker's own reporting server for six
    // days: family resolvers block dynamic-DNS domains as a category (they are a standard way to
    // evade filters), the app's own address happened to be one, and nothing anywhere said so.
    // The app now detects it — see HealthFacts' delivery facts — but the person switching the
    // filter on deserves to know the shape of the trade before it costs them anything.
    Text(
        stringResource(R.string.netdns_blocks_categories),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Card(titleRes: Int, vararg bodyRes: Int) {
    AppCard {
        Heading(stringResource(titleRes))
        for (b in bodyRes) {
            Spacer(Modifier.height(Space.sm))
            Text(
                stringResource(b),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
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

private fun copyAddress(context: Context) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("dns", NetworkFilter.RECOMMENDED))
    }
}

/**
 * Opens the Private DNS page, or the closest thing this phone has.
 *
 * ⚠️ **Where this setting lives is not standard.** Stock Android has a dedicated action from
 * Android 10; before that, and on several OEM skins, it is a row inside the network settings with
 * a vendor-specific route (Xiaomi files it under "Connection & sharing"). So this walks a list
 * rather than trusting one intent, exactly as the accessibility deep link does — and the steps on
 * the screen name the row, because **a button that lands on the wrong page is worse than no button**
 * (the v1.137 setup-guide lesson).
 */
private fun openPrivateDnsSettings(context: Context) {
    val candidates = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add("android.settings.PRIVATE_DNS_SETTINGS")
        add(Settings.ACTION_WIRELESS_SETTINGS)
        add(Settings.ACTION_SETTINGS)
    }
    for (action in candidates) {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
}
