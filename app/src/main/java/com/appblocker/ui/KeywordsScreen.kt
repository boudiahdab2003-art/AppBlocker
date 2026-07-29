package com.appblocker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NoAdultContent
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblocker.data.DeviceBoot
import com.appblocker.data.OffSwitchGuard
import com.appblocker.data.SettingsStore
import kotlinx.coroutines.delay

/**
 * A dedicated home for blocked words: add/remove words instantly (no Save). Words are matched
 * in every app by default (one toggle falls back to browsers-only).
 */
@Composable
fun KeywordsScreen(
    strictActive: Boolean,
    onBack: () -> Unit,
    webVm: WebFilterViewModel = viewModel(),
) {
    val context = LocalContext.current
    val saved by webVm.keywords.collectAsState()
    var newWord by remember { mutableStateOf("") }
    var everywhere by remember { mutableStateOf(SettingsStore.keywordsEverywhere(context)) }
    var adultPack by remember { mutableStateOf(SettingsStore.adultWordsPack(context)) }
    var showDisableGate by remember { mutableStateOf(false) }
    val ed = !strictActive // words can always be added; removal is locked during Strict Mode

    // 24-hour cooling-off on turning the adult pack off: passing the gate only REQUESTS the
    // off. The pack keeps filtering for OFF_DELAY_MS; then the switch works for OFF_WINDOW_MS,
    // after which the request expires and the gate starts over. Cancelling is always allowed.
    // Both moments are derived from one clock-proof record rather than compared against
    // System.currentTimeMillis(): winding the device clock forward a day used to skip the whole
    // cooling-off, which made the app's strongest protection its cheapest to switch off.
    val boot = remember { DeviceBoot.count(context) }
    var offRequest by remember { mutableStateOf(SettingsStore.adultPackOffRequest(context)) }
    // Ticks the countdown; `remaining` is what actually decides, so this only drives redraws.
    var tick by remember { mutableStateOf(0) }
    val untilUnlock = offRequest?.remaining(boot) ?: 0L
    val untilExpiry = offRequest?.remaining(boot, extraMs = OFF_WINDOW_MS) ?: 0L
    // The SAME phase machine the off-switch guard uses — deliberately called rather than
    // re-derived here, even though the name says "guard". This screen used to decide with
    // `untilUnlock <= 0L` alone, which never asked whether the window had since **closed**: a
    // request whose 24 hours were served days ago still read as "you may switch it off now". The
    // 30-second ticker below cleared lapsed requests, so the hole was usually short — but
    // recomposition stops while the app is in the background, so returning to this screen after
    // a missed window handed back an open switch for up to another 30 seconds.
    //
    // Two copies of one state machine, one of them incomplete, is the first bug shape in
    // docs/BLOCKING_INVARIANTS.md. `OffSwitchGuard.phase` is unit-tested, including the
    // lapsed-window case this screen got wrong; there is now one implementation.
    val offReady = OffSwitchGuard.phase(
        hasRequest = offRequest != null,
        untilUnlock = untilUnlock,
        untilExpiry = untilExpiry,
    ) == OffSwitchGuard.Phase.OPEN
    LaunchedEffect(offRequest, tick) {
        if (offRequest == null) return@LaunchedEffect
        if (untilExpiry <= 0L) {
            // The whole request lapsed — the gate starts over.
            offRequest = null
            SettingsStore.clearAdultPackOffRequest(context)
            return@LaunchedEffect
        }
        delay(30_000)
        tick++
    }

    Box(Modifier.fillMaxSize().background(com.appblocker.ui.theme.appBackground())) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = { EditorTopBar("Blocked words", onBack) },
        ) { padding ->
            LazyColumn(
                Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp),
            ) {
                item {
                    Text(
                        "Add words you never want to see. In your browser they block matching " +
                            "sites and searches; in any other app, the moment one shows up " +
                            "on screen it's blocked.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newWord, onValueChange = { newWord = it },
                            placeholder = { Text("Add a word") },
                            singleLine = true,
                            shape = RoundedCornerShape(28.dp),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(enabled = newWord.isNotBlank(), onClick = {
                            val w = newWord.trim().lowercase()
                            if (w.isNotEmpty() && w !in saved) webVm.setKeywords(saved + w)
                            newWord = ""
                        }) {
                            Icon(Icons.Filled.Add, contentDescription = "Add",
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(Modifier.padding(top = 8.dp))
                }

                if (saved.isEmpty()) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(16.dp),
                        ) {
                            Text("No blocked words yet — add a word like “casino” or “betting”.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    items(saved, key = { it }) { word ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(word, Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyLarge)
                            IconButton(enabled = ed, onClick = { webVm.setKeywords(saved - word) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                item {
                    Spacer(Modifier.padding(top = 20.dp))
                    // Turning these OFF weakens the filter, so — like word removal — they're
                    // locked during Strict Mode. Turning them back ON is always allowed.
                    ToggleRow(
                        icon = Icons.Filled.NoAdultContent,
                        title = "Adult content pack",
                        desc = "Hundreds of pornographic words — English and Arabic — blocked " +
                            "automatically, on top of your own list.",
                        checked = adultPack,
                        enabled = ed || !adultPack,
                        onChange = { turnOn ->
                            if (turnOn) {
                                // Turning protection ON is always instant — and wipes any
                                // pending turn-off request.
                                adultPack = true
                                SettingsStore.setAdultWordsPack(context, true)
                                offRequest = null
                                SettingsStore.clearAdultPackOffRequest(context)
                            } else if (offReady) {
                                // Gate passed AND the 24-hour cooling-off served — the off
                                // finally happens.
                                adultPack = false
                                SettingsStore.setAdultWordsPack(context, false)
                                offRequest = null
                                SettingsStore.clearAdultPackOffRequest(context)
                            } else if (offRequest == null) {
                                // Turning it OFF starts at the type-and-wait gate. The switch
                                // stays visibly ON; confirming only starts the cooling-off.
                                showDisableGate = true
                            }
                            // else: request pending — the row below shows the remaining wait.
                        },
                    )
                    if (offRequest != null) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (offReady) {
                                    "You can turn the pack off now — tap the switch. This " +
                                        "unlock expires in ${fmtHoursMinutes(untilExpiry)}."
                                } else {
                                    "Turn-off requested. The pack keeps protecting you for " +
                                        "another ${fmtHoursMinutes(untilUnlock)}."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                offRequest = null
                                SettingsStore.clearAdultPackOffRequest(context)
                            }) { Text("Cancel") }
                        }
                    }
                    Spacer(Modifier.padding(top = 8.dp))
                    ToggleRow(
                        icon = Icons.Filled.Apps,
                        title = "Block these words in every app",
                        desc = "Recommended. When off, words are only blocked in your browser.",
                        checked = everywhere,
                        enabled = ed || !everywhere,
                        onChange = {
                            everywhere = it
                            SettingsStore.setKeywordsEverywhere(context, it)
                        },
                    )
                    Spacer(Modifier.padding(top = 24.dp))
                }
            }
        }

        // Drawn on top of the whole screen. NOT a Dialog: dialog windows report zero insets
        // on this device (see DurationPickerDialog in WheelPicker.kt), so the keyboard would
        // cover the challenge field with no way to detect it. In the activity window,
        // safeDrawingPadding keeps the field above the keyboard (CoachChatScreen pattern).
        if (showDisableGate) {
            FrictionGate(
                title = "Turn off adult protection",
                blurb = "This lowers your guard. To be sure it's really you and really " +
                    "deliberate, type the paragraph below — you can't paste it — before the " +
                    "clock runs out. Miss it and you get a fresh paragraph and a fresh clock. " +
                    "Even then the pack stays on for another 24 hours; only after that can you " +
                    "flip the switch off.",
                confirmLabel = "Start the 24-hour wait",
                dismissLabel = "Keep it on",
                onDismiss = { showDisableGate = false },
                onConfirm = {
                    // Passing the gate does NOT turn the pack off — it starts the 24-hour
                    // cooling-off. The pack keeps filtering until it's served and the owner
                    // flips the switch within the follow-up window.
                    SettingsStore.setAdultPackOffRequest(context, OFF_DELAY_MS, boot)
                    offRequest = SettingsStore.adultPackOffRequest(context)
                    showDisableGate = false
                },
            )
        }
    }
}

/** Cooling-off after passing the gate before the pack can actually be switched off, and the
 *  window to do it in afterwards — miss it and the request expires, gate and all. */
private const val OFF_DELAY_MS = 24 * 60 * 60_000L
private const val OFF_WINDOW_MS = 24 * 60 * 60_000L
