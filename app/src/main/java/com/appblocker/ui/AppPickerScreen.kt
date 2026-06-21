package com.appblocker.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblocker.data.BlockMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerScreen(
    vm: AppListViewModel = viewModel(),
    blockingLocked: Boolean = false,
) {
    val apps by vm.apps.collectAsState()
    val loading by vm.loading.collectAsState()
    val context = LocalContext.current
    val blockedCount = apps.count { it.isBlocked }
    var dialogApp by remember { mutableStateOf<AppItem?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("AppBlocker", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                actions = {
                    IconButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }) {
                        Icon(Icons.Filled.Shield, contentDescription = "Turn on blocking",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SummaryCard(blockedCount)
            if (blockingLocked) {
                Text(
                    "🔒 Focus mode is on — your blocks are locked until the timer ends.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(apps, key = { it.packageName }) { app ->
                        AppRow(
                            app = app,
                            enabled = !blockingLocked,
                            onToggle = { vm.toggle(app) },
                            onClick = { if (app.isBlocked && !blockingLocked) dialogApp = app },
                        )
                    }
                }
            }
        }
    }

    dialogApp?.let { app ->
        ModeDialog(
            app = app,
            onApply = { mode, limit -> vm.setMode(app, mode, limit); dialogApp = null },
            onDismiss = { dialogApp = null },
            onGrantUsage = {
                context.startActivity(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
        )
    }
}

@Composable
private fun SummaryCard(blockedCount: Int) {
    Card(
        Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Shield, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    if (blockedCount == 0) "No apps blocked yet"
                    else "$blockedCount app${if (blockedCount == 1) "" else "s"} blocked",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Flip a switch to block an app, then tap it to set a daily limit. Tap the shield ↑ to turn the blocker on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun AppRow(
    app: AppItem,
    enabled: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = app.isBlocked && enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (app.icon != null) {
            Image(
                bitmap = app.icon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
            )
        } else {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Apps, contentDescription = null)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (app.isBlocked) {
                Text(
                    blockSubtitle(app),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Switch(checked = app.isBlocked, enabled = enabled, onCheckedChange = { onToggle() })
    }
}

private fun blockSubtitle(app: AppItem): String = when (app.mode) {
    BlockMode.LIMIT ->
        if (app.dailyLimitMinutes >= 0) "${app.dailyLimitMinutes} min/day" else "Daily limit"
    else -> "Always blocked"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeDialog(
    app: AppItem,
    onApply: (BlockMode, Int) -> Unit,
    onDismiss: () -> Unit,
    onGrantUsage: () -> Unit,
) {
    var mode by remember { mutableStateOf(app.mode) }
    var limit by remember { mutableIntStateOf(if (app.dailyLimitMinutes >= 0) app.dailyLimitMinutes else 30) }
    val presets = listOf(15, 30, 60, 120)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onApply(mode, if (mode == BlockMode.LIMIT) limit else -1) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(app.label) },
        text = {
            Column {
                OptionRow("Always block", mode == BlockMode.HARD) { mode = BlockMode.HARD }
                OptionRow("Daily time limit", mode == BlockMode.LIMIT) { mode = BlockMode.LIMIT }
                if (mode == BlockMode.LIMIT) {
                    Spacer(Modifier.width(8.dp))
                    Row(Modifier.padding(top = 8.dp)) {
                        presets.forEach { p ->
                            FilterChip(
                                selected = limit == p,
                                onClick = { limit = p },
                                label = { Text("$p m") },
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                    }
                    Text(
                        "Needs Usage Access permission.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    TextButton(onClick = onGrantUsage) { Text("Grant usage access") }
                }
            }
        }
    )
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(4.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurface)
    }
}
