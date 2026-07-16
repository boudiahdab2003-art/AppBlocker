package com.appblocker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NoAdultContent
import androidx.compose.material3.AlertDialog
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

    if (showDisableGate) {
        DisablePackDialog(
            onDismiss = { showDisableGate = false },
            onConfirm = {
                adultPack = false
                SettingsStore.setAdultWordsPack(context, false)
                showDisableGate = false
            },
        )
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
                                // Turning protection ON is always instant.
                                adultPack = true
                                SettingsStore.setAdultWordsPack(context, true)
                            } else {
                                // Turning it OFF must pass the type-and-wait gate. Don't flip the
                                // switch yet — it stays visibly ON until the dialog is confirmed.
                                showDisableGate = true
                            }
                        },
                    )
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
    }
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
 * ~60-word paragraph exactly (pasting disabled) AND wait out a 2-minute countdown before Confirm
 * unlocks. Regenerates the paragraph and resets the timer every time it opens.
 */
@Composable
private fun DisablePackDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val phrase = remember { (1..CHALLENGE_LEN).joinToString(" ") { CHALLENGE_WORDS.random() } }
    var input by remember { mutableStateOf("") }
    var remaining by remember { mutableStateOf(GATE_SECONDS) }
    LaunchedEffect(Unit) {
        while (remaining > 0) { delay(1000); remaining-- }
    }
    val matched = input.trimEnd() == phrase
    val ready = matched && remaining == 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Turn off adult protection?") },
        text = {
            Column {
                Text(
                    "This lowers your guard. To be sure it's really you and really deliberate, " +
                        "type the paragraph below exactly — you can't paste it — and wait for the timer.",
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
                        keyboardOptions = KeyboardOptions(autoCorrect = false),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ready, onClick = onConfirm) {
                Text(
                    when {
                        remaining > 0 -> "Wait ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}…"
                        else -> "Turn it off"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Keep it on") }
        },
    )
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
