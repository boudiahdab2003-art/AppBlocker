package com.appblocker.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GetApp
import androidx.compose.material.icons.filled.NoAdultContent
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
    // Blocklist and allowlist selections are staged independently so flipping the mode chooser
    // never loses either one (matches AppBlock).
    val selectedBlock = remember { mutableStateListOf<String>() }
    val selectedAllow = remember { mutableStateListOf<String>() }
    var editedApps by remember { mutableStateOf(false) }
    LaunchedEffect(apps) {
        if (!editedApps && apps.isNotEmpty()) {
            selectedBlock.clear(); selectedBlock.addAll(apps.filter { it.isBlocked }.map { it.packageName })
            selectedAllow.clear(); selectedAllow.addAll(apps.filter { it.isAllowed }.map { it.packageName })
        }
    }
    var allowlist by remember { mutableStateOf(SettingsStore.quickBlockAllowlist(context)) }
    var showModeSheet by remember { mutableStateOf(false) }
    // The selection the picker is currently editing.
    val selected = if (allowlist) selectedAllow else selectedBlock
    val keywords = remember { mutableStateListOf<String>() }
    var editedKw by remember { mutableStateOf(false) }
    LaunchedEffect(savedKeywords) {
        if (!editedKw) { keywords.clear(); keywords.addAll(savedKeywords) }
    }
    var newWord by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    // Collapsible sections (default open; remembered across rotation / tab switches).
    var appsOpen by rememberSaveable { mutableStateOf(true) }
    // Which app categories are expanded (collapsed by default, AppBlock-style).
    var expandedCats by rememberSaveable { mutableStateOf(listOf<String>()) }
    var preBlockOpen by rememberSaveable { mutableStateOf(false) }
    var webOpen by rememberSaveable { mutableStateOf(true) }
    var adult by remember { mutableStateOf(SettingsStore.blockAdult(context)) }
    var addNew by remember { mutableStateOf(SettingsStore.addNewApps(context)) }
    var purchases by remember { mutableStateOf(SettingsStore.blockPurchases(context)) }
    var unsupported by remember { mutableStateOf(SettingsStore.blockUnsupportedBrowsers(context)) }
    var ytShorts by remember { mutableStateOf(SettingsStore.blockYoutubeShorts(context)) }

    val ed = !strictActive // false locks "weakening" edits during Strict Mode
    val shownApps = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter { it.label.contains(query.trim(), ignoreCase = true) }
    }
    val installedApps = remember(shownApps) { shownApps.filter { it.installed } }
    val preBlockApps = remember(shownApps) { shownApps.filter { !it.installed } }

    fun save() {
        // Commit both selections together (the inactive one is unchanged) so switching modes is
        // lossless and an app toggled in both lists can't clobber itself.
        appsVm.commitQuickBlock(selectedBlock.toSet(), selectedAllow.toSet())
        SettingsStore.setQuickBlockAllowlist(context, allowlist)
        webVm.setKeywords(keywords.toList())
        SettingsStore.setBlockAdult(context, adult)
        SettingsStore.setAddNewApps(context, addNew)
        SettingsStore.setBlockPurchases(context, purchases)
        SettingsStore.setBlockUnsupportedBrowsers(context, unsupported)
        SettingsStore.setBlockYoutubeShorts(context, ytShorts)
        onBack()
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = { EditorTopBar("Quick Block", onBack) },
        bottomBar = {
            // Save is allowed during Strict — you can add blocks, just not remove them.
            GradientButton(text = "Save", onClick = ::save,
                modifier = Modifier.navigationBarsPadding().padding(16.dp))
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Spacer(Modifier.padding(top = 4.dp))
                // Blocking-mode header: title + a tappable Blocklist/Allowlist chip (AppBlock-style).
                BlockingModeHeader(
                    allowlist = allowlist,
                    // The chooser can't be used to weaken protection mid-Strict.
                    enabled = !strictActive,
                    onClick = { if (!strictActive) showModeSheet = true },
                )
                Spacer(Modifier.padding(top = 4.dp))
                Text(
                    if (allowlist) "Select apps you want to allow. All others will be blocked."
                    else "Select apps, sites or words you want to block.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(top = 12.dp))
                if (!AccessibilityUtil.isEnabled(context)) {
                    ProtectionBanner(context); Spacer(Modifier.padding(top = 12.dp))
                }
                if (strictActive) {
                    Text(
                        if (allowlist) "🔒 Strict Mode — you can remove allowed apps, but not add them."
                        else "🔒 Strict Mode — you can add blocks, but not remove them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.padding(top = 12.dp))
                }
                val installedSelected = apps.count { it.installed && selected.contains(it.packageName) }
                CollapsibleHeader(Icons.Filled.Apps, "Apps", installedSelected, appsOpen) { appsOpen = !appsOpen }
                if (appsOpen) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        placeholder = { Text("Search apps") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true, enabled = ed,
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    )
                }
            }
            if (appsOpen) {
                if (loading) {
                    item { Text("Loading apps…", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp)) }
                } else {
                    // Apps grouped into collapsible categories (flat filtered list while searching).
                    categorizedAppItems(
                        apps = apps.filter { it.installed },
                        selected = selected,
                        expandedCats = expandedCats.toSet(),
                        query = query,
                        // During Strict you can only *tighten*: in Blocklist mode add a block
                        // (can't uncheck); in Allowlist mode remove an allowed app (can't check).
                        rowEnabled = { checked -> if (allowlist) ed || checked else ed || !checked },
                        onToggleExpand = { cat ->
                            expandedCats = if (cat.name in expandedCats) expandedCats - cat.name
                            else expandedCats + cat.name
                        },
                        onToggle = { app, on ->
                            if (strictActive && (if (allowlist) on else !on)) return@categorizedAppItems
                            editedApps = true
                            if (on) selected.add(app.packageName) else selected.remove(app.packageName)
                        },
                        onSelectAll = { catApps ->
                            // Selecting a whole category adds; in Allowlist that loosens, so it's
                            // locked during Strict.
                            if (strictActive && allowlist) return@categorizedAppItems
                            editedApps = true
                            catApps.forEach { if (!selected.contains(it.packageName)) selected.add(it.packageName) }
                        },
                        onClearAll = { catApps ->
                            // Clearing a category removes; in Blocklist that loosens, so it's locked
                            // during Strict.
                            if (strictActive && !allowlist) return@categorizedAppItems
                            editedApps = true
                            catApps.forEach { selected.remove(it.packageName) }
                        },
                        extraUnder = { app ->
                            // Nested "Shorts" sub-row right under YouTube (blocks only the Shorts
                            // feed). Blocklist-only — in Allowlist an allowed app is fully allowed.
                            if (!allowlist && app.packageName == "com.google.android.youtube") {
                                {
                                    ShortsSubRow(icon = app.icon, checked = ytShorts, enabled = ed || !ytShorts) { on ->
                                        if (strictActive && !on) return@ShortsSubRow
                                        ytShorts = on
                                    }
                                }
                            } else null
                        },
                    )
                }
            }

            // Hypothetical apps & Websites/words are Blocklist concepts — hidden in Allowlist mode.
            if (!allowlist) {
                item {
                    Spacer(Modifier.padding(top = 16.dp))
                    val preBlockCount = apps.count { !it.installed && selected.contains(it.packageName) }
                    CollapsibleHeader(Icons.Filled.GetApp, "Hypothetical apps", preBlockCount, preBlockOpen) {
                        preBlockOpen = !preBlockOpen
                    }
                    if (preBlockOpen) {
                        Text("Apps you don't have yet. Block them now and they'll be blocked the moment you install them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp))
                    }
                }
                if (preBlockOpen) {
                    items(preBlockApps, key = { it.packageName }) { app ->
                        val isChecked = selected.contains(app.packageName)
                        AppCheckRow(app, checked = isChecked, enabled = ed || !isChecked,
                            subtitle = "Not installed yet") { on ->
                            if (strictActive && !on) return@AppCheckRow
                            editedApps = true
                            if (on) selected.add(app.packageName) else selected.remove(app.packageName)
                        }
                    }
                }

                item {
                    Spacer(Modifier.padding(top = 16.dp))
                    CollapsibleHeader(Icons.Filled.Web, "Websites & words", keywords.size, webOpen) { webOpen = !webOpen }
                    if (webOpen) {
                        Text("Any web address or search containing one of these is blocked. " +
                            "Also on the Blocked words screen (Blocking tab), where you can block them inside apps too.",
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
                }
                if (webOpen) {
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
                }
            }

            item {
                Spacer(Modifier.padding(top = 20.dp))
                SectionHeader(Icons.Filled.Block, "Extra options", null)
                Spacer(Modifier.padding(top = 4.dp))
                ToggleRow(Icons.Filled.NoAdultContent, "Porn sites blocking",
                    "Detects and blocks adult sites in your browsers.", adult, ed) { adult = it }
                // "Add newly installed apps" is a Blocklist concept — in Allowlist mode a new app
                // is already blocked (it isn't allowed), so the toggle is hidden.
                if (!allowlist) {
                    ToggleRow(Icons.Filled.GetApp, "Add newly installed apps",
                        "Newly installed apps are automatically blocked.", addNew, ed) { addNew = it }
                }
                ToggleRow(Icons.Filled.ShoppingBasket, "In-app purchases blocking",
                    "Blocks the Google Play purchase prompt in games and apps.", purchases, ed) { purchases = it }
                ToggleRow(Icons.Filled.Web, "Block unsupported browsers",
                    "Blocks browsers we can't filter (e.g. Brave) so they can't bypass website blocking.",
                    unsupported, ed) { unsupported = it }
                Spacer(Modifier.padding(top = 16.dp))
            }
        }
    }

    if (showModeSheet) {
        BlockingModeSheet(
            allowlist = allowlist,
            onDismiss = { showModeSheet = false },
            onPick = { pickAllowlist ->
                allowlist = pickAllowlist
                showModeSheet = false
            },
        )
    }
}

/** The "Blocking" section title with a tappable mode chip on the right (Blocklist / Allowlist). */
@Composable
private fun BlockingModeHeader(allowlist: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text("Blocking", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Row(
            Modifier.clip(RoundedCornerShape(20.dp)).clickable(enabled = enabled) { onClick() }
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (allowlist) Icons.Filled.Star else Icons.Filled.Block, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (allowlist) "Allowlist" else "Blocklist",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            if (enabled) {
                Icon(Icons.Filled.ExpandMore, contentDescription = "Change blocking mode",
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Bottom sheet that picks the Quick Block mode — mirrors AppBlock's "Blocking mode" chooser. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlockingModeSheet(
    allowlist: Boolean,
    onDismiss: () -> Unit,
    onPick: (Boolean) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Blocking mode", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text("Choose the variant that works best for you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.padding(top = 12.dp))
            ModeOption(Icons.Filled.Block, "Blocklist",
                "Select apps, sites or words you want to block.",
                selected = !allowlist) { onPick(false) }
            Spacer(Modifier.padding(top = 8.dp))
            ModeOption(Icons.Filled.Star, "Allowlist",
                "Select apps you want to allow. All others will be blocked.",
                selected = allowlist) { onPick(true) }
        }
    }
}

@Composable
private fun ModeOption(
    icon: ImageVector, title: String, desc: String, selected: Boolean, onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .then(
                if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                else Modifier,
            )
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(desc, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
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
internal fun ToggleRow(
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

/** Indented "Shorts" child row shown directly under YouTube — blocks only the Shorts feed. */
@Composable
private fun ShortsSubRow(
    icon: Bitmap?,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled) { onToggle(!checked) }
            .padding(start = 40.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.SubdirectoryArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        if (icon != null) {
            Image(icon.asImageBitmap(), null, Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
        } else {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Text("Shorts", style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                .padding(horizontal = 6.dp, vertical = 1.dp),
        ) {
            Text("BETA", style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.weight(1f))
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun ProtectionBanner(context: Context) {
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
