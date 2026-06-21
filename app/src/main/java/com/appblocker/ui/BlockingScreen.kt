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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
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
import com.appblocker.data.Schedule
import com.appblocker.data.ScheduleType
import com.appblocker.data.SettingsStore
import com.appblocker.service.AccessibilityUtil
import com.appblocker.ui.theme.AppGradients

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
            Card(
                Modifier.fillMaxWidth().clickable { onEditQuickBlock() },
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = if (protectionOn) BorderStroke(1.5.dp, AppGradients.accent) else null,
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (protectionOn) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                            contentDescription = null,
                            tint = if (protectionOn) MaterialTheme.colorScheme.primary else Color(0xFFFFB020),
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Quick Block",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.padding(top = 12.dp))
                    SummaryPill(apps = appsBlocked, words = keywords, adultOn = adultOn)
                    Spacer(Modifier.padding(top = 12.dp))
                    if (protectionOn) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Active", style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.weight(1f))
                            Text("Tap to edit", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Text("Tap to turn on the blocker, then choose what to block.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (!protectionOn) {
                Spacer(Modifier.padding(top = 8.dp))
                Text(
                    "Turn on protection ▸",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                )
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
                "Block apps on a timetable or after too much use.",
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
                    TemplateCard(Modifier.weight(1f), t) {
                        vm.applyTemplate(t)
                        Toast.makeText(context, "Applied “${t.title}”", Toast.LENGTH_SHORT).show()
                    }
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.padding(top = 12.dp))
        }

        item { Spacer(Modifier.padding(top = 16.dp)) }
    }
}

@Composable
private fun TemplateCard(modifier: Modifier, t: Template, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(t.colors))
            .clickable(onClick = onClick)
            .padding(16.dp)
            .height(140.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(t.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.weight(1f))
        Text(t.title, style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, color = Color.White)
        Text(t.subtitle, style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.9f))
    }
}

@Composable
private fun SummaryPill(apps: Int, words: Int, adultOn: Boolean) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PillItem(Icons.Filled.Block, null)
        PillItem(Icons.Filled.PhoneAndroid, "$apps")
        PillItem(Icons.Filled.Language, "$words")
        PillItem(Icons.Filled.HourglassEmpty, null) // schedules placeholder glyph
        Text("18+", style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (adultOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PillItem(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp))
        if (value != null) {
            Spacer(Modifier.width(3.dp))
            Text(value, style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
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
        modifier.clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                    .background(AppGradients.accentVertical),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Color.White)
            }
            Spacer(Modifier.padding(top = 8.dp))
            Text(label, style = MaterialTheme.typography.titleSmall,
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
