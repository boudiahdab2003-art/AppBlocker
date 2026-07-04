package com.appblocker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblocker.data.SettingsStore

/**
 * A dedicated home for blocked words: add/remove words instantly (no Save), and choose extra
 * apps — beyond browsers, which are always covered — where those words should also be caught.
 */
@Composable
fun KeywordsScreen(
    strictActive: Boolean,
    onBack: () -> Unit,
    webVm: WebFilterViewModel = viewModel(),
    appsVm: AppListViewModel = viewModel(),
) {
    val context = LocalContext.current
    val saved by webVm.keywords.collectAsState()
    val apps by appsVm.apps.collectAsState()
    var newWord by remember { mutableStateOf("") }
    var scanApps by remember { mutableStateOf(SettingsStore.keywordScanApps(context)) }
    var showAppPicker by remember { mutableStateOf(false) }
    val ed = !strictActive // words can always be added; removal is locked during Strict Mode

    Box(Modifier.fillMaxSize().background(com.appblocker.ui.theme.AppGradients.background)) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = { EditorTopBar("Blocked words", onBack) },
        ) { padding ->
            LazyColumn(
                Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp),
            ) {
                item {
                    Text(
                        "Any web address or search containing one of these words is blocked in " +
                            "your browser. Add apps below to catch them there too.",
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

                // --- Also block inside chosen apps ---
                item {
                    Spacer(Modifier.padding(top = 20.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Apps, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Also block these words inside apps",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                    Text(
                        "Your browsers are always covered. Add apps like YouTube or TikTok to also " +
                            "block these words when they appear on screen there.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "Heads up: in a chosen app, a block triggers whenever a blocked word appears " +
                            "anywhere on screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                    )
                }

                items(scanApps.toList(), key = { it }) { pkg ->
                    val app = apps.firstOrNull { it.packageName == pkg }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(app?.label ?: pkg, Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyLarge)
                        IconButton(enabled = ed, onClick = {
                            scanApps = scanApps - pkg
                            SettingsStore.setKeywordScanApps(context, scanApps)
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove app",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                item {
                    Spacer(Modifier.padding(top = 8.dp))
                    NeutralButton(icon = Icons.Filled.Add, label = "Add apps") { showAppPicker = true }
                    Spacer(Modifier.padding(top = 24.dp))
                }
            }
        }
    }

    if (showAppPicker) {
        KeywordAppsSheet(
            appsVm = appsVm,
            initial = scanApps,
            onDismiss = { showAppPicker = false },
            onSave = { chosen ->
                scanApps = chosen
                SettingsStore.setKeywordScanApps(context, chosen)
                showAppPicker = false
            },
        )
    }
}

/** Bottom sheet to pick which installed apps also have blocked words matched inside them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeywordAppsSheet(
    appsVm: AppListViewModel,
    initial: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    val apps by appsVm.apps.collectAsState()
    val installed = remember(apps) {
        apps.filter { it.installed && it.packageName != context.packageName }
    }
    val selected = remember(initial) { initial.toMutableStateList() }
    // Open at full height so the Save button below the list is always reachable.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text("Block words inside these apps", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text("Pick apps where your blocked words should also be caught.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.padding(top = 8.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                items(installed, key = { it.packageName }) { app ->
                    val checked = selected.contains(app.packageName)
                    AppCheckRow(app, checked = checked, enabled = true) { on ->
                        if (on) selected.add(app.packageName) else selected.remove(app.packageName)
                    }
                }
            }
            Spacer(Modifier.padding(top = 12.dp))
            GradientButton(text = "Save", onClick = { onSave(selected.toSet()) },
                modifier = Modifier.fillMaxWidth())
        }
    }
}
