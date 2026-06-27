package com.appblocker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appblocker.data.Schedule
import com.appblocker.ui.theme.softGlow

@Composable
internal fun TemplateCard(modifier: Modifier, t: Template, active: Boolean, onClick: () -> Unit) {
    Box(modifier.fillMaxHeight().softGlow(RoundedCornerShape(20.dp), glow = t.colors.first(), elevation = 10.dp)) {
    Column(
        Modifier.fillMaxWidth().fillMaxHeight()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(t.colors))
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(t.icon, contentDescription = "Block ${t.title}", tint = Color.White, modifier = Modifier.size(24.dp))
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
        Spacer(Modifier.height(12.dp))
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

internal fun isTemplateActive(t: Template, schedules: List<Schedule>, adultOn: Boolean): Boolean {
    if (t.packages.isNotEmpty()) return schedules.any { it.name == t.title && it.enabled }
    if (t.enableAdult) return adultOn
    return false
}

internal fun templateSummary(t: Template): String {
    val parts = mutableListOf<String>()
    if (t.packages.isNotEmpty()) {
        parts += "${t.packages.size} apps" + if (t.timeLabel.isNotEmpty()) " (${t.timeLabel})" else ""
    }
    if (t.keywords.isNotEmpty()) parts += "${t.keywords.size} words"
    if (t.enableAdult) parts += "the adult filter"
    return "This will block " + parts.joinToString(", ") + "."
}
