package com.appblocker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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
    var offRequestAt by remember { mutableStateOf(SettingsStore.adultPackOffRequestedAt(context)) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(offRequestAt) {
        while (offRequestAt > 0L) {
            now = System.currentTimeMillis()
            if (now >= offRequestAt + OFF_DELAY_MS + OFF_WINDOW_MS) {
                offRequestAt = 0L
                SettingsStore.setAdultPackOffRequestedAt(context, 0L)
                break
            }
            delay(30_000)
        }
    }
    val offUnlockAt = offRequestAt + OFF_DELAY_MS
    val offReady = offRequestAt > 0L && now >= offUnlockAt

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
                                offRequestAt = 0L
                                SettingsStore.setAdultPackOffRequestedAt(context, 0L)
                            } else if (offReady) {
                                // Gate passed AND the 24-hour cooling-off served — the off
                                // finally happens.
                                adultPack = false
                                SettingsStore.setAdultWordsPack(context, false)
                                offRequestAt = 0L
                                SettingsStore.setAdultPackOffRequestedAt(context, 0L)
                            } else if (offRequestAt == 0L) {
                                // Turning it OFF starts at the type-and-wait gate. The switch
                                // stays visibly ON; confirming only starts the cooling-off.
                                showDisableGate = true
                            }
                            // else: request pending — the row below shows the remaining wait.
                        },
                    )
                    if (offRequestAt > 0L) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (offReady) {
                                    "You can turn the pack off now — tap the switch. This " +
                                        "unlock expires in ${fmtHm(offRequestAt + OFF_DELAY_MS + OFF_WINDOW_MS - now)}."
                                } else {
                                    "Turn-off requested. The pack keeps protecting you for " +
                                        "another ${fmtHm(offUnlockAt - now)}."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                offRequestAt = 0L
                                SettingsStore.setAdultPackOffRequestedAt(context, 0L)
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
            DisablePackGate(
                onDismiss = { showDisableGate = false },
                onConfirm = {
                    // Passing the gate does NOT turn the pack off — it starts the 24-hour
                    // cooling-off. The pack keeps filtering until it's served and the owner
                    // flips the switch within the follow-up window.
                    offRequestAt = System.currentTimeMillis()
                    SettingsStore.setAdultPackOffRequestedAt(context, offRequestAt)
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

/** "23h 40m" / "35m" for countdown rows (rounded up so it never shows 0m while pending). */
private fun fmtHm(ms: Long): String {
    val m = (ms.coerceAtLeast(0L) + 59_999) / 60_000
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}

/** Length of the random paragraph you must type, and the wait before Confirm unlocks. */
private const val CHALLENGE_LEN = 60
private const val GATE_SECONDS = 120

private val CHALLENGE_WORDS = listOf(
    "river", "table", "green", "stone", "quiet", "paper", "cloud", "light", "grass", "under",
    "north", "chair", "water", "bread", "sugar", "plant", "field", "house", "music", "night",
    "ocean", "candle", "window", "yellow", "orange", "purple", "garden", "silver", "planet",
    "forest", "bridge", "pocket", "shadow", "mirror", "pencil", "button", "ticket", "engine",
    "market", "letter", "circle", "square", "handle", "corner", "pillow", "carpet", "basket",
    "kitchen", "morning", "evening", "picture", "journey", "weather", "compass", "lantern",
    "harbor", "meadow", "valley", "pebble", "willow", "copper", "cotton", "ember", "maple",
    "ripple", "sparrow", "cedar", "pewter", "amber", "flint", "thistle", "clover", "birch",
)

/**
 * Friction gate for switching the Adult content pack off outside Strict Mode: type a fresh random
 * ~60-word paragraph (pasting disabled) AND wait out a 2-minute countdown before Confirm unlocks.
 * Regenerates the paragraph and resets the timer every time it opens. Confirming doesn't turn the
 * pack off — it starts the 24-hour cooling-off (see OFF_DELAY_MS in KeywordsScreen).
 *
 * A full screen in the activity window, deliberately NOT a Dialog: dialog windows report zero
 * insets on the owner's device (see DurationPickerDialog in WheelPicker.kt), which left the
 * keyboard sitting on top of the challenge field — typing was invisible.
 */
@Composable
private fun DisablePackGate(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val phrase = remember { (1..CHALLENGE_LEN).joinToString(" ") { CHALLENGE_WORDS.random() } }
    var input by remember { mutableStateOf("") }
    var remaining by remember { mutableStateOf(GATE_SECONDS) }
    LaunchedEffect(Unit) {
        while (remaining > 0) { delay(1000); remaining-- }
    }
    // Forgiving compare: a capital letter or stray double space must not silently keep the
    // button locked — typing all 60 words is the friction, not transcription perfection.
    val matched = input.trim().replace(Regex("\\s+"), " ").equals(phrase, ignoreCase = true)
    val ready = matched && remaining == 0

    BackHandler { onDismiss() }
    // Background BEFORE safeDrawingPadding so the app color still paints behind the system
    // bars; safeDrawing includes the keyboard, keeping the field and buttons above it.
    Column(
        Modifier.fillMaxSize().background(com.appblocker.ui.theme.appBackground())
            .safeDrawingPadding(),
    ) {
        EditorTopBar("Turn off adult protection", onBack = onDismiss)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            Text(
                "This lowers your guard. To be sure it's really you and really deliberate, " +
                    "type the paragraph below — you can't paste it — and wait for the timer. " +
                    "Even then the pack stays on for another 24 hours; only after that can " +
                    "you flip the switch off.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(top = 12.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
            ) {
                Text(phrase, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.padding(top = 12.dp))
            // A no-op text toolbar removes the cut/copy/paste popup entirely, so the paragraph
            // must be hand-typed (soft keyboards have no paste key).
            CompositionLocalProvider(LocalTextToolbar provides NoPasteToolbar) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    placeholder = { Text("Type the paragraph here") },
                    singleLine = false,
                    keyboardOptions = KeyboardOptions(
                        autoCorrect = false,
                        capitalization = KeyboardCapitalization.None,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                )
            }
            if (input.isNotBlank()) {
                Text(
                    if (matched) "Matches ✓" else "Doesn't match yet — keep going",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (matched) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.padding(top = 16.dp))
        }
        GradientButton(
            text = if (remaining > 0) {
                "Wait ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}…"
            } else "Start the 24-hour wait",
            enabled = ready,
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp),
        )
        TextButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 4.dp, bottom = 12.dp),
        ) { Text("Keep it on") }
    }
}

/** Text toolbar that shows nothing — used to block paste (and copy/cut) on the challenge field. */
private val NoPasteToolbar = object : TextToolbar {
    override val status: TextToolbarStatus = TextToolbarStatus.Hidden
    override fun hide() {}
    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {}
}
