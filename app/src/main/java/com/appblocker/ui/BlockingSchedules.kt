package com.appblocker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.appblocker.data.Schedule
import com.appblocker.data.ScheduleType
import com.appblocker.ui.theme.AppGradients
import com.appblocker.ui.theme.softGlow

@Composable
internal fun ScheduleTile(
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
internal fun ScheduleCard(
    schedule: Schedule,
    strictActive: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    // During Strict you may turn a schedule ON (strengthen) but not OFF; the card still opens
    // (the editor itself stays read-only for an existing schedule during Strict).
    val toggleEnabled = !strictActive || !schedule.enabled
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
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
            Switch(checked = schedule.enabled, enabled = toggleEnabled, onCheckedChange = onToggle)
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
            "${fmtWindow(s.startMinutes, s.endMinutes)} · ${daysText(s.daysMask)} · $apps"
        ScheduleType.USAGE_LIMIT -> "${fmtDuration(s.limitMinutes)}/day · $apps"
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
