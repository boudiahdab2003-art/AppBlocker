package com.appblocker.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.Check
import com.appblocker.data.Schedule
import com.appblocker.data.ScheduleType
import com.appblocker.data.SettingsStore
import com.appblocker.service.AccessibilityUtil
import com.appblocker.ui.theme.AppGradients
import com.appblocker.ui.theme.softGlow

@Composable
fun BlockingScreen(
    onEditQuickBlock: () -> Unit,
    onNewSchedule: (ScheduleType) -> Unit,
    onEditSchedule: (Schedule) -> Unit,
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
    val protectionOn = AccessibilityUtil.isEnabled(context)
    val adultOn = SettingsStore.blockAdult(context)
    var pending by remember { mutableStateOf<Template?>(null) }

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
            }
            Spacer(Modifier.padding(top = 16.dp))
        }

        // Quick Block card
        item {
            val firstRun = appsBlocked == 0 && keywords == 0 && !protectionOn
            Card(
                Modifier.fillMaxWidth()
                    .softGlow(RoundedCornerShape(22.dp), elevation = if (protectionOn) 14.dp else 4.dp)
                    .then(if (firstRun) Modifier else Modifier.clickable { onEditQuickBlock() }),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = if (protectionOn) BorderStroke(1.5.dp, AppGradients.accent) else null,
            ) {
                Column(Modifier.padding(22.dp)) {
                    if (firstRun) {
                        Text("Quick Block", style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.padding(top = 14.dp))
                        QuickBlockPill()
                        Spacer(Modifier.padding(top = 18.dp))
                        GradientButton(text = "Start", onClick = onEditQuickBlock)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (protectionOn) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                                contentDescription = null,
                                tint = if (protectionOn) MaterialTheme.colorScheme.primary else Color(0xFFFFB020),
                                modifier = Modifier.size(30.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("Quick Block", style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Spacer(Modifier.padding(top = 16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatChip(Modifier.weight(1f), Icons.Filled.PhoneAndroid, "$appsBlocked", "Apps")
                            StatChip(Modifier.weight(1f), Icons.Filled.Language, "$keywords", "Words")
                            StatChip(Modifier.weight(1f), Icons.Filled.Block, if (adultOn) "On" else "Off", "18+")
                        }
                        Spacer(Modifier.padding(top = 16.dp))
                        if (protectionOn) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Active", style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.weight(1f))
                                Text("Tap to edit", style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            Text("Tap to turn on the blocker, then choose what to block.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ScheduleTile(
                    Modifier.weight(1f), "Time", Icons.Filled.Schedule,
                    enabled = !focusActive,
                ) { onNewSchedule(ScheduleType.TIME) }
                ScheduleTile(
                    Modifier.weight(1f), "Usage limit", Icons.Filled.HourglassEmpty,
                    enabled = !focusActive,
                ) { onNewSchedule(ScheduleType.USAGE_LIMIT) }
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
private fun TemplateCard(modifier: Modifier, t: Template, active: Boolean, onClick: () -> Unit) {
    Box(modifier.softGlow(RoundedCornerShape(20.dp), glow = t.colors.first(), elevation = 10.dp)) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(t.colors))
            .clickable(onClick = onClick)
            .padding(16.dp)
            .height(162.dp),
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
        Text(t.title, style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, color = Color.White)
        Text(t.subtitle, style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.9f))
        if (t.timeLabel.isNotEmpty()) {
            Spacer(Modifier.padding(top = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Schedule, contentDescription = null, tint = Color.White,
                    modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text(t.timeLabel, style = MaterialTheme.typography.labelMedium,
                    color = Color.White, maxLines = 1)
            }
        }
    }
    }
}

/** AppBlock-style muted category-icon pill shown on the empty/first-run Quick Block card. */
@Composable
private fun QuickBlockPill() {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val tint = MaterialTheme.colorScheme.onSurfaceVariant
        listOf(
            Icons.Filled.Block, Icons.Filled.PhoneAndroid, Icons.Filled.Language,
            Icons.Filled.Tag, Icons.Filled.PlayArrow, Icons.Filled.CalendarMonth,
        ).forEach { Icon(it, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp)) }
        Text("18+", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = tint)
        Icon(Icons.Filled.ShoppingBasket, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun StatChip(modifier: Modifier, icon: ImageVector, value: String, label: String) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp))
        Spacer(Modifier.padding(top = 6.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ScheduleTile(
    modifier: Modifier,
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier.softGlow(RoundedCornerShape(20.dp), elevation = 6.dp).clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(13.dp))
                    .background(AppGradients.accentVertical),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.padding(top = 10.dp))
            Text(label, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
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
                Icon(
                    if (schedule.type == ScheduleType.TIME) Icons.Filled.Schedule
                    else Icons.Filled.HourglassEmpty,
                    contentDescription = null, tint = Color.White,
                )
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
