package com.appblocker.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appblocker.R
import com.appblocker.data.InstalledAppsRepository
import com.appblocker.service.findBrowserPackages

/**
 * The row under "Block these words in every app" that opens [WordSkipPicker] (invariant 87).
 * [names] are the skipped apps' labels, in the order to show them.
 */
@Composable
internal fun WordSkipRow(names: List<String>, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(14.dp).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.DoNotDisturbOn, contentDescription = null, tint = Color.White,
                modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.words_skip_title), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                if (names.isEmpty()) stringResource(R.string.words_skip_none) else names.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Picks the apps "check every app" leaves out (invariant 87). Drawn over the whole screen rather
 * than in a `Dialog`, like the friction gate, because dialog windows report zero insets on his phone.
 *
 * Each tick is stored at once, like the words themselves (no Save). Browsers are not offered: their
 * scan carries the site layer, which no word list should be able to switch off. During a Strict
 * session a ticked app can be unticked and nothing can be ticked — `enabled = ed || checked`, the
 * stronger direction always open (invariant 16) — and [onChange]'s write re-checks it regardless.
 */
@Composable
internal fun WordSkipPicker(
    skipped: Set<String>,
    strictActive: Boolean,
    onChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    BackHandler { onDismiss() }
    LaunchedEffect(Unit) { InstalledAppsRepository.ensureLoaded(context) }
    val installed by InstalledAppsRepository.apps.collectAsState()
    val browsers = remember { runCatching { findBrowserPackages(context) }.getOrDefault(emptySet()) }
    // Skipped apps first, so what he chose is what he sees; then the rest by name. Sorted once per
    // list rather than per tick, so a row does not jump away from under his finger.
    val apps = remember(installed, browsers) {
        installed
            .filter { it.packageName != context.packageName && it.packageName !in browsers }
            .sortedWith(compareBy({ it.packageName !in skipped }, { it.label.lowercase() }))
    }
    val ed = !strictActive

    Box(Modifier.fillMaxSize().background(com.appblocker.ui.theme.appBackground())) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = { EditorTopBar(stringResource(R.string.words_skip_picker_title), onDismiss) },
        ) { padding ->
            LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
                item {
                    Text(
                        stringResource(
                            if (strictActive) R.string.words_skip_strict else R.string.words_skip_desc,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                    )
                }
                items(apps, key = { it.packageName }) { app ->
                    val checked = app.packageName in skipped
                    AppCheckRow(
                        item = AppItem(app.packageName, app.label, app.icon, isBlocked = false,
                            category = app.category),
                        checked = checked,
                        enabled = ed || checked,
                        onToggle = { on ->
                            onChange(if (on) skipped + app.packageName else skipped - app.packageName)
                        },
                    )
                }
            }
        }
    }
}
