package com.appblocker.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appblocker.data.moodLabel
import com.appblocker.ui.theme.AppGradients
import com.appblocker.ui.theme.softGlow
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The cards that make up the Insights tab — coach, balance, peak time, rollup, mood, summary,
 * patterns and the app-detail sheet — plus the shared card/row furniture they are built from.
 * Split out of InsightsScreen.kt, which had grown past 1200 lines; the screen itself and its
 * hero stayed there.
 */

@Composable
internal fun CoachCard(
    state: CoachState,
    goals: List<String>,
    onNewTips: () -> Unit,
    onChat: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(AppGradients.accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Color.White,
                modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text("AI Coach", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text("Personalized tips from your data · Gemini",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.padding(top = 8.dp))
    Column(
        Modifier.fillMaxWidth()
            .softGlow(RoundedCornerShape(20.dp), elevation = 10.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.5.dp, AppGradients.accent, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        when (state) {
            CoachState.Loading -> {
                Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Analyzing your day…", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                }
            }
            is CoachState.Tips -> {
                Spacer(Modifier.height(4.dp))
                state.tips.forEachIndexed { i, tip ->
                    if (i > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    }
                    Row(Modifier.padding(vertical = 12.dp)) {
                        Box(
                            Modifier.padding(top = 1.dp).size(22.dp).clip(CircleShape)
                                .background(AppGradients.accent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Lightbulb, contentDescription = null,
                                tint = Color.White, modifier = Modifier.size(13.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(tip, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (goals.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Flag, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (goals.size == 1) "Goal: ${goals[0]}"
                            else "Goals: ${goals.joinToString(" · ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                GradientButton("Chat with coach", onClick = onChat,
                    modifier = Modifier.padding(top = 12.dp))
                Row {
                    TextButton(onClick = onNewTips) { Text("New tips") }
                }
            }
            CoachState.Unavailable -> {
                Text(
                    "Couldn't reach Gemini — tips will return when you're online.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 12.dp),
                )
                GradientButton("Chat with coach", onClick = onChat,
                    modifier = Modifier.padding(top = 12.dp))
                Row {
                    TextButton(onClick = onNewTips) { Text("Try again") }
                }
            }
        }
    }
}


internal const val AWAKE_MIN = 16 * 60 // a 16-hour waking day, the denominator for "% of awake time"

/** Balance: screen time as a share of a 16h waking day (Day = today, Week/Trend = daily average). */


@Composable
internal fun BalanceCard(tab: Int, state: InsightsState) {
    val minutes = when (tab) {
        0 -> state.screenMinutes
        1 -> state.weekMinutes / 7
        else -> state.monthAvg
    }
    val pct = (minutes * 100 / AWAKE_MIN).coerceIn(0, 100)
    SectionCard("Balance", icon = Icons.Filled.Balance) {
        Spacer(Modifier.padding(top = 4.dp))
        Text("$pct % of awake time", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.padding(top = 10.dp))
        Box(
            Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier.fillMaxWidth((minutes.toFloat() / AWAKE_MIN).coerceIn(0f, 1f))
                    .fillMaxHeight().clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Spacer(Modifier.padding(top = 12.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Smartphone, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(InsightsViewModel.fmt(minutes), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Filled.WbSunny, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("16h", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Peak time: the busiest hour of today, shown as a labelled range like "1 PM – 2 PM". */


@Composable
internal fun PeakTimeCard(state: InsightsState) {
    val peakIdx = state.hourly.indices.maxByOrNull { state.hourly[it] } ?: return
    SectionCard("Peak time", icon = Icons.Filled.Schedule) {
        Spacer(Modifier.padding(top = 4.dp))
        Text("${hourLabel(peakIdx)} – ${hourLabel((peakIdx + 1) % 24)}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text("${InsightsViewModel.fmt(state.hourly[peakIdx])} of screen time",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
    }
}

/** Usage: today's screen time split into Productive / Distracting / Neutral, derived from the
 *  app categories (labels come from AppCategory.label). */


@Composable
internal fun UsageRollupCard(state: InsightsState) {
    var productive = 0; var distracting = 0; var neutral = 0
    state.categories.forEach { c ->
        when (c.label) {
            "Productive" -> productive += c.minutes
            "Social", "Video", "Games" -> distracting += c.minutes
            else -> neutral += c.minutes
        }
    }
    val total = productive + distracting + neutral
    if (total <= 0) return
    fun pct(m: Int) = (m * 100f / total).roundToInt()
    SectionCard("Usage", icon = Icons.Filled.PieChart) {
        Spacer(Modifier.padding(top = 4.dp))
        UsageBucketRow("Productive", pct(productive), Color(0xFF22C55E))
        UsageBucketRow("Distracting", pct(distracting), Color(0xFF7C5CFF))
        UsageBucketRow("Neutral", pct(neutral), Color(0xFF3B82F6))
        Spacer(Modifier.padding(top = 4.dp))
    }
}


@Composable
internal fun UsageBucketRow(name: String, pct: Int, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(14.dp))
        Box(
            Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier.fillMaxWidth((pct / 100f).coerceIn(0f, 1f)).fillMaxHeight()
                    .clip(RoundedCornerShape(50)).background(color),
            )
        }
        Spacer(Modifier.width(14.dp))
        Text("$pct %", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, color = color)
    }
}

/** A square-ish stat tile (label + big value + icon) for the Focus / Distractions rows. */


@Composable
internal fun StatTile(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier
            .softGlow(RoundedCornerShape(20.dp), elevation = 4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp))
        Spacer(Modifier.padding(top = 10.dp))
        Text(label.uppercase(), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Mood check-in summary card: today's self-rating + note, tap to (re)check in. */


@Composable
internal fun MoodCard(state: InsightsState, onOpen: () -> Unit) {
    val (label, color) = moodLabel(state.moodRating)
    SectionCard("Mood check-in", icon = Icons.Filled.Mood) {
        Row(
            Modifier.fillMaxWidth().clickable { onOpen() }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (state.moodRating < 0) "How did today feel?" else label,
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = if (state.moodRating < 0) MaterialTheme.colorScheme.onSurface else color)
                Text(
                    when {
                        state.moodRating < 0 -> "Tap to check in."
                        state.moodNote.isBlank() -> "No note added…"
                        else -> state.moodNote
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                )
            }
            Icon(Icons.Filled.Edit, contentDescription = "Edit",
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

/** Bottom sheet: rate how phone use felt today on a gradient slider + optional note. */
@OptIn(ExperimentalMaterial3Api::class)


@Composable
internal fun MoodSheet(
    initialRating: Int,
    initialNote: String,
    onSave: (Int, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var rating by remember { mutableFloatStateOf(if (initialRating < 0) 50f else initialRating.toFloat()) }
    var note by remember { mutableStateOf(initialNote) }
    val (label, color) = moodLabel(rating.roundToInt())
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("How did your phone use feel today?", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.padding(top = 12.dp))
            Text(label, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = color)
            Spacer(Modifier.padding(top = 10.dp))
            // Gradient track (pink→violet→blue→green) behind a transparent-track slider.
            Box(contentAlignment = Alignment.CenterStart) {
                Box(
                    Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFFEC4899), Color(0xFF7C5CFF),
                                    Color(0xFF3B82F6), Color(0xFF22C55E))
                            )
                        ),
                )
                Slider(
                    value = rating, onValueChange = { rating = it }, valueRange = 0f..100f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.Transparent,
                        inactiveTrackColor = Color.Transparent,
                    ),
                )
            }
            Row(Modifier.fillMaxWidth()) {
                Text("Very distracted", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("In control", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.padding(top = 16.dp))
            Text("Add a short note (optional)", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.padding(top = 6.dp))
            OutlinedTextField(
                value = note, onValueChange = { note = it },
                placeholder = { Text("Your thoughts…") },
                modifier = Modifier.fillMaxWidth(), minLines = 2,
                shape = RoundedCornerShape(16.dp),
            )
            Spacer(Modifier.padding(top = 16.dp))
            GradientButton(text = "Save check-in",
                onClick = { onSave(rating.roundToInt(), note) })
        }
    }
}


internal fun openNotificationAccess(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

/** Extra at-a-glance stats: daily average, busiest day, vs-yesterday change, usage rating. */


@Composable
internal fun SummaryStats(state: InsightsState) {
    val today = state.screenMinutes
    val yesterday = state.weekly.getOrElse(5) { 0 }
    val avg = if (state.weekly.isNotEmpty()) state.weekly.sum() / state.weekly.size else 0
    val busiestIdx = state.weekly.indices.maxByOrNull { state.weekly[it] } ?: 6
    val rating = rateUsage(today)

    SectionCard("Summary", icon = Icons.Filled.Assessment) {
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
        // Bright row titles — the muted variant grey was hard to read on the dark surface.
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        value()
    }
}

/** A light/moderate/heavy rating of today's screen time, with a colour. */


internal fun rateUsage(totalMinutes: Int): Pair<String, Color> = when {
    totalMinutes < 90 -> "Light" to Color(0xFF22C55E)
    totalMinutes < 210 -> "Moderate" to Color(0xFF3B82F6)
    totalMinutes < 360 -> "Heavy" to Color(0xFFF59E0B)
    else -> "Very heavy" to Color(0xFFEF4444)
}

/** Weekday-vs-weekend averages, shown on the Patterns card. */


@Composable
internal fun PatternsCard(state: InsightsState) {
    SectionCard("Patterns", icon = Icons.Filled.CalendarMonth) {
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

/** Round a value array's max up to a whole-hour cap (min 1h) for the chart's y-axis. */


internal fun openUsageAccessSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}


@Composable
internal fun UsageAccessCard(context: Context) {
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

/** Section header (tinted icon tile + title) + its rows in one glowing card — the page's
 *  shared card language, matching the Blocking/Profile cards. */


@Composable
internal fun SectionCard(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    content: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Spacer(Modifier.padding(top = 8.dp))
    Column(
        Modifier.fillMaxWidth()
            .softGlow(RoundedCornerShape(20.dp), elevation = 4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                RoundedCornerShape(20.dp),
            )
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) { content() }
}

/** The rows of one section, divider-separated; app rows open the detail sheet. */


@Composable
internal fun StatRows(rows: List<StatRow>, vm: InsightsViewModel) {
    rows.forEachIndexed { i, row ->
        if (i > 0) InsetDivider()
        StatListRow(row, onClick = row.pkg?.let { p -> { vm.selectApp(p) } })
    }
}


@Composable
internal fun InsetDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
        modifier = Modifier.padding(start = 52.dp),
    )
}

/** Gradient hero card: big number, its comparison pill, and today's key counters. */


@Composable
internal fun StatListRow(row: StatRow, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(vertical = 10.dp),
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
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 1)
                Text(row.value, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
                row.delta?.let { d ->
                    val color = when (row.deltaGood) {
                        true -> Color(0xFF22C55E)
                        false -> Color(0xFFEF4444)
                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(d, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold, color = color)
                }
            }
            // Comparison bar: this row relative to the section's biggest row, in its
            // category colour — spot the dominant app without reading the numbers.
            row.fraction?.let { f ->
                Spacer(Modifier.height(6.dp))
                val tint = row.dotColor ?: MaterialTheme.colorScheme.primary
                val animated by animateFloatAsState(
                    targetValue = f.coerceIn(0.02f, 1f), animationSpec = tween(500), label = "bar")
                Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50))
                    .background(tint.copy(alpha = 0.15f))) {
                    Box(Modifier.fillMaxWidth(animated).fillMaxHeight()
                        .clip(RoundedCornerShape(50)).background(tint))
                }
            }
        }
    }
}

/** Bottom sheet showing one app's screen time, opens and block attempts together. */
@OptIn(ExperimentalMaterial3Api::class)


@Composable
internal fun AppDetailSheet(detail: AppDetail, onDismiss: () -> Unit) {
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
internal fun DetailStat(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface)
    }
}
