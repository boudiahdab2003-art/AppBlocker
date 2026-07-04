package com.appblocker.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblocker.Dist
import com.appblocker.data.QuickSession
import com.appblocker.data.Schedule
import com.appblocker.data.ScheduleType
import com.appblocker.data.SettingsStore
import com.appblocker.data.TemplateStore
import com.appblocker.ui.theme.AppGradients
import com.appblocker.ui.theme.softGlow
import kotlinx.coroutines.delay

@Composable
fun BlockingScreen(
    onEditQuickBlock: () -> Unit,
    onOpenKeywords: () -> Unit,
    onNewSchedule: (ScheduleType) -> Unit,
    onEditSchedule: (Schedule) -> Unit,
    onOpenPermissions: () -> Unit,
    vm: HomeViewModel = viewModel(),
    scheduleVm: ScheduleViewModel = viewModel(),
    focusVm: FocusViewModel = viewModel(),
    updateVm: UpdateViewModel = viewModel(),
    appsVm: AppListViewModel = viewModel(),
) {
    val updateState by updateVm.state.collectAsState()
    val context = LocalContext.current
    val appsBlocked by vm.appsBlocked.collectAsState()
    val keywords by vm.keywordCount.collectAsState()
    val schedules by scheduleVm.schedules.collectAsState()
    val focusActive by focusVm.isActive.collectAsState()
    val remaining by focusVm.remainingMillis.collectAsState()
    // Re-checked on every resume so granting in Settings updates the UI immediately.
    val perms = rememberPermissions()
    val essentialMissing = perms.count { !it.granted && it.essential }
    val adultOn = SettingsStore.blockAdult(context)
    var pending by remember { mutableStateOf<Template?>(null) }
    var editingTemplate by remember { mutableStateOf<Template?>(null) }
    var showTimer by remember { mutableStateOf(false) }
    var showPomo by remember { mutableStateOf(false) }
    // 1s ticker drives the Timer/Pomodoro countdown.
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { tick = System.currentTimeMillis(); delay(1000) } }
    val session = remember(tick) { QuickSession.state(context) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            Spacer(Modifier.padding(top = 16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShieldMark()
                Spacer(Modifier.width(10.dp))
                Text(
                    "AppBlocker",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.weight(1f))
                if (focusActive) TimerPill(remaining)
                else if (session.active) TimerPill(session.remainingMillis)
            }
            Spacer(Modifier.padding(top = 16.dp))
            if (essentialMissing > 0) {
                SetupBanner(essentialMissing, onOpenPermissions)
                Spacer(Modifier.padding(top = 16.dp))
            }
            (updateState as? UpdateState.Available)?.let { avail ->
                UpdateBanner(avail.release.version) { updateVm.downloadAndInstall(avail.release) }
                Spacer(Modifier.padding(top = 16.dp))
            }
        }

        // Quick Block card
        item {
            val configured = appsBlocked > 0 || keywords > 0
            var paused by remember { mutableStateOf(SettingsStore.quickBlockPaused(context)) }
            LaunchedEffect(perms) { paused = SettingsStore.quickBlockPaused(context) }
            val active = configured && !paused
            Card(
                Modifier.fillMaxWidth()
                    .softGlow(RoundedCornerShape(22.dp), elevation = if (active) 14.dp else 4.dp)
                    .clickable { onEditQuickBlock() },
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = if (active) BorderStroke(1.5.dp, AppGradients.accent) else null,
            ) {
                Column(Modifier.padding(22.dp)) {
                    Text("Quick Block", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.padding(top = 14.dp))
                    QuickBlockPill(apps = appsBlocked, words = keywords, adultOn = adultOn, dimmed = !active)
                    Spacer(Modifier.padding(top = 18.dp))
                    when {
                        !configured -> GradientButton(text = "Start", onClick = onEditQuickBlock)
                        session.active -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(26.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                    contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Schedule, contentDescription = null,
                                        tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                Text("${session.label} ${fmtClock(session.remainingMillis)}",
                                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.padding(top = 14.dp))
                            NeutralButton(Modifier.fillMaxWidth(), Icons.Filled.Stop, "Stop") {
                                QuickSession.stop(context); tick = System.currentTimeMillis()
                            }
                        }
                        paused -> {
                            // Starting/strengthening is always allowed — even during Strict Mode.
                            GradientButton(text = "Start", enabled = true, onClick = {
                                SettingsStore.setQuickBlockPaused(context, false); paused = false
                            })
                            Spacer(Modifier.padding(top = 10.dp))
                            Text("Paused — tap the card to edit your list.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.padding(top = 12.dp))
                            TimerPomoRow(enabled = true, onTimer = { showTimer = true }, onPomo = { showPomo = true })
                        }
                        else -> {
                            NeutralButton(Modifier.fillMaxWidth(), Icons.Filled.Stop, "Stop", enabled = !focusActive) {
                                SettingsStore.setQuickBlockPaused(context, true); paused = true
                            }
                            Spacer(Modifier.padding(top = 14.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(26.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                    contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Check, contentDescription = null,
                                        tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                Text("Active", style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.weight(1f))
                                Text("Tap to edit", style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.padding(top = 12.dp))
                            TimerPomoRow(enabled = true, onTimer = { showTimer = true }, onPomo = { showPomo = true })
                        }
                    }
                }
            }
            Spacer(Modifier.padding(top = 24.dp))
        }

        // Blocked words card — its own discoverable entry point (the words were previously only
        // reachable buried inside the Quick Block editor).
        item {
            val scanAppCount = SettingsStore.keywordScanApps(context).size
            Card(
                Modifier.fillMaxWidth()
                    .softGlow(RoundedCornerShape(22.dp), elevation = 4.dp)
                    .clickable { onOpenKeywords() },
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Language, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Blocked words", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            if (keywords == 0) "Block words in browsers and chosen apps"
                            else "$keywords ${if (keywords == 1) "word" else "words"}" +
                                if (scanAppCount > 0) " · $scanAppCount ${if (scanAppCount == 1) "app" else "apps"}" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.padding(top = 24.dp))
        }

        // New schedule
        item {
            Text(
                "New schedule",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                if (schedules.isEmpty()) "Let's do this — schedule your first blocking!"
                else "Block apps on a timetable or after too much use.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(top = 12.dp))
            // On wide screens (tablets) the five tiles share the full row width — a scrolling
            // row of small fixed tiles leaves a dead gap there. Phones keep the scroll row.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val wide = maxWidth >= 640.dp
                val rowModifier =
                    if (wide) Modifier.fillMaxWidth()
                    else Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val tiles = buildList {
                        add(Triple("Time", Icons.Filled.Schedule, ScheduleType.TIME))
                        add(Triple("Usage limit", Icons.Filled.HourglassEmpty, ScheduleType.USAGE_LIMIT))
                        add(Triple("Launch count", Icons.AutoMirrored.Filled.OpenInNew, ScheduleType.LAUNCH_COUNT))
                        if (Dist.LOCATION_SCHEDULES) {
                            add(Triple("Wi-Fi", Icons.Filled.Wifi, ScheduleType.WIFI))
                            add(Triple("Location", Icons.Filled.LocationOn, ScheduleType.LOCATION))
                        }
                    }
                    tiles.forEach { (label, icon, type) ->
                        // Adding new protection is always allowed — even during Strict Mode.
                        ScheduleTile(
                            modifier = if (wide) Modifier.weight(1f) else Modifier.width(116.dp),
                            label = label, icon = icon, enabled = true,
                        ) { onNewSchedule(type) }
                    }
                }
            }
            Spacer(Modifier.padding(top = 24.dp))
        }

        // Schedules list
        if (schedules.isNotEmpty()) {
            item {
                Text(
                    "Your schedules",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.padding(top = 8.dp))
            }
            items(schedules, key = { it.id }) { s ->
                ScheduleCard(
                    schedule = s,
                    strictActive = focusActive,
                    onToggle = { scheduleVm.setEnabled(s, it) },
                    onClick = { onEditSchedule(s) },
                )
                Spacer(Modifier.padding(top = 10.dp))
            }
        }

        // Templates — one-tap preset bundles
        item {
            Spacer(Modifier.padding(top = 8.dp))
            Text("Templates", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text("One tap to block a whole category.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.padding(top = 12.dp))
        }
        items(appTemplates.chunked(2)) { rowItems ->
            // IntrinsicSize.Min lets both cards grow to the taller one's content instead of
            // clipping a fixed height (time labels were getting cut off on some font scales).
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowItems.forEach { t ->
                    TemplateCard(
                        Modifier.weight(1f), t,
                        active = isTemplateActive(t, schedules, adultOn),
                        onEditApps = if (t.packages.isNotEmpty()) ({ editingTemplate = t }) else null,
                    ) { pending = t }
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.padding(top = 12.dp))
        }

        item { Spacer(Modifier.padding(top = 16.dp)) }
    }

    pending?.let { t ->
        AlertDialog(
            onDismissRequest = { pending = null },
            confirmButton = {
                TextButton(onClick = {
                    vm.applyTemplate(t)
                    Toast.makeText(context, "Applied “${t.title}”", Toast.LENGTH_SHORT).show()
                    pending = null
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
            title = { Text("Apply “${t.title}”?") },
            text = { Text(templateSummary(t)) },
        )
    }

    editingTemplate?.let { t ->
        TemplateAppsSheet(
            template = t,
            appsVm = appsVm,
            onDismiss = { editingTemplate = null },
            onSave = { pkgs ->
                TemplateStore.setPackages(context, t.id, pkgs)
                editingTemplate = null
                Toast.makeText(context, "Saved apps for “${t.title}”", Toast.LENGTH_SHORT).show()
            },
        )
    }

    if (showTimer) {
        DurationPickerDialog(
            title = "Set the timer",
            initialMinutes = 25,
            onSave = {
                QuickSession.startTimer(context, it); tick = System.currentTimeMillis()
                Toast.makeText(context, "Timer started — blocks for ${fmtDuration(it)}", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showTimer = false },
        )
    }
    if (showPomo) {
        PomodoroPickerDialog(
            onStart = { work, brk, rounds ->
                QuickSession.startPomodoro(context, work, brk, rounds)
                tick = System.currentTimeMillis()
                Toast.makeText(context, "Pomodoro started — $work min work · $brk min break", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showPomo = false },
        )
    }
}

/** Bottom sheet to choose which of the user's installed apps a template blocks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplateAppsSheet(
    template: Template,
    appsVm: AppListViewModel,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    val apps by appsVm.apps.collectAsState()
    val installed = remember(apps) { apps.filter { it.installed } }
    val selected = remember(template.id) {
        (TemplateStore.packagesFor(context, template.id) ?: template.packages.map { it.first })
            .toMutableStateList()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text("Choose apps for “${template.title}”", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text("Pick which of your apps this template blocks.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                items(installed, key = { it.packageName }) { app ->
                    val checked = selected.contains(app.packageName)
                    AppCheckRow(app, checked = checked, enabled = true) { on ->
                        if (on) selected.add(app.packageName) else selected.remove(app.packageName)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            GradientButton(text = "Save", onClick = { onSave(selected.toList()) },
                modifier = Modifier.fillMaxWidth())
        }
    }
}
