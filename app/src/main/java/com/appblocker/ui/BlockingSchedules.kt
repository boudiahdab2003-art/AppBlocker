package com.appblocker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.appblocker.data.Schedule
import com.appblocker.data.ScheduleType
import com.appblocker.ui.theme.AppGradients
import com.appblocker.ui.theme.softGlow

/** One "create a schedule" tile. [modifier] carries the width — a fixed 116dp on phones
 *  (scrolling row) or weight(1f) on wide screens where the five tiles share the full row. */
@Composable
internal fun ScheduleTile(
    modifier: Modifier,
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // Grow the tile with the system font size so two-line labels ("Usage limit", "Launch
    // count") never clip on phones set to a larger font. Padding + icon are dp (not font-scaled),
    // so only the text needs the extra room; all tiles share the value, staying uniform.
    val fontScale = LocalDensity.current.fontScale
    val tileHeight = (134f + ((fontScale - 1f).coerceIn(0f, 1.2f) * 48f)).dp
    Column(
        modifier
            .height(tileHeight)
            .softGlow(RoundedCornerShape(20.dp), elevation = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                RoundedCornerShape(20.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            // Slim side padding: at larger system fonts a one-word label ("Location") needs
            // the extra width or it breaks mid-word ("Locatio/n").
            .padding(vertical = 16.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Icon in a soft circle with a small "+" badge — reads as "tap to create".
        Box {
            Box(
                Modifier.size(46.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp))
            }
            Box(
                Modifier.size(18.dp).align(Alignment.TopEnd).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White,
                    modifier = Modifier.size(13.dp))
            }
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
    onDelete: (() -> Unit)? = null,
) {
    // During Strict you may turn a schedule ON (strengthen) but not OFF; the card still opens
    // (the editor itself stays read-only for an existing schedule during Strict).
    val toggleEnabled = !strictActive || !schedule.enabled
    var confirmDelete by remember { mutableStateOf(false) }
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
            // Deleting weakens blocking, so the shortcut disappears during Strict Mode
            // (mirrors the editor, which goes read-only for existing schedules then).
            if (onDelete != null && !strictActive) {
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete ${schedule.name}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Switch(checked = schedule.enabled, enabled = toggleEnabled, onCheckedChange = onToggle)
        }
    }
    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this schedule?") },
            text = { Text("“${schedule.name}” — ${scheduleSummary(schedule)}. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
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
