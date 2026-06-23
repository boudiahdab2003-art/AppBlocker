package com.appblocker.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.Check
import com.appblocker.data.QuickSession
import com.appblocker.data.Schedule
import com.appblocker.data.ScheduleType
import com.appblocker.data.SettingsStore
import kotlinx.coroutines.delay
import com.appblocker.service.AccessibilityUtil
import com.appblocker.ui.theme.AppGradients
import com.appblocker.ui.theme.softGlow

@Composable
fun BlockingScreen(
    onEditQuickBlock: () -> Unit,
    onNewSchedule: (ScheduleType) -> Unit,
    onEditSchedule: (Schedule) -> Unit,
    onOpenPermissions: () -> Unit,
    vm: HomeViewModel = viewModel(),
    scheduleVm: ScheduleViewModel = viewModel(),
    focusVm: FocusViewModel = viewModel(),
) {
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
                            GradientButton(text = "Start", enabled = !focusActive, onClick = {
                                SettingsStore.setQuickBlockPaused(context, false); paused = false
                            })
                            Spacer(Modifier.padding(top = 10.dp))
                            Text("Paused — tap the card to edit your list.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.padding(top = 12.dp))
                            TimerPomoRow(enabled = !focusActive, onTimer = { showTimer = true }, onPomo = { showPomo = true })
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
                            TimerPomoRow(enabled = !focusActive, onTimer = { showTimer = true }, onPomo = { showPomo = true })
                        }
                    }
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
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Adding new protection is always allowed — even during Strict Mode.
                ScheduleTile("Time", Icons.Filled.Schedule, enabled = true) {
                    onNewSchedule(ScheduleType.TIME)
                }
                ScheduleTile("Usage limit", Icons.Filled.HourglassEmpty, enabled = true) {
                    onNewSchedule(ScheduleType.USAGE_LIMIT)
                }
                ScheduleTile("Launch count", Icons.AutoMirrored.Filled.OpenInNew, enabled = true) {
                    onNewSchedule(ScheduleType.LAUNCH_COUNT)
                }
                ScheduleTile("Wi-Fi", Icons.Filled.Wifi, enabled = true) {
                    onNewSchedule(ScheduleType.WIFI)
                }
                ScheduleTile("Location", Icons.Filled.LocationOn, enabled = true) {
                    onNewSchedule(ScheduleType.LOCATION)
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
                    enabled = !focusActive,
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

    if (showTimer) {
        DurationPickerDialog(
            title = "Set the timer",
            initialMinutes = 25,
            onSave = { QuickSession.startTimer(context, it); tick = System.currentTimeMillis() },
            onDismiss = { showTimer = false },
        )
    }
    if (showPomo) {
        PomodoroPickerDialog(
            onStart = { work, brk, rounds ->
                QuickSession.startPomodoro(context, work, brk, rounds)
                tick = System.currentTimeMillis()
            },
            onDismiss = { showPomo = false },
        )
    }
}

/** Premium Pomodoro picker: rich selectable preset cards + Start. */
@Composable
private fun PomodoroPickerDialog(onStart: (Int, Int, Int) -> Unit, onDismiss: () -> Unit) {
    // (work, break, rounds)
    val presets = listOf(Triple(25, 5, 4), Triple(50, 10, 3), Triple(15, 3, 6))
    var selected by remember { mutableStateOf(presets.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            TextButton(onClick = { onStart(selected.first, selected.second, selected.third); onDismiss() }) {
                Text("Start")
            }
        },
        title = { Text("Pomodoro") },
        text = {
            Column {
                Text("Block during work, free during breaks.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.padding(top = 12.dp))
                presets.forEach { p ->
                    PomodoroOption(p, selected == p) { selected = p }
                    Spacer(Modifier.padding(top = 10.dp))
                }
            }
        },
    )
}

@Composable
private fun PomodoroOption(p: Triple<Int, Int, Int>, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (selected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary,
                RoundedCornerShape(16.dp)) else Modifier)
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Spa, null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${p.first} min work · ${p.second} min break",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface)
            Text("× ${p.third} rounds", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** Two neutral side-by-side buttons (Timer / Pomodoro) under the Start/Stop control. */
@Composable
private fun TimerPomoRow(enabled: Boolean, onTimer: () -> Unit, onPomo: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NeutralButton(Modifier.weight(1f), Icons.Filled.Timer, "Timer", enabled, compact = true, onClick = onTimer)
        NeutralButton(Modifier.weight(1f), Icons.Filled.Spa, "Pomodoro", enabled, compact = true, onClick = onPomo)
    }
}

/** Formats remaining millis as H:MM:SS or M:SS. */
private fun fmtClock(ms: Long): String {
    val total = (ms / 1000).toInt()
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun isTemplateActive(t: Template, schedules: List<Schedule>, adultOn: Boolean): Boolean {
    if (t.packages.isNotEmpty()) return schedules.any { it.name == t.title && it.enabled }
    if (t.enableAdult) return adultOn
    return false
}

private fun templateSummary(t: Template): String {
    val parts = mutableListOf<String>()
    if (t.packages.isNotEmpty()) {
        parts += "${t.packages.size} apps" + if (t.timeLabel.isNotEmpty()) " (${t.timeLabel})" else ""
    }
    if (t.keywords.isNotEmpty()) parts += "${t.keywords.size} words"
    if (t.enableAdult) parts += "the adult filter"
    return "This will block " + parts.joinToString(", ") + "."
}

@Composable
private fun SetupBanner(missing: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF3A2A12)).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFFFB020),
            modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Finish setup", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text("$missing required ${if (missing == 1) "step" else "steps"} left — tap to fix",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TemplateCard(modifier: Modifier, t: Template, active: Boolean, onClick: () -> Unit) {
    Box(modifier.fillMaxHeight().softGlow(RoundedCornerShape(20.dp), glow = t.colors.first(), elevation = 10.dp)) {
    Column(
        Modifier.fillMaxWidth().fillMaxHeight()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(t.colors))
            .clickable(onClick = onClick)
            .padding(14.dp)
            .heightIn(min = 178.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(t.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.weight(1f))
            if (active) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.28f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White,
                        modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("Active", style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text(t.title, style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2)
        Text(t.subtitle, style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.92f), maxLines = 2)
        if (t.timeLabel.isNotEmpty()) {
            Spacer(Modifier.padding(top = 4.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.Schedule, contentDescription = null, tint = Color.White,
                    modifier = Modifier.size(12.dp).padding(top = 2.dp))
                Spacer(Modifier.width(4.dp))
                Text(t.timeLabel, style = MaterialTheme.typography.labelSmall,
                    color = Color.White, maxLines = 2)
            }
        }
    }
    }
}

/** AppBlock-style muted category-icon pill shown on the empty/first-run Quick Block card. */
@Composable
private fun QuickBlockPill(apps: Int = 0, words: Int = 0, adultOn: Boolean = false, dimmed: Boolean = false) {
    val baseTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (dimmed) 0.45f else 1f)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Block, null, tint = baseTint, modifier = Modifier.size(18.dp))
        PillCount(Icons.Filled.PhoneAndroid, apps, baseTint)
        PillCount(Icons.Filled.Language, words, baseTint)
        Icon(Icons.Filled.PlayArrow, null, tint = baseTint, modifier = Modifier.size(18.dp))
        Icon(Icons.Filled.CalendarMonth, null, tint = baseTint, modifier = Modifier.size(18.dp))
        Text("18+", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            color = if (adultOn && !dimmed) MaterialTheme.colorScheme.primary else baseTint)
        Icon(Icons.Filled.ShoppingBasket, null, tint = baseTint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun PillCount(icon: ImageVector, count: Int, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(3.dp))
        Text("$count", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
            color = tint)
    }
}

/** Neutral pill button (surfaceVariant). `compact` = the smaller Timer/Pomodoro size. */
@Composable
private fun NeutralButton(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier.height(if (compact) 46.dp else 54.dp).clip(RoundedCornerShape(27.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(if (compact) 18.dp else 22.dp))
            Spacer(Modifier.width(if (compact) 6.dp else 8.dp))
            Text(label,
                style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun ScheduleTile(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .width(116.dp).height(120.dp)
            .softGlow(RoundedCornerShape(20.dp), elevation = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.padding(top = 10.dp))
        Text(label, style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun ScheduleCard(
    schedule: Schedule,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(11.dp))
                    .background(AppGradients.accentVertical),
                contentAlignment = Alignment.Center,
            ) {
                Icon(scheduleIcon(schedule.type), contentDescription = null, tint = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(schedule.name, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(scheduleSummary(schedule), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = schedule.enabled, enabled = enabled, onCheckedChange = onToggle)
        }
    }
}

private fun scheduleIcon(type: ScheduleType): ImageVector = when (type) {
    ScheduleType.TIME -> Icons.Filled.Schedule
    ScheduleType.USAGE_LIMIT -> Icons.Filled.HourglassEmpty
    ScheduleType.LAUNCH_COUNT -> Icons.AutoMirrored.Filled.OpenInNew
    ScheduleType.WIFI -> Icons.Filled.Wifi
    ScheduleType.LOCATION -> Icons.Filled.LocationOn
}

private fun scheduleSummary(s: Schedule): String {
    val apps = "${s.packages.size} app${if (s.packages.size == 1) "" else "s"}"
    return when (s.type) {
        ScheduleType.TIME ->
            "%02d:%02d–%02d:%02d · %s · %s".format(
                s.startMinutes / 60, s.startMinutes % 60,
                s.endMinutes / 60, s.endMinutes % 60,
                daysText(s.daysMask), apps,
            )
        ScheduleType.USAGE_LIMIT -> "${s.limitMinutes} min/day · $apps"
        ScheduleType.LAUNCH_COUNT -> "${s.limitCount} opens/day · $apps"
        ScheduleType.WIFI -> (if (s.wifiSsid.isBlank()) "Any Wi-Fi" else "Wi-Fi: ${s.wifiSsid}") + " · $apps"
        ScheduleType.LOCATION -> "Within ${s.radiusMeters} m · $apps"
    }
}

private fun daysText(mask: Int): String {
    if (mask and 0b1111111 == 0b1111111) return "Every day"
    val labels = listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")
    return (0..6).filter { (mask shr it) and 1 == 1 }.joinToString(" ") { labels[it] }
        .ifEmpty { "No days" }
}

@Composable
private fun ShieldMark() {
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(AppGradients.accent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Shield, contentDescription = null, tint = Color.White,
            modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun TimerPill(remainingMillis: Long) {
    val totalSeconds = remainingMillis / 1000
    val mm = totalSeconds / 60
    val ss = totalSeconds % 60
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        Spacer(Modifier.width(8.dp))
        Text("%02d:%02d".format(mm, ss), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
