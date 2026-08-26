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
import com.appblocker.data.StrictEdits
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import com.appblocker.R

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
    // The website words live because their app is blocked. A word one of these already covers can
    // be removed during Strict Mode — see StrictEdits and BlockedWordRow.
    val siteWords by webVm.liveSiteWords.collectAsState()
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
            topBar = { EditorTopBar(stringResource(R.string.blocked_words), onBack) },
        ) { padding ->
            LazyColumn(
                Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.words_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newWord, onValueChange = { newWord = it },
                            placeholder = { Text(stringResource(R.string.words_add_placeholder)) },
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
                            Text(stringResource(R.string.words_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    items(saved, key = { it }) { word ->
                        BlockedWordRow(
                            word = word,
                            covering = remember(word, siteWords) {
                                StrictEdits.coveringSiteWord(word, siteWords)
                            },
                            strictActive = strictActive,
                            onRemove = { webVm.setKeywords(saved - word) },
                        )
                    }
                }

                item {
                    Spacer(Modifier.padding(top = 20.dp))
                    // Turning these OFF weakens the filter, so — like word removal — they're
                    // locked during Strict Mode. Turning them back ON is always allowed.
                    ToggleRow(
                        icon = Icons.Filled.NoAdultContent,
                        title = stringResource(R.string.words_adult_pack),
                        desc = stringResource(R.string.words_adult_pack_desc),
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
                                    stringResource(
                                        R.string.words_pack_unlocked,
                                        fmtHoursMinutes(untilExpiry),
                                    )
                                } else {
                                    stringResource(
                                        R.string.words_pack_waiting,
                                        fmtHoursMinutes(untilUnlock),
                                    )
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                offRequest = null
                                SettingsStore.clearAdultPackOffRequest(context)
                            }) { Text(stringResource(R.string.common_cancel)) }
                        }
                    }
                    Spacer(Modifier.padding(top = 8.dp))
                    ToggleRow(
                        icon = Icons.Filled.Apps,
                        title = stringResource(R.string.words_everywhere),
                        desc = stringResource(R.string.words_everywhere_desc),
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
                title = context.getString(R.string.gate_adult_title),
                blurb = context.getString(R.string.gate_adult_blurb),
                detail = context.getString(R.string.gate_adult_detail),
                confirmLabel = context.getString(R.string.gate_adult_confirm),
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
