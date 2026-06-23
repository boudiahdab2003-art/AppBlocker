package com.appblocker.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GetApp
import androidx.compose.material.icons.filled.NoAdultContent
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblocker.data.SettingsStore
import com.appblocker.service.AccessibilityUtil

/**
 * Quick Block editor — everything (apps, websites & words, extra options) edits a LOCAL
 * staged copy; nothing is written until Save. Back discards. Read-only in Strict Mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockEditorScreen(
    strictActive: Boolean,
    onBack: () -> Unit,
    appsVm: AppListViewModel = viewModel(),
    webVm: WebFilterViewModel = viewModel(),
) {
    val context = LocalContext.current
    val apps by appsVm.apps.collectAsState()
    val loading by appsVm.loading.collectAsState()
    val savedKeywords by webVm.keywords.collectAsState()

    // --- staged state (seeded from current data, mirrors until the user edits) ---
    val selected = remember { mutableStateListOf<String>() }
    var editedApps by remember { mutableStateOf(false) }
    LaunchedEffect(apps) {
        if (!editedApps && apps.isNotEmpty()) {
            selected.clear(); selected.addAll(apps.filter { it.isBlocked }.map { it.packageName })
        }
    }
    val keywords = remember { mutableStateListOf<String>() }
    var editedKw by remember { mutableStateOf(false) }
    LaunchedEffect(savedKeywords) {
        if (!editedKw) { keywords.clear(); keywords.addAll(savedKeywords) }
    }
    var newWord by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var adult by remember { mutableStateOf(SettingsStore.blockAdult(context)) }
    var addNew by remember { mutableStateOf(SettingsStore.addNewApps(context)) }
    var purchases by remember { mutableStateOf(SettingsStore.blockPurchases(context)) }
    var unsupported by remember { mutableStateOf(SettingsStore.blockUnsupportedBrowsers(context)) }

    val ed = !strictActive // false locks "weakening" edits during Strict Mode
    val shownApps = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter { it.label.contains(query.trim(), ignoreCase = true) }
    }

    fun save() {
        appsVm.commitBlocked(selected.toSet())
        webVm.setKeywords(keywords.toList())
        SettingsStore.setBlockAdult(context, adult)
        SettingsStore.setAddNewApps(context, addNew)
        SettingsStore.setBlockPurchases(context, purchases)
        SettingsStore.setBlockUnsupportedBrowsers(context, unsupported)
        onBack()
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = { EditorTopBar("Quick Block", onBack) },
        bottomBar = {
            // Save is allowed during Strict — you can add blocks, just not remove them.
            GradientButton(text = "Save", onClick = ::save, modifier = Modifier.padding(16.dp))
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Spacer(Modifier.padding(top = 4.dp))
                Text("Choose what to block. Nothing is applied until you tap Save.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.padding(top = 12.dp))
                if (!AccessibilityUtil.isEnabled(context)) {
                    ProtectionBanner(context); Spacer(Modifier.padding(top = 12.dp))
                }
                if (strictActive) {
                    Text("🔒 Strict Mode — you can add blocks, but not remove them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.padding(top = 12.dp))
                }
                SectionHeader(Icons.Filled.Apps, "Apps", selected.size)
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    placeholder = { Text("Search apps") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true, enabled = ed,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                )
            }
            if (loading) {
                item { Text("Loading apps…", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp)) }
            } else {
                items(shownApps, key = { it.packageName }) { app ->
                    val isChecked = selected.contains(app.packageName)
                    // During Strict you can check (add) an app, but not uncheck (remove) one.
                    AppCheckRow(app, checked = isChecked, enabled = ed || !isChecked) { on ->
                        if (strictActive && !on) return@AppCheckRow
                        editedApps = true
                        if (on) selected.add(app.packageName) else selected.remove(app.packageName)
                    }
                }
            }

            item {
                Spacer(Modifier.padding(top = 16.dp))
                SectionHeader(Icons.Filled.Web, "Websites & words", keywords.size)
                Text("Any web address or search containing one of these is blocked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newWord, onValueChange = { newWord = it },
                        placeholder = { Text("Add a word or site") },
                        singleLine = true, enabled = true, // adding is allowed during Strict
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(enabled = newWord.isNotBlank(), onClick = {
                        editedKw = true
                        val w = newWord.trim().lowercase()
                        if (w.isNotEmpty() && w !in keywords) keywords.add(w)
                        newWord = ""
                    }) { Icon(Icons.Filled.Add, contentDescription = "Add",
                        tint = MaterialTheme.colorScheme.primary) }
                }
            }
            items(keywords, key = { it }) { word ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(word, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge)
                    IconButton(enabled = ed, onClick = { editedKw = true; keywords.remove(word) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item {
                Spacer(Modifier.padding(top = 20.dp))
                SectionHeader(Icons.Filled.Block, "Extra options", null)
                Spacer(Modifier.padding(top = 4.dp))
                ToggleRow(Icons.Filled.NoAdultContent, "Porn sites blocking",
                    "Detects and blocks adult sites in your browsers.", adult, ed) { adult = it }
                ToggleRow(Icons.Filled.GetApp, "Add newly installed apps",
                    "Newly installed apps are automatically blocked.", addNew, ed) { addNew = it }
                ToggleRow(Icons.Filled.ShoppingBasket, "In-app purchases blocking",
                    "Blocks the purchase prompt in games and apps.", purchases, ed) { purchases = it }
                ToggleRow(Icons.Filled.Web, "Block unsupported browsers",
                    "Blocks browsers we can't filter (e.g. Brave) so they can't bypass website blocking.",
                    unsupported, ed) { unsupported = it }
                Spacer(Modifier.padding(top = 16.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(icon: ImageVector, title: String, count: Int?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        if (count != null && count > 0) {
            Text("$count", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ToggleRow(
    icon: ImageVector, title: String, desc: String,
    checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface).padding(14.dp).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(desc, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun ProtectionBanner(context: android.content.Context) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFFFB020),
                modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text("Protection is off", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.padding(top = 6.dp))
        Text("Turn on the blocker so your choices actually block.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.padding(top = 12.dp))
        GradientButton(text = "Turn on protection", onClick = {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        })
    }
}
