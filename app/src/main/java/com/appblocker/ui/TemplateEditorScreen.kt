package com.appblocker.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.GetApp
import androidx.compose.material.icons.filled.NoAdultContent
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblocker.data.TemplateOptionsStore
import com.appblocker.data.TemplateStore
import com.appblocker.ui.theme.AppGradients
import com.appblocker.ui.theme.appBackground

/**
 * Full-screen editor for a template — same look and feel as the Quick Block editor: a
 * collapsible app list (with search) you can hide, plus the extra-option switches. Nothing is
 * applied until Save; applying the template is a separate step from the Templates list.
 */
@Composable
fun TemplateEditorScreen(
    template: Template,
    strictActive: Boolean,
    onBack: () -> Unit,
    appsVm: AppListViewModel = viewModel(),
) {
    val context = LocalContext.current
    val apps by appsVm.apps.collectAsState()
    val hasApps = template.packages.isNotEmpty()
    val selected = remember(template.id) {
        (TemplateStore.packagesFor(context, template.id) ?: template.packages.map { it.first })
            .toMutableStateList()
    }
    val selectedOptions = remember(template.id) {
        template.effectiveOptions(context).map { it.key }.toMutableStateList()
    }
    var query by remember { mutableStateOf("") }
    // Hidden by default — the app list is a preset you rarely touch, so keep it tidy.
    var appsOpen by rememberSaveable(template.id) { mutableStateOf(false) }
    var expandedCats by rememberSaveable(template.id) { mutableStateOf(listOf<String>()) }
    val ed = !strictActive

    fun save() {
        if (hasApps) TemplateStore.setPackages(context, template.id, selected.toList())
        TemplateOptionsStore.setOptions(context, template.id, selectedOptions.toSet())
        Toast.makeText(context, "Saved “${template.title}”", Toast.LENGTH_SHORT).show()
        onBack()
    }

    Box(Modifier.fillMaxSize().background(appBackground())) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = { EditorTopBar("Customise “${template.title}”", onBack) },
            bottomBar = {
                GradientButton(text = "Save", onClick = ::save,
                    modifier = Modifier.navigationBarsPadding().padding(16.dp))
            },
        ) { padding ->
            LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
                item {
                    Spacer4()
                    Text("Choose what this template blocks. Nothing changes until you tap Save.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (strictActive) {
                        Spacer12()
                        Text("🔒 Strict Mode — you can add, but not remove.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }

                if (hasApps) {
                    item {
                        Spacer12()
                        val selCount = apps.count { it.installed && selected.contains(it.packageName) }
                        CollapsibleHeader(Icons.Filled.Apps, "Apps", selCount, appsOpen) {
                            appsOpen = !appsOpen
                        }
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
                        categorizedAppItems(
                            apps = apps.filter { it.installed },
                            selected = selected,
                            expandedCats = expandedCats.toSet(),
                            query = query,
                            rowEnabled = { checked -> ed || !checked },
                            onToggleExpand = { cat ->
                                expandedCats = if (cat.name in expandedCats) expandedCats - cat.name
                                else expandedCats + cat.name
                            },
                            onToggle = { app, on ->
                                if (strictActive && !on) return@categorizedAppItems
                                if (on) selected.add(app.packageName) else selected.remove(app.packageName)
                            },
                            onSelectAll = { catApps ->
                                catApps.forEach { if (!selected.contains(it.packageName)) selected.add(it.packageName) }
                            },
                            onClearAll = { catApps ->
                                if (strictActive) return@categorizedAppItems
                                catApps.forEach { selected.remove(it.packageName) }
                            },
                        )
                    }
                }

                item {
                    Spacer(Modifier.padding(top = 20.dp))
                    Text("EXTRA OPTIONS", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 6.dp))
                }
                items(QuickOption.entries, key = { it.key }) { opt ->
                    ToggleRow(
                        icon = opt.iconFor(), title = opt.label, desc = opt.descFor(),
                        checked = opt.key in selectedOptions, enabled = true,
                    ) { on ->
                        if (on) { if (opt.key !in selectedOptions) selectedOptions.add(opt.key) }
                        else selectedOptions.remove(opt.key)
                    }
                    Spacer(Modifier.padding(top = 8.dp))
                }
                item { Spacer(Modifier.padding(top = 16.dp)) }
            }
        }
    }
}

private fun QuickOption.iconFor(): ImageVector = when (this) {
    QuickOption.ADULT -> Icons.Filled.NoAdultContent
    QuickOption.ADD_NEW -> Icons.Filled.GetApp
    QuickOption.PURCHASES -> Icons.Filled.ShoppingBasket
    QuickOption.UNSUPPORTED -> Icons.Filled.Web
}

private fun QuickOption.descFor(): String = when (this) {
    QuickOption.ADULT -> "Detects and blocks adult sites in your browsers."
    QuickOption.ADD_NEW -> "Apps you install later are blocked automatically."
    QuickOption.PURCHASES -> "Blocks the Google Play purchase prompt in games and apps."
    QuickOption.UNSUPPORTED -> "Blocks browsers we can't filter (e.g. Brave)."
}

@Composable private fun Spacer4() = Spacer(Modifier.padding(top = 4.dp))
@Composable private fun Spacer12() = Spacer(Modifier.padding(top = 12.dp))
