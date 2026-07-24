package com.appblocker.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appblocker.ui.theme.AppGradients
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.ceil

/**
 * Insights' chart furniture: the Day/Week/Trend tab strip, the bar chart every tab draws, and
 * the axis-label helpers. Split out of InsightsScreen.kt, which had grown past 1200 lines.
 */

@Composable
internal fun SegmentedTabs(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf("Day", "Week", "Trend")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(50))
                    .then(if (on) Modifier.background(AppGradients.accent) else Modifier)
                    .clickable { onSelect(i) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}


@Composable
internal fun BarChart(
    values: IntArray,
    maxMinutes: Int,
    bottomLabels: List<String>,
    yLabels: List<String>,
    readoutLabel: (Int) -> String,
    valueLabel: (Int) -> String = { InsightsViewModel.fmt(values[it]) },
) {
    val barBrush = AppGradients.chartBar
    val trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val highlight = MaterialTheme.colorScheme.primary
    val peak = remember(values) { values.indices.maxByOrNull { values[it] } ?: 0 }
    var selected by remember(values) { mutableIntStateOf(peak) }
    // Bars grow in when the data (tab) changes — read inside the Canvas so it redraws per frame.
    val growth = remember(values) { Animatable(0f) }
    LaunchedEffect(values) { growth.animateTo(1f, tween(500)) }

    // Readout: the selected bar's exact value + when it was.
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(valueLabel(selected), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.width(6.dp))
        Text("· ${readoutLabel(selected)}", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (selected == peak && values[peak] > 0) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.clip(RoundedCornerShape(50)).background(highlight.copy(alpha = 0.18f))
                .padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text("peak", style = MaterialTheme.typography.labelSmall, color = highlight)
            }
        }
    }
    Row(Modifier.fillMaxWidth()) {
        val n = values.size
        Canvas(
            Modifier.weight(1f).height(180.dp)
                .pointerInput(values) {
                    detectTapGestures { o -> selected = (o.x / (size.width / n)).toInt().coerceIn(0, n - 1) }
                }
                .pointerInput(values) {
                    detectHorizontalDragGestures { change, _ ->
                        selected = (change.position.x / (size.width / n)).toInt().coerceIn(0, n - 1)
                    }
                },
        ) {
            val h = size.height
            val w = size.width
            // gridlines (0, 0.5, 1.0)
            listOf(0f, 0.5f, 1f).forEach { f ->
                val y = h * f
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
            val slot = w / n
            val barW = slot * 0.5f
            val radius = CornerRadius(barW / 2, barW / 2)
            values.forEachIndexed { i, v ->
                val x = i * slot + (slot - barW) / 2
                // faint full-height track behind each bar for a polished look
                drawRoundRect(trackColor, Offset(x, 0f), Size(barW, h), radius)
                val frac = (v.toFloat() / maxMinutes).coerceIn(0f, 1f)
                val barH = h * frac * growth.value
                if (barH > 1f) {
                    drawRoundRect(barBrush, Offset(x, h - barH), Size(barW, barH), radius)
                }
                // highlight the selected bar (full-height marker + solid fill)
                if (i == selected) {
                    drawRoundRect(highlight.copy(alpha = 0.12f), Offset(x, 0f), Size(barW, h), radius)
                    if (barH > 1f) drawRoundRect(highlight, Offset(x, h - barH), Size(barW, barH), radius)
                }
            }
        }
        Column(Modifier.height(180.dp).padding(start = 6.dp), verticalArrangement = Arrangement.SpaceBetween) {
            yLabels.forEach {
                Text(it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(end = 28.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        bottomLabels.forEach {
            Text(it, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}


internal fun chartCap(values: IntArray): Int {
    val maxV = (values.maxOrNull() ?: 0).coerceAtLeast(60)
    return (ceil(maxV / 60.0) * 60).toInt()
}

/** A date [daysAgo] days before today as "MMM d", e.g. "Jun 5". */


internal fun dateLabel(daysAgo: Int): String {
    val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -daysAgo) }
    return SimpleDateFormat("MMM d", Locale.getDefault()).format(cal.time)
}

/** A 0–23 hour as a 12-hour clock label, e.g. 0 -> "12 AM", 19 -> "7 PM". */


internal fun hourLabel(h: Int): String {
    val period = if (h < 12) "AM" else "PM"
    val h12 = if (h % 12 == 0) 12 else h % 12
    return "$h12 $period"
}

/** Weekday label for the day [daysAgo] days before today. [short]=true gives "Mon"/"Today"
 *  for the readout; [short]=false gives the one-letter axis label. */


internal fun weekdayLabel(daysAgo: Int, short: Boolean): String {
    if (daysAgo == 0 && short) return "Today"
    val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -daysAgo) }
    val pattern = if (short) "EEE" else "EEEEE"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(cal.time)
}


@Composable
internal fun CategoryBreakdown(cats: List<CatSlice>) {
    Row(
        Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(50)),
    ) {
        cats.forEach { c ->
            Box(Modifier.weight(c.minutes.toFloat().coerceAtLeast(0.01f)).fillMaxHeight().background(c.color))
        }
    }
    Spacer(Modifier.padding(top = 12.dp))
    // legend: tinted pill chips in the category colours, wrapping as needed
    legendPills(cats)
}

@OptIn(ExperimentalLayoutApi::class)


@Composable
internal fun legendPills(cats: List<CatSlice>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        cats.forEach { c ->
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(c.color.copy(alpha = 0.15f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(c.color))
                Spacer(Modifier.width(6.dp))
                Text("${c.label} ${InsightsViewModel.fmt(c.minutes)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold, color = c.color, maxLines = 1)
            }
        }
    }
}
