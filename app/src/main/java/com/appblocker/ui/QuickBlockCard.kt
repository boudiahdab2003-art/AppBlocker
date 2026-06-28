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
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Premium Pomodoro picker: rich selectable preset cards + Start. */
@Composable
internal fun PomodoroPickerDialog(onStart: (Int, Int, Int) -> Unit, onDismiss: () -> Unit) {
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
internal fun TimerPomoRow(enabled: Boolean, onTimer: () -> Unit, onPomo: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NeutralButton(Modifier.weight(1f), Icons.Filled.Timer, "Timer", enabled, compact = true, onClick = onTimer)
        NeutralButton(Modifier.weight(1f), Icons.Filled.Spa, "Pomodoro", enabled, compact = true, onClick = onPomo)
    }
}

/** AppBlock-style muted category-icon pill shown on the empty/first-run Quick Block card. */
@Composable
internal fun QuickBlockPill(apps: Int = 0, words: Int = 0, adultOn: Boolean = false, dimmed: Boolean = false) {
    val baseTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (dimmed) 0.45f else 1f)
    val pillDescription = "Blocking $apps apps, $words words" + if (adultOn) ", adult filter on" else ""
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clearAndSetSemantics { contentDescription = pillDescription },
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
internal fun NeutralButton(
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

/** Formats a whole-minute duration as e.g. "25 min" or "1 hr 30 min". */
internal fun fmtDuration(minutes: Int): String {
    val h = minutes / 60; val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "$h hr $m min"
        h > 0 -> "$h hr"
        else -> "$m min"
    }
}

/** Formats remaining millis as H:MM:SS or M:SS. */
internal fun fmtClock(ms: Long): String {
    val total = (ms / 1000).toInt()
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
