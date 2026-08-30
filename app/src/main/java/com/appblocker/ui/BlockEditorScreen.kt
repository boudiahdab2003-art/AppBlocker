package com.appblocker.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblocker.data.SettingsStore
import com.appblocker.data.StrictEdits
import com.appblocker.service.AccessibilityUtil
import androidx.compose.ui.res.stringResource
import com.appblocker.R

/** The editor's internal pages. The picker drill-ins stay local (not top-level Overlays) so the
 *  staged selections survive navigating in and out. */
private enum class EditorPage { SUMMARY, APPS, WEBSITES, MODE }

/**
 * Quick Block editor — everything (apps, websites & words, extra options) edits a LOCAL
 * staged copy; nothing is written until Save. Back discards. Read-only in Strict Mode.
 *
 * AppBlock-style layout: a SUMMARY page with tap-in rows that drill into APPS / WEBSITES
 * pickers, plus a full-screen MODE chooser. All pages share one hoisted staged state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockEditorScreen(
    strictActive: Boolean,
    onBack: () -> Unit,
    /** Ask AppRoot for [AccessibilityDisclosureScreen] — see [ProtectionBanner]. */
    onRequestDisclosure: (() -> Unit) -> Unit = {},
    appsVm: AppListViewModel = viewModel(),
    webVm: WebFilterViewModel = viewModel(),
) {
    val context = LocalContext.current
    val apps by appsVm.apps.collectAsState()
    val loading by appsVm.loading.collectAsState()
    val savedKeywords by webVm.keywords.collectAsState()
    // Website words live because their app is blocked — what makes a word removable mid-Strict.
    val siteWords by webVm.liveSiteWords.collectAsState()

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
    // The selection the pickers are currently editing.
    val selected = if (allowlist) selectedAllow else selectedBlock
    val keywords = remember { mutableStateListOf<String>() }
    var editedKw by remember { mutableStateOf(false) }
    LaunchedEffect(savedKeywords) {
        if (!editedKw) { keywords.clear(); keywords.addAll(savedKeywords) }
    }
    var newWord by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    // Which app categories are expanded (collapsed by default, AppBlock-style).
    var expandedCats by rememberSaveable { mutableStateOf(listOf<String>()) }
    var preBlockOpen by rememberSaveable { mutableStateOf(false) }
    var adult by remember { mutableStateOf(SettingsStore.blockAdult(context)) }
    var addNew by remember { mutableStateOf(SettingsStore.addNewApps(context)) }
    var purchases by remember { mutableStateOf(SettingsStore.blockPurchases(context)) }
    var unsupported by remember { mutableStateOf(SettingsStore.blockUnsupportedBrowsers(context)) }
    var ytShorts by remember { mutableStateOf(SettingsStore.blockYoutubeShorts(context)) }

    val ed = !strictActive // false locks "weakening" edits during Strict Mode
    val preBlockApps = remember(apps, query) {
        val shown = if (query.isBlank()) apps
            else apps.filter { it.label.contains(query.trim(), ignoreCase = true) }
        shown.filter { !it.installed }
    }

    // Internal navigation: SUMMARY <-> a picker/chooser page. The local BackHandler is enabled
    // only off-summary, so it takes precedence over AppRoot's (which closes the editor).
    var page by remember { mutableStateOf(EditorPage.SUMMARY) }
    BackHandler(enabled = page != EditorPage.SUMMARY) { page = EditorPage.SUMMARY }

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

    // Shared app-picker toggle handlers (used by the APPS page for both the categorized list and
    // the hypothetical-apps list). During Strict you can only *tighten*.
    fun toggleApp(pkg: String, on: Boolean) {
        if (strictActive && (if (allowlist) on else !on)) return
        editedApps = true
        if (on) selected.add(pkg) else selected.remove(pkg)
    }

    AnimatedContent(targetState = page, label = "editorPage") { p ->
        when (p) {
            EditorPage.SUMMARY -> Scaffold(
                containerColor = Color.Transparent,
                topBar = { EditorTopBar(stringResource(R.string.quick_block), onBack) },
                bottomBar = {
                    // Save is allowed during Strict — you can tighten, just not loosen.
                    GradientButton(text = stringResource(R.string.common_save), onClick = ::save,
                        modifier = Modifier.navigationBarsPadding().padding(16.dp))
                },
            ) { padding ->
                LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
                    item {
                        Spacer(Modifier.padding(top = 4.dp))
                        // "Blocking" title + a tappable Blocklist/Allowlist chip (AppBlock-style).
                        BlockingModeHeader(
                            allowlist = allowlist,
                            enabled = !strictActive, // can't switch modes to escape Strict
                            onClick = { if (!strictActive) page = EditorPage.MODE },
                        )
                        Spacer(Modifier.padding(top = 4.dp))
                        Text(
                            stringResource(
                                if (allowlist) R.string.editor_allowlist_intro
                                else R.string.editor_blocklist_intro,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.padding(top = 12.dp))
                        if (!AccessibilityUtil.isEnabled(context)) {
                            ProtectionBanner(context, onRequestDisclosure)
                            Spacer(Modifier.padding(top = 12.dp))
                        }
                        if (strictActive) {
                            Text(
                                if (allowlist) "🔒 Strict Mode — you can remove allowed apps, but not add them."
                                else "🔒 Strict Mode — you can add blocks, but not remove them.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.padding(top = 12.dp))
                        }
                        // The tap-in summary card.
                        val installedSelected = apps.count { it.installed && selected.contains(it.packageName) }
                        val stripIcons = apps.asSequence()
                            .filter { it.installed && selected.contains(it.packageName) && it.icon != null }
                            .mapNotNull { it.icon }.take(4).toList()
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surface),
                        ) {
                            SummaryRow(
                                Icons.Filled.Apps, stringResource(R.string.editor_apps),
                                installedSelected, stripIcons,
                            ) {
                                page = EditorPage.APPS
                            }
                            if (!allowlist) {
                                RowDivider()
                                SummaryRow(
                                    Icons.Filled.Web,
                                    stringResource(R.string.editor_websites_words), keywords.size,
                                ) {
                                    page = EditorPage.WEBSITES
                                }
                            }
                        }
                    }

                    item {
                        Spacer(Modifier.padding(top = 20.dp))
                        SectionHeader(Icons.Filled.Block, stringResource(R.string.editor_extra_options), null)
                        Spacer(Modifier.padding(top = 4.dp))
                        // `ed || !value` — switching one ON is always allowed, including mid-Strict;
                        // switching it OFF is what a session refuses. These four were the only
                        // strengthening controls in the app still frozen in both directions during
                        // Strict, which is the same mistake as v1.127's guard ejecting people for
                        // turning protection on. The pattern is already used by the Shorts row and
                        // the app checkboxes below, and by both toggles in KeywordsScreen.
                        // Once switched on, `!value` is false and the row locks itself for the rest
                        // of the session — which is the point.
                        ToggleRow(
                            Icons.Filled.NoAdultContent,
                            stringResource(R.string.editor_porn_title),
                            stringResource(R.string.editor_porn_desc),
                            adult, ed || !adult) { adult = it }
                        // "Add newly installed apps" is a Blocklist concept — in Allowlist mode a new
                        // app is already blocked (it isn't allowed), so the toggle is hidden.
                        if (!allowlist) {
                            ToggleRow(
                                Icons.Filled.GetApp,
                                stringResource(R.string.editor_newapps_title),
                                stringResource(R.string.editor_newapps_desc),
                                addNew, ed || !addNew) { addNew = it }
                        }
                        ToggleRow(
                            Icons.Filled.ShoppingBasket,
                            stringResource(R.string.editor_purchases_title),
                            stringResource(R.string.editor_purchases_desc),
                            purchases, ed || !purchases) { purchases = it }
                        ToggleRow(
                            Icons.Filled.Web,
                            stringResource(R.string.editor_browsers_title),
                            stringResource(R.string.editor_browsers_desc),
                            unsupported, ed || !unsupported) { unsupported = it }
                        Spacer(Modifier.padding(top = 16.dp))
                    }
                }
            }

            EditorPage.APPS -> Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    EditorTopBar(
                        stringResource(R.string.editor_apps),
                        onBack = { page = EditorPage.SUMMARY },
                    )
                },
            ) { padding ->
                LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
                    item {
                        Spacer(Modifier.padding(top = 4.dp))
                        Text(
                            stringResource(
                                if (allowlist) R.string.editor_pick_allowed
                                else R.string.editor_pick_blocked,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = query, onValueChange = { query = it },
                            placeholder = { Text(stringResource(R.string.editor_search_apps)) },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            singleLine = true, enabled = ed,
                            shape = RoundedCornerShape(28.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        )
                    }
                    if (loading) {
                        item {
                            Text(
                                stringResource(R.string.editor_loading),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp)) }
                    } else {
                        categorizedAppItems(
                            apps = apps.filter { it.installed },
                            selected = selected,
                            expandedCats = expandedCats.toSet(),
                            query = query,
                            rowEnabled = { checked -> if (allowlist) ed || checked else ed || !checked },
                            onToggleExpand = { cat ->
                                expandedCats = if (cat.name in expandedCats) expandedCats - cat.name
                                else expandedCats + cat.name
                            },
                            onToggle = { app, on -> toggleApp(app.packageName, on) },
                            onSelectAll = { catApps ->
                                // Adding loosens in Allowlist mode → locked during Strict.
                                if (strictActive && allowlist) return@categorizedAppItems
                                editedApps = true
                                catApps.forEach { if (!selected.contains(it.packageName)) selected.add(it.packageName) }
                            },
                            onClearAll = { catApps ->
                                // Clearing loosens in Blocklist mode → locked during Strict.
                                if (strictActive && !allowlist) return@categorizedAppItems
                                editedApps = true
                                catApps.forEach { selected.remove(it.packageName) }
                            },
                            extraUnder = { app ->
                                // Nested "Shorts" sub-row under YouTube (blocks only the Shorts feed).
                                // Blocklist-only — in Allowlist an allowed app is fully allowed.
                                if (!allowlist && app.packageName == "com.google.android.youtube") {
                                    {
                                        ShortsSubRow(icon = app.icon, checked = ytShorts, enabled = ed || !ytShorts) { on ->
                                            if (strictActive && !on) return@ShortsSubRow
                                            ytShorts = on
                                        }
                                        // Only once Shorts blocking is actually on: before that
                                        // there is nothing to explain, and this is the screen the
                                        // "less talk" pass is trying to keep quiet.
                                        if (ytShorts) ShortsPipNote()
                                    }
                                } else null
                            },
                        )
                    }

                    // Hypothetical (not-yet-installed) apps are a Blocklist concept.
                    if (!allowlist) {
                        item {
                            Spacer(Modifier.padding(top = 16.dp))
                            val preBlockCount = apps.count { !it.installed && selected.contains(it.packageName) }
                            CollapsibleHeader(
                                Icons.Filled.GetApp,
                                stringResource(R.string.editor_hypothetical),
                                preBlockCount, preBlockOpen,
                            ) {
                                preBlockOpen = !preBlockOpen
                            }
                            if (preBlockOpen) {
                                Text(stringResource(R.string.editor_hypothetical_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp))
                            }
                        }
                        if (preBlockOpen) {
                            items(preBlockApps, key = { it.packageName }) { app ->
                                val isChecked = selected.contains(app.packageName)
                                AppCheckRow(app, checked = isChecked, enabled = ed || !isChecked,
                                    subtitle = stringResource(R.string.editor_not_installed)) { on -> toggleApp(app.packageName, on) }
                            }
                        }
                        item { Spacer(Modifier.padding(top = 16.dp)) }
                    }
                }
            }

            EditorPage.WEBSITES -> Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    EditorTopBar(
                        stringResource(R.string.editor_websites_words),
                        onBack = { page = EditorPage.SUMMARY },
                    )
                },
            ) { padding ->
                LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
                    item {
                        Spacer(Modifier.padding(top = 4.dp))
                        Text(stringResource(R.string.editor_words_intro),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = newWord, onValueChange = { newWord = it },
                                placeholder = { Text(stringResource(R.string.editor_add_word_site)) },
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
                        BlockedWordRow(
                            word = word,
                            covering = remember(word, siteWords) {
                                StrictEdits.coveringSiteWord(word, siteWords)
                            },
                            strictActive = strictActive,
                            onRemove = { editedKw = true; keywords.remove(word) },
                        )
                    }
                }
            }

            EditorPage.MODE -> BlockingModePage(
                allowlist = allowlist,
                onClose = { page = EditorPage.SUMMARY },
                onContinue = { picked -> allowlist = picked; page = EditorPage.SUMMARY },
            )
        }
    }
}

/** The "Blocking" section title with a tappable mode chip on the right (Blocklist / Allowlist). */
@Composable
private fun BlockingModeHeader(allowlist: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(stringResource(R.string.editor_blocking), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Row(
            Modifier.clip(RoundedCornerShape(20.dp)).clickable(enabled = enabled) { onClick() }
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (allowlist) Icons.Filled.Star else Icons.Filled.Block, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(if (allowlist) R.string.editor_allowlist else R.string.editor_blocklist),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            if (enabled) {
                Icon(Icons.Filled.ExpandMore, contentDescription = "Change blocking mode",
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** A settings-style tap-in row: leading icon, title, optional strip of app icons, count, chevron. */
@Composable
private fun SummaryRow(
    icon: ImageVector,
    title: String,
    count: Int,
    icons: List<Bitmap> = emptyList(),
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        if (icons.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                icons.forEach { bmp ->
                    Image(bmp.asImageBitmap(), null, Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)))
                }
            }
            Spacer(Modifier.width(10.dp))
        }
        Text("$count", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Hairline divider between summary rows. */
@Composable
private fun RowDivider() {
    Box(
        Modifier.fillMaxWidth().padding(start = 50.dp).height(1.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
    )
}

/** Full-screen "Blocking mode" chooser with a Continue button — mirrors AppBlock's screen.
 *  A normal overlay page (NOT a Dialog), so ordinary safe-area padding works on HyperOS. */
@Composable
private fun BlockingModePage(
    allowlist: Boolean,
    onClose: () -> Unit,
    onContinue: (Boolean) -> Unit,
) {
    var pending by remember { mutableStateOf(allowlist) }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding().padding(horizontal = 20.dp),
    ) {
        IconButton(onClick = onClose, modifier = Modifier.padding(top = 4.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Close")
        }
        BlockingModePreview(allowlist = pending)
        Spacer(Modifier.padding(top = 16.dp))
        Text(stringResource(R.string.editor_mode_title), style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(Modifier.padding(top = 2.dp))
        Text(stringResource(R.string.editor_mode_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(Modifier.padding(top = 20.dp))
        ModeOption(Icons.Filled.Block, "Blocklist",
            "Select apps, sites or words you want to block.",
            selected = !pending) { pending = false }
        Spacer(Modifier.padding(top = 12.dp))
        ModeOption(Icons.Filled.Star, "Allowlist",
            "Select apps you want to allow. All others will be blocked.",
            selected = pending) { pending = true }
        Spacer(Modifier.weight(1f))
        // The Column already applies safeDrawingPadding, so no extra nav-bar inset here.
        GradientButton(
            text = stringResource(R.string.onboarding_continue),
            onClick = { onContinue(pending) },
            modifier = Modifier.padding(vertical = 16.dp))
    }
}

/** A decorative mock of a list (check glyphs for Allowlist, block glyphs for Blocklist) shown
 *  above the mode options, echoing AppBlock's illustration. */
@Composable
private fun BlockingModePreview(allowlist: Boolean) {
    val widths = listOf(0.5f, 0.72f, 0.4f, 0.6f, 0.8f)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        widths.forEachIndexed { i, w ->
            // The first few rows are "selected" (icon shown), the rest are plain.
            val chosen = i < 3
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(22.dp).clip(CircleShape)
                        .background(
                            if (chosen) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (chosen) {
                        Icon(if (allowlist) Icons.Filled.Check else Icons.Filled.Block, null,
                            tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier.fillMaxWidth(w).height(9.dp).clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
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
        Text(stringResource(R.string.editor_shorts), style = MaterialTheme.typography.bodyLarge,
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

/**
 * The one part of the floating-Short problem that is a setting rather than code.
 *
 * The app now closes the reel before it sends him out, so *its* blocks no longer leave a Short
 * playing over the launcher. But nothing in an accessibility service can stop YouTube opening a
 * floating window when **he** swipes home himself mid-Short, and nothing can close one that is
 * already open — see [openPictureInPictureSettings]. Turning YouTube's permission off closes both
 * routes permanently, so it is worth the one tap.
 */
@Composable
private fun ShortsPipNote() {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().padding(start = 68.dp, end = 8.dp).padding(bottom = 8.dp),
    ) {
        Text(
            stringResource(R.string.shorts_pip_title),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            stringResource(R.string.shorts_pip_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = { openPictureInPictureSettings(context) },
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
        ) {
            Text(
                stringResource(R.string.shorts_pip_button),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ProtectionBanner(context: Context, onRequestDisclosure: (() -> Unit) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFFFB020),
                modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.editor_protection_off), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.padding(top = 6.dp))
        Text(stringResource(R.string.editor_protection_off_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.padding(top = 12.dp))
        // **Through the disclosure, like every other door to that settings page.**
        // This button used to call startActivity directly. It never went near the consent gate,
        // so it sent people to Android's Accessibility page with no disclosure at all — and it
        // appears exactly when protection is off, which is the moment somebody is most likely to
        // press it. That is the flow Google's AccessibilityService policy is written about, and
        // it is the answer to "why does a self-control app get to read my screen?" going unasked.
        GradientButton(text = stringResource(R.string.editor_turn_on), onClick = {
            onRequestDisclosure {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        })
    }
}
