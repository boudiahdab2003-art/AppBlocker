package com.appblocker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.Composable
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
import com.appblocker.data.SettingsStore

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
    val ed = !strictActive // words can always be added; removal is locked during Strict Mode

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
                        "Add words you never want to see. The moment one shows up on screen — " +
                            "in your browser or in any other app — the screen is blocked.",
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
                        onChange = {
                            adultPack = it
                            SettingsStore.setAdultWordsPack(context, it)
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
