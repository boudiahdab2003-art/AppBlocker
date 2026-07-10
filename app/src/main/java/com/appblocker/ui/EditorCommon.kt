package com.appblocker.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appblocker.data.AppCategory

/** A selectable app row (icon + label + checkbox) shared by the Quick Block & schedule editors.
 *  [subtitle], when set, shows a small line under the label (e.g. "Not installed yet"). */
@Composable
fun AppCheckRow(
    item: AppItem,
    checked: Boolean,
    enabled: Boolean,
    subtitle: String? = null,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled) { onToggle(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.icon != null) {
            Image(item.icon.asImageBitmap(), null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
        } else if (item.accentColor != null) {
            // No real icon yet (not installed) — show a brand-coloured initial badge.
            val brand = Color(item.accentColor)
            val onBrand = if (brand.luminance() > 0.5f) Color.Black else Color.White
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(brand),
                contentAlignment = Alignment.Center,
            ) {
                Text(item.label.take(1).uppercase(), color = onBrand,
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        } else {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Apps, null, Modifier.size(22.dp)) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.label, color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = onToggle)
    }
}

/**
 * A tappable section header that collapses/expands the list under it. Same look as a plain section
 * header (primary-tinted icon, bold title, optional count badge) plus a trailing chevron that flips
 * with [expanded]. Shared by the Quick Block & schedule editors.
 */
@Composable
fun CollapsibleHeader(
    icon: ImageVector,
    title: String,
    count: Int?,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onToggle() }.padding(vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        if (count != null && count > 0) {
            Text("$count", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** UI icon for each app category (kept out of the enum — data layer stays Compose-free). */
fun categoryIcon(category: AppCategory): ImageVector = when (category) {
    AppCategory.SOCIAL -> Icons.Filled.Forum
    AppCategory.ENTERTAINMENT -> Icons.Filled.Movie
    AppCategory.GAMES -> Icons.Filled.SportsEsports
    AppCategory.NEWS_BOOKS -> Icons.Filled.MenuBook
    AppCategory.SHOPPING_FOOD -> Icons.Filled.ShoppingCart
    AppCategory.CREATIVITY -> Icons.Filled.Palette
    AppCategory.TRAVEL -> Icons.Filled.Flight
    AppCategory.UTILITIES -> Icons.Filled.Widgets
    AppCategory.EDUCATION -> Icons.Filled.School
    AppCategory.HEALTH_FITNESS -> Icons.Filled.FitnessCenter
    AppCategory.PRODUCTIVITY -> Icons.Filled.Work
    AppCategory.OTHER -> Icons.Filled.MoreHoriz
}

/**
 * AppBlock-style category row: color-tinted icon tile, name, blue selected-count, tri-state
 * checkbox (whole-category select) and a chevron. Tapping the row expands/collapses.
 */
@Composable
fun CategoryHeaderRow(
    category: AppCategory,
    selectedCount: Int,
    expanded: Boolean,
    state: ToggleableState,
    enabled: Boolean,
    onToggleExpand: () -> Unit,
    onToggleAll: () -> Unit,
) {
    val tint = Color(category.color)
    Row(
        Modifier.fillMaxWidth().clickable { onToggleExpand() }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(categoryIcon(category), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            category.label, Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (selectedCount > 0) {
            Text("$selectedCount", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        TriStateCheckbox(state = state, onClick = onToggleAll, enabled = enabled)
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The shared categorized app list used by every picker (Quick Block / Schedule / Template).
 * No search query -> apps grouped under collapsible [CategoryHeaderRow]s (enum order, empty
 * categories hidden, collapsed by default). With a query -> a flat filtered list, no headers.
 * [extraUnder] lets a caller append a sub-row under a specific app (YouTube's Shorts row).
 */
fun LazyListScope.categorizedAppItems(
    apps: List<AppItem>,
    selected: List<String>,
    expandedCats: Set<String>,
    query: String,
    rowEnabled: (checked: Boolean) -> Boolean,
    onToggleExpand: (AppCategory) -> Unit,
    onToggle: (AppItem, Boolean) -> Unit,
    onSelectAll: (List<AppItem>) -> Unit,
    onClearAll: (List<AppItem>) -> Unit,
    extraUnder: (AppItem) -> (@Composable () -> Unit)? = { null },
) {
    val q = query.trim()
    if (q.isNotEmpty()) {
        val shown = apps.filter { it.label.contains(q, ignoreCase = true) }
        items(shown, key = { it.packageName }) { app ->
            val checked = selected.contains(app.packageName)
            Column {
                AppCheckRow(app, checked = checked, enabled = rowEnabled(checked)) { onToggle(app, it) }
                extraUnder(app)?.invoke()
            }
        }
        return
    }
    AppCategory.entries.forEach { cat ->
        val catApps = apps.filter { it.category == cat }
        if (catApps.isEmpty()) return@forEach
        val selCount = catApps.count { selected.contains(it.packageName) }
        val state = when (selCount) {
            0 -> ToggleableState.Off
            catApps.size -> ToggleableState.On
            else -> ToggleableState.Indeterminate
        }
        item(key = "cat-${cat.name}") {
            CategoryHeaderRow(
                category = cat,
                selectedCount = selCount,
                expanded = cat.name in expandedCats,
                state = state,
                // The tri-state can always ADD; whether it may clear is the caller's rule.
                enabled = rowEnabled(false) || state != ToggleableState.On,
                onToggleExpand = { onToggleExpand(cat) },
                onToggleAll = { if (state == ToggleableState.On) onClearAll(catApps) else onSelectAll(catApps) },
            )
        }
        if (cat.name in expandedCats) {
            items(catApps, key = { it.packageName }) { app ->
                val checked = selected.contains(app.packageName)
                Column {
                    AppCheckRow(app, checked = checked, enabled = rowEnabled(checked)) { onToggle(app, it) }
                    extraUnder(app)?.invoke()
                }
            }
        }
    }
}

/** The transparent editor top bar (title + back, optional trailing actions). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorTopBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}
