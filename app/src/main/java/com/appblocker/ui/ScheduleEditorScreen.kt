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
import com.appblocker.service.timeScheduleCanFire
import androidx.compose.ui.res.stringResource
import com.appblocker.R

private val DAY_LABELS = listOf("S", "M", "T", "W", "T", "F", "S") // bit0 = Sunday

/**
 * "Is this schedule actually doing anything?" — answered, and changed, on the screen you land on
 * when you tap a schedule.
 *
 * Before this, the on/off switch existed only on the card in the list. Tapping that card opened
 * the editor, so the one screen dedicated to a schedule was the one screen that couldn't tell you
 * whether it was running, and you had to go back out to turn it on.
 *
 * Says what "off" means rather than leaving it to be guessed: a switched-off schedule is not a
 * deleted one, and the difference matters on a screen that also has a delete button.
 */
@Composable
private fun ScheduleEnabledRow(
    on: Boolean,
    isNew: Boolean,
    canChange: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    isNew && on -> stringResource(R.string.sched_start_now)
                    isNew -> stringResource(R.string.sched_create_off)
                    on -> stringResource(R.string.sched_is_on)
                    else -> stringResource(R.string.sched_is_off)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                when {
                    !canChange -> "Strict Mode is running. A schedule can be switched on while " +
                        "it is, but not off."
                    isNew && on -> stringResource(R.string.sched_will_start)
                    isNew -> stringResource(R.string.sched_saved_off)
                    on -> stringResource(R.string.sched_enforcing)
                    else -> stringResource(R.string.sched_kept)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = on, enabled = canChange, onCheckedChange = onChange)
    }
}

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
    // Whether the schedule is actually being enforced. It used to live only on the card in the
    // list, which meant tapping a schedule took you to the one screen that could not answer
    // "is this on?" — the owner asked for it in here, where he already is.
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
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
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.sched_delete),
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        bottomBar = {
            GradientButton(
                text = stringResource(
                    if (existing == null) R.string.sched_create else R.string.common_save,
                ),
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
                            enabled = enabled,
                        )
                    )
                    onBack()
                },
                enabled = editable && selected.isNotEmpty() &&
                    (type != ScheduleType.LOCATION || locCaptured) &&
                    (type != ScheduleType.USAGE_LIMIT || limit >= 1) &&
                    // A schedule that can never fire must not be saveable — it would sit in the
                    // list looking on, and protect nothing. See timeScheduleCanFire.
                    (type != ScheduleType.TIME || timeScheduleCanFire(daysMask, start, end)),
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
                ScheduleEnabledRow(
                    on = enabled,
                    isNew = existing == null,
                    // Deliberately NOT `editable`. That rule locks an existing schedule outright
                    // while Strict is running, which is right for its settings — but switching a
                    // schedule ON strengthens blocking, and Strict has always allowed that (it is
                    // exactly what the card in the list allows). Using `editable` here would have
                    // made this screen stricter than Strict.
                    canChange = existing == null || !strictActive || !enabled,
                    onChange = { on ->
                        enabled = on
                        // An existing schedule's switch takes effect at once, exactly as it does
                        // on the card — and it has to, because during Strict the Save button
                        // below is disabled, and turning one on is the thing Strict permits.
                        if (existing != null) vm.setEnabled(existing, on)
                    },
                )
                Spacer(Modifier.padding(top = 12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.sched_name)) },
                    singleLine = true,
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.padding(top = 8.dp))
            }

            when (type) {
                ScheduleType.TIME -> item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TimeField(stringResource(R.string.sched_start), start, enabled = editable, modifier = Modifier.weight(1f)) { start = it }
                        TimeField("End", end, enabled = editable, modifier = Modifier.weight(1f)) { end = it }
                    }
                    Spacer(Modifier.padding(top = 12.dp))
                    SectionLabel(stringResource(R.string.sched_days))
                    Spacer(Modifier.padding(top = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DAY_LABELS.forEachIndexed { i, label ->
                            val on = (daysMask shr i) and 1 == 1
                            ChipBtn(label, on, editable) { daysMask = daysMask xor (1 shl i) }
                        }
                    }
                    // Says why Save is greyed out. Without it the button just looks broken — and
                    // the alternative (letting it save) is a schedule that protects nothing while
                    // looking like it does, which is the failure nobody notices.
                    if (!timeScheduleCanFire(daysMask, start, end)) {
                        Spacer(Modifier.padding(top = 8.dp))
                        Text(
                            if ((daysMask and 0b1111111) == 0)
                                stringResource(R.string.sched_pick_a_day)
                            else "Start and end are the same, so this would never run. " +
                                "For a whole day, use 00:00 to 23:59.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.padding(top = 12.dp))
                }
                ScheduleType.USAGE_LIMIT -> item {
                    val hours = limit / 60
                    val mins = limit % 60
                    SectionLabel(stringResource(R.string.sched_daily_limit))
                    Spacer(Modifier.padding(top = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StepperField("hours", hours, "h", min = 0, max = 23, step = 1,
                            enabled = editable, modifier = Modifier.weight(1f)) { limit = it * 60 + mins }
                        StepperField("minutes", mins, "m", min = 0, max = 59, step = 5,
                            enabled = editable, modifier = Modifier.weight(1f)) { limit = hours * 60 + it }
                    }
                    // A daily limit is measured from Android's usage statistics, and reading them
                    // needs the "usage access" special permission. Without it the app sees zero
                    // minutes for every app, so the limit is never reached and this schedule
                    // silently never blocks. That permission is listed as optional elsewhere in
                    // the app (it only powers Insights there) — for THIS schedule type it is
                    // required, and nothing said so. Same failure the Wi-Fi and Location types
                    // already warn about; this was the third one, left out.
                    val usageTick = resumeTick()
                    val hasUsage = remember(usageTick) { hasUsageAccess(context) }
                    if (!hasUsage) {
                        Spacer(Modifier.padding(top = 12.dp))
                        UsageAccessWarning { openUsageAccess(context) }
                    }
                    Spacer(Modifier.padding(top = 12.dp))
                }
                ScheduleType.LAUNCH_COUNT -> item {
                    SectionLabel(stringResource(R.string.sched_open_limit))
                    Spacer(Modifier.padding(top = 6.dp))
                    StepperField("opens", limitCount, "opens", min = 1, max = 999, step = 1,
                        enabled = editable, modifier = Modifier.fillMaxWidth()) { limitCount = it }
                    Spacer(Modifier.padding(top = 12.dp))
                }
                ScheduleType.WIFI -> item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.sched_any_wifi), Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                        Switch(checked = anyWifi, enabled = editable, onCheckedChange = { anyWifi = it })
                    }
                    if (!anyWifi) {
                        Spacer(Modifier.padding(top = 6.dp))
                        OutlinedTextField(
                            value = wifiSsid, onValueChange = { wifiSsid = it },
                            label = { Text(stringResource(R.string.sched_wifi_name)) }, singleLine = true, enabled = editable,
                            shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth(),
                        )
                        // Android refuses to reveal the network name without location access, and
                        // hands back a placeholder instead — so a named-network schedule silently
                        // never matches. It used to say so only as grey small print, which is easy
                        // to miss and doesn't say whether the permission is actually granted. Now
                        // it checks, and offers the grant, the way the Location type does.
                        val wifiTick = resumeTick()
                        val hasLoc = remember(wifiTick) { hasLocation(context) }
                        val wifiLocLauncher = rememberLauncherForActivityResult(
                            ActivityResultContracts.RequestPermission()
                        ) { /* state re-reads on resume */ }
                        if (hasLoc) {
                            Text(stringResource(R.string.sched_wifi_needs_location),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp))
                        } else {
                            Spacer(Modifier.padding(top = 8.dp))
                            Box(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.errorContainer)
                                    .padding(14.dp),
                            ) {
                                Column {
                                    Text(stringResource(R.string.sched_wont_work),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onErrorContainer)
                                    Spacer(Modifier.padding(top = 2.dp))
                                    Text(stringResource(R.string.sched_wifi_needs_location_body),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                            Spacer(Modifier.padding(top = 8.dp))
                            GradientButton(
                            text = stringResource(R.string.sched_grant_location),
                            enabled = editable, onClick = {
                                wifiLocLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            })
                        }
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

                    SectionLabel(stringResource(R.string.sched_location))
                    Spacer(Modifier.padding(top = 6.dp))
                    if (!hasFine) {
                        Text(
                            stringResource(R.string.sched_location_needed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.padding(top = 8.dp))
                        GradientButton(
                            text = stringResource(R.string.sched_grant_location),
                            enabled = editable, onClick = {
                            fineLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        })
                    } else {
                        val places by vm.savedPlaces.collectAsState()
                        var showSaveDialog by remember { mutableStateOf(false) }
                        var placeToDelete by remember { mutableStateOf<SavedPlace?>(null) }

                        Text(
                            if (locCaptured) "Captured: %.4f, %.4f".format(lat, lng) else stringResource(R.string.sched_no_location),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.padding(top = 8.dp))
                        GradientButton(
                            text = stringResource(R.string.sched_use_location),
                            enabled = editable, onClick = {
                            requestCurrentLocation(context) { la, ln -> lat = la; lng = ln; locCaptured = true }
                        })
                        if (locCaptured && editable) {
                            TextButton(onClick = { showSaveDialog = true }) {
                                Text(stringResource(R.string.sched_save_place))
                            }
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
                            Text(stringResource(R.string.sched_longpress_tip),
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
                                title = stringResource(R.string.sched_name_place),
                                label = stringResource(R.string.sched_place_label),
                                onConfirm = { vm.savePlace(it, lat, lng); showSaveDialog = false },
                                onDismiss = { showSaveDialog = false },
                            )
                        }
                        placeToDelete?.let { p ->
                            AlertDialog(
                                onDismissRequest = { placeToDelete = null },
                                title = { Text(stringResource(R.string.sched_delete_place)) },
                                text = { Text(stringResource(R.string.sched_delete_place_body, p.name)) },
                                confirmButton = {
                                    TextButton(onClick = { vm.deletePlace(p); placeToDelete = null }) { Text(stringResource(R.string.common_delete)) }
                                },
                                dismissButton = {
                                    TextButton(onClick = { placeToDelete = null }) { Text(stringResource(R.string.common_cancel)) }
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
                        placeholder = { Text(stringResource(R.string.editor_search_apps)) },
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
            dismissButton = { TextButton(onClick = { show = false }) { Text(stringResource(R.string.common_cancel)) } },
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
            Text(stringResource(R.string.sched_needs_all_the_time),
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

/** Same shape as [BackgroundLocationWarning] — this schedule type cannot work at all yet. */
@Composable
private fun UsageAccessWarning(onFix: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .clickable(onClick = onFix)
            .padding(14.dp),
    ) {
        Column {
            Text(stringResource(R.string.sched_wont_work),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.padding(top = 2.dp))
            Text("A daily limit is measured from Android's usage statistics, which AppBlocker " +
                "can't read yet. Until you allow it, every app looks like zero minutes and this " +
                "schedule can never block. Tap to open settings, then switch AppBlocker on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

private fun openUsageAccess(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
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
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
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
