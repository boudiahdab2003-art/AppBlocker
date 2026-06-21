package com.appblocker.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblocker.data.Schedule
import com.appblocker.data.ScheduleType

private val DAY_LABELS = listOf("S", "M", "T", "W", "T", "F", "S") // bit0 = Sunday

private fun fmtTime(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditorScreen(
    type: ScheduleType,
    existing: Schedule?,
    strictActive: Boolean,
    onBack: () -> Unit,
    vm: ScheduleViewModel = viewModel(),
    appsVm: AppListViewModel = viewModel(),
) {
    val apps by appsVm.apps.collectAsState()

    var name by remember { mutableStateOf(existing?.name ?: defaultName(type)) }
    var start by remember { mutableIntStateOf(existing?.startMinutes ?: 9 * 60) }
    var end by remember { mutableIntStateOf(existing?.endMinutes ?: 17 * 60) }
    var daysMask by remember { mutableIntStateOf(existing?.daysMask ?: 0b1111111) }
    var limit by remember { mutableIntStateOf(existing?.limitMinutes ?: 30) }
    val selected = remember { (existing?.packages ?: emptyList()).toMutableStateList() }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (type == ScheduleType.TIME) "Time schedule" else "Usage limit",
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (existing != null && !strictActive) {
                        IconButton(onClick = { vm.delete(existing); onBack() }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Spacer(Modifier.padding(top = 8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    enabled = !strictActive,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.padding(top = 8.dp))
            }

            if (type == ScheduleType.TIME) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TimeField("Start", start, enabled = !strictActive, modifier = Modifier.weight(1f)) { start = it }
                        TimeField("End", end, enabled = !strictActive, modifier = Modifier.weight(1f)) { end = it }
                    }
                    Spacer(Modifier.padding(top = 12.dp))
                    Text("Days", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.padding(top = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DAY_LABELS.forEachIndexed { i, label ->
                            val on = (daysMask shr i) and 1 == 1
                            FilterChip(
                                selected = on,
                                enabled = !strictActive,
                                onClick = { daysMask = daysMask xor (1 shl i) },
                                label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            )
                        }
                    }
                    Spacer(Modifier.padding(top = 12.dp))
                }
            } else {
                item {
                    Text("Daily limit", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.padding(top = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 30, 60, 120).forEach { p ->
                            FilterChip(
                                selected = limit == p,
                                enabled = !strictActive,
                                onClick = { limit = p },
                                label = { Text("$p m") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            )
                        }
                    }
                    Spacer(Modifier.padding(top = 12.dp))
                }
            }

            item {
                Text("Apps", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.padding(top = 4.dp))
            }
            items(apps, key = { it.packageName }) { app ->
                val isSel = selected.contains(app.packageName)
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = !strictActive) {
                            if (isSel) selected.remove(app.packageName) else selected.add(app.packageName)
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (app.icon != null) {
                        Image(app.icon.asImageBitmap(), null,
                            Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)))
                    } else {
                        Box(Modifier.size(36.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Apps, null, Modifier.size(20.dp))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(app.label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground)
                    Checkbox(checked = isSel, enabled = !strictActive, onCheckedChange = {
                        if (it) selected.add(app.packageName) else selected.remove(app.packageName)
                    })
                }
            }

            item {
                Spacer(Modifier.padding(top = 12.dp))
                GradientButton(
                    text = if (existing == null) "Create schedule" else "Save",
                    onClick = {
                        vm.save(
                            (existing ?: Schedule(name = name, type = type)).copy(
                                name = name.ifBlank { defaultName(type) },
                                type = type,
                                startMinutes = start,
                                endMinutes = end,
                                daysMask = daysMask,
                                limitMinutes = limit,
                                packages = selected.toList(),
                            )
                        )
                        onBack()
                    },
                    enabled = !strictActive && selected.isNotEmpty(),
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(
    label: String,
    minutes: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onPick: (Int) -> Unit,
) {
    var show by remember { mutableStateOf(false) }
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(enabled = enabled) { show = true }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(fmtTime(minutes), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
    }
    if (show) {
        val state = rememberTimePickerState(minutes / 60, minutes % 60, true)
        AlertDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = { onPick(state.hour * 60 + state.minute); show = false }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } },
            text = { TimePicker(state = state) },
        )
    }
}

private fun defaultName(type: ScheduleType): String =
    if (type == ScheduleType.TIME) "Time block" else "Usage limit"
