package com.appblocker.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblocker.ui.theme.AppGradients
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
fun InsightsScreen(vm: InsightsViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) } // 0 Day, 1 Week, 2 Trend

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            Spacer(Modifier.padding(top = 14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { openUsageAccessSettings(context) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.weight(1f))
                Text("Insights", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(36.dp))
            }
            Spacer(Modifier.padding(top = 16.dp))
            SegmentedTabs(tab) { tab = it }
            Spacer(Modifier.padding(top = 8.dp))
        }

        if (!state.usageAccess) {
            item { UsageAccessCard(context); Spacer(Modifier.padding(top = 8.dp)) }
        }

        // Big number + chart
        item {
            val minutes = when (tab) {
                0 -> state.screenMinutes
                1 -> state.weekMinutes
                else -> state.monthAvg
            }
            Spacer(Modifier.padding(top = 8.dp))
            Text(InsightsViewModel.fmt(minutes), fontSize = 52.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Text(if (tab == 2) "30-DAY AVERAGE" else "SCREEN TIME",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.padding(top = 20.dp))
            when (tab) {
                0 -> BarChart(values = state.hourly, maxMinutes = 60,
                    bottomLabels = listOf("12a", "6a", "12p", "6p", "11p"),
                    yLabels = listOf("1h", "30m", "0s"),
                    readoutLabel = { hourLabel(it) })
                1 -> {
                    val cap = chartCap(state.weekly)
                    BarChart(values = state.weekly, maxMinutes = cap,
                        bottomLabels = (0..6).map { weekdayLabel(daysAgo = 6 - it, short = false) },
                        yLabels = listOf("${cap / 60}h", "${cap / 120}h", "0s"),
                        readoutLabel = { weekdayLabel(daysAgo = 6 - it, short = true) })
                }
                else -> {
                    val cap = chartCap(state.monthly)
                    val n = state.monthly.size
                    BarChart(values = state.monthly, maxMinutes = cap,
                        bottomLabels = listOf("30d", "20d", "10d", "today"),
                        yLabels = listOf("${cap / 60}h", "${cap / 120}h", "0s"),
                        readoutLabel = { dateLabel(n - 1 - it) })
                    Spacer(Modifier.padding(top = 10.dp))
                    WeekOverWeek(state.thisWeekMin, state.lastWeekMin)
                }
            }
            if (tab != 2 && state.categories.isNotEmpty()) {
                Spacer(Modifier.padding(top = 20.dp))
                CategoryBreakdown(state.categories)
            }
            Spacer(Modifier.padding(top = 24.dp))
        }

        // Patterns: weekday vs weekend
        if (state.usageAccess && (state.weekdayAvg > 0 || state.weekendAvg > 0)) {
            item { PatternsCard(state); Spacer(Modifier.padding(top = 24.dp)) }
        }

        // Trending apps (week over week)
        if (state.appTrends.isNotEmpty()) {
            item {
                Text("Trending this week", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Text("How each app changed vs last week.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.padding(top = 8.dp))
            }
            items(state.appTrends) { row ->
                StatListRow(row, onClick = row.pkg?.let { p -> { vm.selectApp(p) } })
            }
            item { Spacer(Modifier.padding(top = 24.dp)) }
        }

        // Summary statistics (derived from today + the last-7-days array)
        if (state.usageAccess && state.weekly.any { it > 0 }) {
            item {
                SummaryStats(state)
                Spacer(Modifier.padding(top = 24.dp))
            }
        }

        // Most used apps
        item {
            Text("Most used apps", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.padding(top = 8.dp))
        }
        if (state.topApps.isEmpty()) {
            item {
                Text(if (state.usageAccess) "No usage recorded yet." else "Needs Usage Access.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            items(state.topApps) { row ->
                StatListRow(row, onClick = row.pkg?.let { p -> { vm.selectApp(p) } })
            }
        }

        // Most opened apps (launch counts)
        if (state.topOpens.isNotEmpty()) {
            item {
                Spacer(Modifier.padding(top = 20.dp))
                Text("Most opened apps", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                Text("${state.totalOpens} app opens today · tap an app for details",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.padding(top = 8.dp))
            }
            items(state.topOpens) { row ->
                StatListRow(row, onClick = row.pkg?.let { p -> { vm.selectApp(p) } })
            }
        }

        // Blocked-app attempts
        item {
            Spacer(Modifier.padding(top = 20.dp))
            Text("Times you opened blocked apps", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.padding(top = 8.dp))
        }
        if (state.attempts.isEmpty()) {
            item {
                Text("No blocks yet today.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            items(state.attempts) { row ->
                StatListRow(row, onClick = row.pkg?.let { p -> { vm.selectApp(p) } })
            }
        }
        item { Spacer(Modifier.padding(top = 24.dp)) }
    }

    val detail by vm.detail.collectAsState()
    detail?.let { AppDetailSheet(it, onDismiss = vm::clearDetail) }
}

@Composable
private fun SegmentedTabs(selected: Int, onSelect: (Int) -> Unit) {
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
private fun BarChart(
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
                val barH = h * frac
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

/** Extra at-a-glance stats: daily average, busiest day, vs-yesterday change, usage rating. */
@Composable
private fun SummaryStats(state: InsightsState) {
    val today = state.screenMinutes
    val yesterday = state.weekly.getOrElse(5) { 0 }
    val avg = if (state.weekly.isNotEmpty()) state.weekly.sum() / state.weekly.size else 0
    val busiestIdx = state.weekly.indices.maxByOrNull { state.weekly[it] } ?: 6
    val rating = rateUsage(today)

    Text("Summary", style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
    Spacer(Modifier.padding(top = 8.dp))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface).padding(horizontal = 18.dp, vertical = 6.dp),
    ) {
        SummaryRow("Daily average (7 days)") {
            Text(InsightsViewModel.fmt(avg), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
        SummaryRow("Busiest day") {
            Text(weekdayLabel(daysAgo = 6 - busiestIdx, short = true),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
        SummaryRow("Phone unlocks today") {
            Text("${state.unlocksToday}", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
        SummaryRow("Compared to yesterday") {
            if (yesterday > 0) {
                val pct = ((today - yesterday) * 100f / yesterday).roundToInt()
                val up = pct >= 0
                // More screen time than yesterday = red (worse); less = green (better).
                val color = if (up) Color(0xFFEF4444) else Color(0xFF22C55E)
                Text("${if (up) "▲" else "▼"} ${abs(pct)}%",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = color)
            } else {
                Text("—", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SummaryRow("Today's usage") {
            Box(Modifier.clip(RoundedCornerShape(50)).background(rating.second.copy(alpha = 0.18f))
                .padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text(rating.first, style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold, color = rating.second)
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        value()
    }
}

/** A light/moderate/heavy rating of today's screen time, with a colour. */
private fun rateUsage(totalMinutes: Int): Pair<String, Color> = when {
    totalMinutes < 90 -> "Light" to Color(0xFF22C55E)
    totalMinutes < 210 -> "Moderate" to Color(0xFF3B82F6)
    totalMinutes < 360 -> "Heavy" to Color(0xFFF59E0B)
    else -> "Very heavy" to Color(0xFFEF4444)
}

/** Weekday-vs-weekend averages, shown on the Patterns card. */
@Composable
private fun PatternsCard(state: InsightsState) {
    Text("Patterns", style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
    Spacer(Modifier.padding(top = 8.dp))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface).padding(horizontal = 18.dp, vertical = 6.dp),
    ) {
        SummaryRow("Weekday average") {
            Text(InsightsViewModel.fmt(state.weekdayAvg), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
        SummaryRow("Weekend average") {
            Text(InsightsViewModel.fmt(state.weekendAvg), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
    }
    val takeaway = when {
        state.weekendAvg > state.weekdayAvg * 1.2 -> "You use your phone more on weekends."
        state.weekdayAvg > state.weekendAvg * 1.2 -> "You use your phone more on weekdays."
        else -> "Your use is fairly even across the week."
    }
    Spacer(Modifier.padding(top = 8.dp))
    Text(takeaway, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** This-week vs last-week total, shown under the Trend chart. */
@Composable
private fun WeekOverWeek(thisWeek: Int, lastWeek: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically) {
        Text("This week ${InsightsViewModel.fmt(thisWeek)}",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (lastWeek > 0) {
            val pct = ((thisWeek - lastWeek) * 100f / lastWeek).roundToInt()
            val up = pct >= 0
            val color = if (up) Color(0xFFEF4444) else Color(0xFF22C55E)
            Spacer(Modifier.width(8.dp))
            Text("${if (up) "▲" else "▼"} ${abs(pct)}% vs last week",
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = color)
        }
    }
}

/** Round a value array's max up to a whole-hour cap (min 1h) for the chart's y-axis. */
private fun chartCap(values: IntArray): Int {
    val maxV = (values.maxOrNull() ?: 0).coerceAtLeast(60)
    return (ceil(maxV / 60.0) * 60).toInt()
}

/** A date [daysAgo] days before today as "MMM d", e.g. "Jun 5". */
private fun dateLabel(daysAgo: Int): String {
    val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -daysAgo) }
    return SimpleDateFormat("MMM d", Locale.getDefault()).format(cal.time)
}

/** A 0–23 hour as a 12-hour clock label, e.g. 0 -> "12 AM", 19 -> "7 PM". */
private fun hourLabel(h: Int): String {
    val period = if (h < 12) "AM" else "PM"
    val h12 = if (h % 12 == 0) 12 else h % 12
    return "$h12 $period"
}

/** Weekday label for the day [daysAgo] days before today. [short]=true gives "Mon"/"Today"
 *  for the readout; [short]=false gives the one-letter axis label. */
private fun weekdayLabel(daysAgo: Int, short: Boolean): String {
    if (daysAgo == 0 && short) return "Today"
    val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -daysAgo) }
    val pattern = if (short) "EEE" else "EEEEE"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(cal.time)
}

@Composable
private fun CategoryBreakdown(cats: List<CatSlice>) {
    Row(
        Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(50)),
    ) {
        cats.forEach { c ->
            Box(Modifier.weight(c.minutes.toFloat().coerceAtLeast(0.01f)).fillMaxHeight().background(c.color))
        }
    }
    Spacer(Modifier.padding(top = 12.dp))
    // legend: wrap into rows of up to 3
    cats.chunked(3).forEach { rowCats ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            rowCats.forEach { c ->
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(c.color))
                    Spacer(Modifier.width(6.dp))
                    Text("${c.label} ${InsightsViewModel.fmt(c.minutes)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            repeat(3 - rowCats.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

private fun openUsageAccessSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

@Composable
private fun UsageAccessCard(context: Context) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface).padding(20.dp),
    ) {
        Text("Turn on Usage Access", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Text("Insights needs Usage Access to show your screen time and most-used apps.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { openUsageAccessSettings(context) }) { Text("Grant access") }
    }
}

@Composable
private fun StatListRow(row: StatRow, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.icon != null) {
            Image(row.icon.asImageBitmap(), null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
        } else {
            Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Block, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        if (row.dotColor != null) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(row.dotColor))
            Spacer(Modifier.width(8.dp))
        }
        Text(row.label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.bodyLarge, maxLines = 1)
        Text(row.value, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
    }
}

/** Bottom sheet showing one app's screen time, opens and block attempts together. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppDetailSheet(detail: AppDetail, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (detail.icon != null) {
                    Image(detail.icon.asImageBitmap(), null,
                        Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
                } else {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Apps, null, tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(detail.label, style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(detail.categoryColor))
                        Spacer(Modifier.width(6.dp))
                        Text(detail.categoryLabel, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.padding(top = 16.dp))
            DetailStat("Screen time today", InsightsViewModel.fmt(detail.minutes))
            DetailStat("Opens today", "${detail.opens}")
            if (detail.attemptsToday > 0 || detail.attemptsTotal > 0) {
                DetailStat("Opened while blocked", "${detail.attemptsToday}× today · ${detail.attemptsTotal}× total")
            }
        }
    }
}

@Composable
private fun DetailStat(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface)
    }
}
