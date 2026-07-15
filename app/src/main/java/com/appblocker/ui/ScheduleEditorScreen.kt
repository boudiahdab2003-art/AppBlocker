package com.appblocker.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblocker.data.SavedPlace
import com.appblocker.data.Schedule
import com.appblocker.data.ScheduleType

private val DAY_LABELS = listOf("S", "M", "T", "W", "T", "F", "S") // bit0 = Sunday

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    var appQuery by remember { mutableStateOf("") }
    var expandedCats by rememberSaveable { mutableStateOf(listOf<String>()) } // open categories

    // A NEW schedule can always be created (adding protection is allowed during Strict Mode);
    // an EXISTING one stays locked while Strict is active so it can't be weakened.
    val editable = existing == null || !strictActive

    Scaffold(
        containerColor = Color.Transparent,
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
        bottomBar = {
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
                    (type != ScheduleType.LOCATION || locCaptured) &&
                    (type != ScheduleType.USAGE_LIMIT || limit >= 1),
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp, bottom = 24.dp),
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
                    val hours = limit / 60
                    val mins = limit % 60
                    SectionLabel("Daily limit")
                    Spacer(Modifier.padding(top = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StepperField("hours", hours, "h", min = 0, max = 23, step = 1,
                            enabled = editable, modifier = Modifier.weight(1f)) { limit = it * 60 + mins }
                        StepperField("minutes", mins, "m", min = 0, max = 59, step = 5,
                            enabled = editable, modifier = Modifier.weight(1f)) { limit = hours * 60 + it }
                    }
                    Spacer(Modifier.padding(top = 12.dp))
                }
                ScheduleType.LAUNCH_COUNT -> item {
                    SectionLabel("Block after how many opens / day")
                    Spacer(Modifier.padding(top = 6.dp))
                    StepperField("opens", limitCount, "opens", min = 1, max = 999, step = 1,
                        enabled = editable, modifier = Modifier.fillMaxWidth()) { limitCount = it }
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
                    // Re-read permission state whenever we return from Settings.
                    val tick = resumeTick()
                    val hasFine = remember(tick) { hasLocation(context) }
                    val hasBg = remember(tick) { hasBackgroundLocation(context) }
                    val fineLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { /* state re-reads on resume */ }

                    SectionLabel("Location")
                    Spacer(Modifier.padding(top = 6.dp))
                    if (!hasFine) {
                        Text(
                            "Location access is needed to block by place.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.padding(top = 8.dp))
                        GradientButton(text = "Grant location access", enabled = editable, onClick = {
                            fineLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        })
                    } else {
                        val places by vm.savedPlaces.collectAsState()
                        var showSaveDialog by remember { mutableStateOf(false) }
                        var placeToDelete by remember { mutableStateOf<SavedPlace?>(null) }

                        Text(
                            if (locCaptured) "Captured: %.4f, %.4f".format(lat, lng) else "No location captured yet.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.padding(top = 8.dp))
                        GradientButton(text = "Use my current location", enabled = editable, onClick = {
                            requestCurrentLocation(context) { la, ln -> lat = la; lng = ln; locCaptured = true }
                        })
                        if (locCaptured && editable) {
                            TextButton(onClick = { showSaveDialog = true }) { Text("Save this place") }
                        }

                        if (places.isNotEmpty()) {
                            Spacer(Modifier.padding(top = 8.dp))
                            SectionLabel("Saved places")
                            Spacer(Modifier.padding(top = 6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                places.forEach { place ->
                                    val isSel = locCaptured && place.latitude == lat && place.longitude == lng
                                    PlaceChip(place.name, isSel, editable,
                                        onClick = { lat = place.latitude; lng = place.longitude; locCaptured = true },
                                        onLongClick = { if (editable) placeToDelete = place })
                                }
                            }
                            Text("Tip: long-press a place to delete it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp))
                        }

                        if (!hasBg) {
                            Spacer(Modifier.padding(top = 12.dp))
                            BackgroundLocationWarning { openAppDetails(context) }
                        }
                        Spacer(Modifier.padding(top = 12.dp))
                        SectionLabel("Radius")
                        Spacer(Modifier.padding(top = 6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(100, 250, 500).forEach { r ->
                                ChipBtn("$r m", radius == r, editable) { radius = r }
                            }
                        }

                        if (showSaveDialog) {
                            TextInputDialog(
                                title = "Name this place",
                                label = "Name (e.g. UK)",
                                onConfirm = { vm.savePlace(it, lat, lng); showSaveDialog = false },
                                onDismiss = { showSaveDialog = false },
                            )
                        }
                        placeToDelete?.let { p ->
                            AlertDialog(
                                onDismissRequest = { placeToDelete = null },
                                title = { Text("Delete place") },
                                text = { Text("Remove \"${p.name}\" from saved places?") },
                                confirmButton = {
                                    TextButton(onClick = { vm.deletePlace(p); placeToDelete = null }) { Text("Delete") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { placeToDelete = null }) { Text("Cancel") }
                                },
                            )
                        }
                    }
                    Spacer(Modifier.padding(top = 12.dp))
                }
            }

            item {
                CollapsibleHeader(Icons.Filled.Apps, "Apps", selected.size, appsOpen) { appsOpen = !appsOpen }
                if (appsOpen) {
                    OutlinedTextField(
                        value = appQuery, onValueChange = { appQuery = it },
                        placeholder = { Text("Search apps") },
                        singleLine = true, enabled = editable,
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    )
                }
                Spacer(Modifier.padding(top = 4.dp))
            }
            if (appsOpen) {
                categorizedAppItems(
                    apps = apps.filter { it.installed },
                    selected = selected,
                    expandedCats = expandedCats.toSet(),
                    query = appQuery,
                    rowEnabled = { editable },
                    onToggleExpand = { cat ->
                        expandedCats = if (cat.name in expandedCats) expandedCats - cat.name
                        else expandedCats + cat.name
                    },
                    onToggle = { app, on ->
                        if (on) selected.add(app.packageName) else selected.remove(app.packageName)
                    },
                    onSelectAll = { catApps ->
                        if (editable) catApps.forEach {
                            if (!selected.contains(it.packageName)) selected.add(it.packageName)
                        }
                    },
                    onClearAll = { catApps ->
                        if (editable) catApps.forEach { selected.remove(it.packageName) }
                    },
                )
            }

            item { Spacer(Modifier.padding(top = 8.dp)) }
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
            Text(fmtClock12(minutes), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
    }
    if (show) {
        val state = rememberTimePickerState(minutes / 60, minutes % 60, false)
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

/** Warns that location blocking needs "Allow all the time" and opens settings on tap. */
@Composable
private fun BackgroundLocationWarning(onFix: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .clickable(onClick = onFix)
            .padding(14.dp),
    ) {
        Column {
            Text("Location blocking needs “Allow all the time”",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.padding(top = 2.dp))
            Text("Blocking runs in the background, so foreground-only location isn't enough. " +
                "Tap to open settings, then choose Location → “Allow all the time” (and Precise).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

private fun openAppDetails(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
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
        label = { Text(label, maxLines = 1) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

/**
 * A rounded value field with a unit and − / + steppers. The number is directly typeable
 * (numeric keyboard) and always kept within [min]..[max]. [name] is used for accessibility.
 */
@Composable
private fun StepperField(
    name: String,
    value: Int,
    unit: String,
    min: Int,
    max: Int,
    step: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            IconButton(
                onClick = { onChange((value - step).coerceIn(min, max)) },
                enabled = enabled && value > min,
            ) { Icon(Icons.Filled.Remove, contentDescription = "Decrease $name") }

            Row(verticalAlignment = Alignment.Bottom) {
                BasicTextField(
                    value = text,
                    onValueChange = { new ->
                        val digits = new.filter { it.isDigit() }.take(4)
                        text = digits
                        digits.toIntOrNull()?.coerceIn(min, max)?.let(onChange)
                    },
                    enabled = enabled,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.End,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.width(56.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(unit, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp))
            }

            IconButton(
                onClick = { onChange((value + step).coerceIn(min, max)) },
                enabled = enabled && value < max,
            ) { Icon(Icons.Filled.Add, contentDescription = "Increase $name") }
        }
    }
}

/** A small text-entry dialog; OK is enabled only when the trimmed text is non-blank. */
@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val valid = text.trim().isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(text.trim()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(30) },
                label = { Text(label) },
                singleLine = true,
            )
        },
    )
}

/** A pill chip for a saved place: tap to select, long-press to delete. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaceChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, maxLines = 1, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Captures the current location: uses a recent last-known fix if available, otherwise actively
 * requests a fresh one (so it still works when there's no cached fix). Async — [onResult] fires
 * on the main thread once a fix arrives. Needs Location permission.
 */
@SuppressLint("MissingPermission")
private fun requestCurrentLocation(context: Context, onResult: (Double, Double) -> Unit) {
    if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        openAppDetails(context)
        return
    }
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return
    val gps = LocationManager.GPS_PROVIDER
    val net = LocationManager.NETWORK_PROVIDER
    val last = runCatching { lm.getLastKnownLocation(gps) ?: lm.getLastKnownLocation(net) }.getOrNull()
    if (last != null) {
        onResult(last.latitude, last.longitude)
        return
    }
    // No cached fix — actively ask for one.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val provider = if (lm.isProviderEnabled(gps)) gps else net
        runCatching {
            lm.getCurrentLocation(provider, null, context.mainExecutor) { loc ->
                loc?.let { onResult(it.latitude, it.longitude) }
            }
        }
    }
}
