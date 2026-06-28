package com.appblocker.ui

import android.annotation.SuppressLint
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
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

    val context = LocalContext.current
    var name by remember { mutableStateOf(existing?.name ?: defaultName(type)) }
    var start by remember { mutableIntStateOf(existing?.startMinutes ?: 9 * 60) }
    var end by remember { mutableIntStateOf(existing?.endMinutes ?: 17 * 60) }
    var daysMask by remember { mutableIntStateOf(existing?.daysMask ?: 0b1111111) }
    var limit by remember { mutableIntStateOf(existing?.limitMinutes ?: 30) }
    var limitCount by remember { mutableIntStateOf(existing?.limitCount ?: 5) }
    var anyWifi by remember { mutableStateOf(existing?.wifiSsid.isNullOrBlank()) }
    var wifiSsid by remember { mutableStateOf(existing?.wifiSsid ?: "") }
    var lat by remember { mutableStateOf(existing?.latitude ?: 0.0) }
    var lng by remember { mutableStateOf(existing?.longitude ?: 0.0) }
    var radius by remember { mutableIntStateOf(existing?.radiusMeters ?: 150) }
    var locCaptured by remember { mutableStateOf((existing?.latitude ?: 0.0) != 0.0) }
    val selected = remember { (existing?.packages ?: emptyList()).toMutableStateList() }
    var appsOpen by rememberSaveable { mutableStateOf(true) } // collapsible Apps list

    // A NEW schedule can always be created (adding protection is allowed during Strict Mode);
    // an EXISTING one stays locked while Strict is active so it can't be weakened.
    val editable = existing == null || !strictActive

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            EditorTopBar(typeTitle(type), onBack) {
                if (existing != null && editable) {
                    IconButton(onClick = { vm.delete(existing); onBack() }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
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
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.padding(top = 8.dp))
            }

            when (type) {
                ScheduleType.TIME -> item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TimeField("Start", start, enabled = editable, modifier = Modifier.weight(1f)) { start = it }
                        TimeField("End", end, enabled = editable, modifier = Modifier.weight(1f)) { end = it }
                    }
                    Spacer(Modifier.padding(top = 12.dp))
                    SectionLabel("Days")
                    Spacer(Modifier.padding(top = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DAY_LABELS.forEachIndexed { i, label ->
                            val on = (daysMask shr i) and 1 == 1
                            ChipBtn(label, on, editable) { daysMask = daysMask xor (1 shl i) }
                        }
                    }
                    Spacer(Modifier.padding(top = 12.dp))
                }
                ScheduleType.USAGE_LIMIT -> item {
                    SectionLabel("Daily limit")
                    Spacer(Modifier.padding(top = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 30, 60, 120).forEach { p ->
                            ChipBtn("$p m", limit == p, editable) { limit = p }
                        }
                    }
                    Spacer(Modifier.padding(top = 12.dp))
                }
                ScheduleType.LAUNCH_COUNT -> item {
                    SectionLabel("Block after how many opens / day")
                    Spacer(Modifier.padding(top = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(3, 5, 10, 20).forEach { c ->
                            ChipBtn("$c", limitCount == c, editable) { limitCount = c }
                        }
                    }
                    Spacer(Modifier.padding(top = 12.dp))
                }
                ScheduleType.WIFI -> item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Any Wi-Fi network", Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                        Switch(checked = anyWifi, enabled = editable, onCheckedChange = { anyWifi = it })
                    }
                    if (!anyWifi) {
                        Spacer(Modifier.padding(top = 6.dp))
                        OutlinedTextField(
                            value = wifiSsid, onValueChange = { wifiSsid = it },
                            label = { Text("Wi-Fi name (SSID)") }, singleLine = true, enabled = editable,
                            shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth(),
                        )
                        Text("Reading a specific network's name needs the Location permission.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                    Spacer(Modifier.padding(top = 12.dp))
                }
                ScheduleType.LOCATION -> item {
                    SectionLabel("Location")
                    Spacer(Modifier.padding(top = 6.dp))
                    Text(
                        if (locCaptured) "Captured: %.4f, %.4f".format(lat, lng) else "No location captured yet.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.padding(top = 8.dp))
                    GradientButton(text = "Use my current location", enabled = editable, onClick = {
                        captureLocation(context)?.let { lat = it.first; lng = it.second; locCaptured = true }
                    })
                    Spacer(Modifier.padding(top = 12.dp))
                    SectionLabel("Radius")
                    Spacer(Modifier.padding(top = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(100, 250, 500).forEach { r ->
                            ChipBtn("$r m", radius == r, editable) { radius = r }
                        }
                    }
                    Spacer(Modifier.padding(top = 12.dp))
                }
            }

            item {
                CollapsibleHeader(Icons.Filled.Apps, "Apps", selected.size, appsOpen) { appsOpen = !appsOpen }
                Spacer(Modifier.padding(top = 4.dp))
            }
            if (appsOpen) {
                items(apps, key = { it.packageName }) { app ->
                    AppCheckRow(app, checked = selected.contains(app.packageName), enabled = editable) { on ->
                        if (on) selected.add(app.packageName) else selected.remove(app.packageName)
                    }
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
                                limitCount = limitCount,
                                wifiSsid = if (anyWifi) "" else wifiSsid.trim(),
                                latitude = lat,
                                longitude = lng,
                                radiusMeters = radius,
                                packages = selected.toList(),
                            )
                        )
                        onBack()
                    },
                    enabled = editable && selected.isNotEmpty() &&
                        (type != ScheduleType.LOCATION || locCaptured),
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

private fun defaultName(type: ScheduleType): String = when (type) {
    ScheduleType.TIME -> "Time block"
    ScheduleType.USAGE_LIMIT -> "Usage limit"
    ScheduleType.LAUNCH_COUNT -> "Launch limit"
    ScheduleType.WIFI -> "Wi-Fi block"
    ScheduleType.LOCATION -> "Location block"
}

private fun typeTitle(type: ScheduleType): String = when (type) {
    ScheduleType.TIME -> "Time schedule"
    ScheduleType.USAGE_LIMIT -> "Usage limit"
    ScheduleType.LAUNCH_COUNT -> "Launch count"
    ScheduleType.WIFI -> "Wi-Fi schedule"
    ScheduleType.LOCATION -> "Location schedule"
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChipBtn(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

/** One-shot best-effort current location via last-known fix (needs Location permission). */
@SuppressLint("MissingPermission")
private fun captureLocation(context: android.content.Context): Pair<Double, Double>? {
    if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:${context.packageName}"))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return null
    }
    val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as? android.location.LocationManager
        ?: return null
    val loc = runCatching {
        lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
    }.getOrNull() ?: return null
    return loc.latitude to loc.longitude
}
