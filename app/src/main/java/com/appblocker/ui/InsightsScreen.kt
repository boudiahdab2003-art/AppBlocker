package com.appblocker.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblocker.data.Gamification
import com.appblocker.data.GamifyState
import com.appblocker.ui.theme.AppGradients
import com.appblocker.ui.theme.softGlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
fun InsightsScreen(
    onOpenCoach: () -> Unit = {},
    onOpenAchievements: () -> Unit = {},
    onNewGoalSchedule: () -> Unit = {},
    vm: InsightsViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val coach by vm.coach.collectAsState()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) } // 0 Day, 1 Week, 2 Trend
    var showKeyDialog by remember { mutableStateOf(false) }

    // Rebuild the stats every time the tab is opened, so they're always current.
    LaunchedEffect(Unit) { vm.refresh() }

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

        // Gradient hero: the headline number, its context, and today's key counters.
        item {
            Spacer(Modifier.padding(top = 8.dp))
            InsightsHero(tab, state)
            Spacer(Modifier.padding(top = 20.dp))
        }

        // Focus Score: today's live score, level/XP, streak, and the door to achievements.
        state.gamify?.let { g ->
            item {
                ScoreCard(g, onOpenAchievements)
                Spacer(Modifier.padding(top = 24.dp))
            }
        }

        // Goals: measurable daily targets, tracked live with hit/miss history.
        item {
            GoalsCard(
                goals = state.goals,
                onAddGoal = { vm.addGoal(it) },
                onRemoveGoal = { vm.removeGoal(it) },
                onEnforce = onNewGoalSchedule,
                onOpenCoach = onOpenCoach,
            )
            Spacer(Modifier.padding(top = 24.dp))
        }

        // Activity: the interactive chart + category split in one designed card.
        item {
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
                    .padding(16.dp),
            ) {
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
                    }
                }
                if (tab != 2 && state.categories.isNotEmpty()) {
                    Spacer(Modifier.padding(top = 16.dp))
                    CategoryBreakdown(state.categories)
                }
            }
            Spacer(Modifier.padding(top = 24.dp))
        }

        // AI Coach: Gemini's daily read of the numbers above.
        item {
            CoachCard(coach, state.goals.map { it.goal.label() },
                onEditKey = { showKeyDialog = true },
                onNewTips = { vm.newTips() }, onChat = onOpenCoach)
            Spacer(Modifier.padding(top = 24.dp))
        }

        // Patterns: weekday vs weekend
        if (state.usageAccess && (state.weekdayAvg > 0 || state.weekendAvg > 0)) {
            item { PatternsCard(state); Spacer(Modifier.padding(top = 24.dp)) }
        }

        // Trending apps (week over week)
        if (state.appTrends.isNotEmpty()) {
            item {
                SectionCard("Trending this week", "How each app changed vs last week.",
                    icon = Icons.AutoMirrored.Filled.TrendingUp) {
                    StatRows(state.appTrends, vm)
                }
                Spacer(Modifier.padding(top = 24.dp))
            }
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
            SectionCard("Most used apps", icon = Icons.Filled.BarChart) {
                if (state.topApps.isEmpty()) {
                    Text(if (state.usageAccess) "No usage recorded yet." else "Needs Usage Access.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp))
                } else {
                    StatRows(state.topApps, vm)
                }
            }
        }

        // Most opened apps (launch counts)
        if (state.topOpens.isNotEmpty()) {
            item {
                Spacer(Modifier.padding(top = 24.dp))
                SectionCard("Most opened apps",
                    "${state.totalOpens} app opens today · tap an app for details",
                    icon = Icons.Filled.TouchApp) {
                    StatRows(state.topOpens, vm)
                }
            }
        }

        // Blocked-app attempts
        item {
            Spacer(Modifier.padding(top = 24.dp))
            SectionCard("Times you opened blocked apps", icon = Icons.Filled.Block) {
                if (state.attempts.isEmpty()) {
                    Text("No blocks yet today.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp))
                } else {
                    // Headline: what AppBlocker caught, without reading every row.
                    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${state.attemptsTodayTotal}× today",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary)
                        Text(" · ${state.attemptsAllTotal}× all time",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    InsetDivider()
                    StatRows(state.attempts, vm)
                }
            }
        }
        item { Spacer(Modifier.padding(top = 24.dp)) }
    }

    val detail by vm.detail.collectAsState()
    detail?.let { AppDetailSheet(it, onDismiss = vm::clearDetail) }

    if (showKeyDialog) {
        var keyText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showKeyDialog = false },
            title = { Text("Gemini API key") },
            text = {
                Column {
                    Text(
                        "Paste your free key from aistudio.google.com/apikey. " +
                            "It's stored on this device only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = keyText,
                        onValueChange = { keyText = it.trim() },
                        label = { Text("API key") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = keyText.isNotBlank(),
                    onClick = { vm.setApiKey(keyText); showKeyDialog = false },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showKeyDialog = false }) { Text("Cancel") } },
        )
    }
}

/** Focus Score card: animated score ring, level + XP bar, streak, achievements door. */
@Composable
private fun ScoreCard(g: GamifyState, onOpenAchievements: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(AppGradients.accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = Color.White,
                modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text("Focus Score", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text("Your day, scored live from your habits",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.padding(top = 8.dp))
    Column(
        Modifier.fillMaxWidth()
            .softGlow(RoundedCornerShape(20.dp), elevation = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(g.score, g.band)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text("Level ${g.levelIndex + 1} · ${g.level.name}",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                // XP progress toward the next level.
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(g.levelProgress.coerceIn(0.03f, 1f))
                        .fillMaxHeight().clip(RoundedCornerShape(50))
                        .background(AppGradients.accent))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    g.nextLevel?.let { "${g.xp} / ${it.threshold} XP to ${it.name}" }
                        ?: "${g.xp} XP · max level",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.LocalFireDepartment, contentDescription = null,
                        tint = if (g.streak > 0) Color(0xFFFF9E45)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        when {
                            g.streak > 1 -> "${g.streak}-day streak"
                            g.streak == 1 -> "1 good day — keep it going"
                            else -> "Score 60+ to start a streak"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (g.streak > 0) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Celebrate anything unlocked on this refresh.
        g.newlyUnlocked.forEach { a ->
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.EmojiEvents, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Achievement unlocked: ${a.title}  (+${a.xp} XP)",
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        Row(
            Modifier.fillMaxWidth().clickable { onOpenAchievements() }.padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Achievements", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            Text("${g.unlocked.size}/${Gamification.ACHIEVEMENTS.size} unlocked",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The animated score dial: gradient arc over a faint track, score + band in the middle. */
@Composable
private fun ScoreRing(score: Int, band: String) {
    val sweep by animateFloatAsState(targetValue = score / 100f, animationSpec = tween(700),
        label = "score")
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            val inset = stroke / 2
            drawArc(
                color = track,
                startAngle = 135f, sweepAngle = 270f, useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
            drawArc(
                brush = AppGradients.accent,
                startAngle = 135f, sweepAngle = 270f * sweep, useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$score", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface)
            Text(band, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The AI Coach panel: Gemini-written tips from today's aggregate stats. Styled as the page's
 *  special card — gradient icon + gradient border — so the AI feature stands out. */
@Composable
private fun CoachCard(
    state: CoachState,
    goals: List<String>,
    onEditKey: () -> Unit,
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
            CoachState.NoKey -> {
                Text(
                    "Add your free Gemini API key to get daily coaching based on how you " +
                        "actually used your phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 12.dp),
                )
                TextButton(onClick = onEditKey) { Text("Add key") }
            }
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
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onEditKey) { Text("Change key") }
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
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onEditKey) { Text("Change key") }
                }
            }
        }
    }
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

/** Extra at-a-glance stats: daily average, busiest day, vs-yesterday change, usage rating. */
@Composable
private fun SummaryStats(state: InsightsState) {
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
private fun rateUsage(totalMinutes: Int): Pair<String, Color> = when {
    totalMinutes < 90 -> "Light" to Color(0xFF22C55E)
    totalMinutes < 210 -> "Moderate" to Color(0xFF3B82F6)
    totalMinutes < 360 -> "Heavy" to Color(0xFFF59E0B)
    else -> "Very heavy" to Color(0xFFEF4444)
}

/** Weekday-vs-weekend averages, shown on the Patterns card. */
@Composable
private fun PatternsCard(state: InsightsState) {
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
    // legend: tinted pill chips in the category colours, wrapping as needed
    legendPills(cats)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun legendPills(cats: List<CatSlice>) {
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

/** Section header (tinted icon tile + title) + its rows in one glowing card — the page's
 *  shared card language, matching the Blocking/Profile cards. */
@Composable
private fun SectionCard(
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
private fun StatRows(rows: List<StatRow>, vm: InsightsViewModel) {
    rows.forEachIndexed { i, row ->
        if (i > 0) InsetDivider()
        StatListRow(row, onClick = row.pkg?.let { p -> { vm.selectApp(p) } })
    }
}

@Composable
private fun InsetDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
        modifier = Modifier.padding(start = 52.dp),
    )
}

/** Gradient hero card: big number, its comparison pill, and today's key counters. */
@Composable
private fun InsightsHero(tab: Int, state: InsightsState) {
    val minutes = when (tab) {
        0 -> state.screenMinutes
        1 -> state.weekMinutes
        else -> state.monthAvg
    }
    // Comparison baseline per tab: Day vs the 7-day average, Week/Trend vs last week.
    val (baseline, baselineLabel) = when (tab) {
        0 -> (if (state.weekly.isNotEmpty()) state.weekly.sum() / state.weekly.size else 0) to
            "vs your 7-day average"
        else -> state.lastWeekMin to "vs last week"
    }
    val compared = if (tab == 0) state.screenMinutes else state.thisWeekMin
    Column(
        Modifier.fillMaxWidth()
            .softGlow(RoundedCornerShape(24.dp), elevation = 12.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(AppGradients.accent)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(InsightsViewModel.fmt(minutes), fontSize = 52.sp, fontWeight = FontWeight.Bold,
            color = Color.White)
        Text(if (tab == 2) "30-DAY AVERAGE" else "SCREEN TIME",
            style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.8f))
        if (baseline > 0) {
            val pct = ((compared - baseline) * 100f / baseline).roundToInt()
            val up = pct >= 0
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.18f))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (up) "▲" else "▼", style = MaterialTheme.typography.labelLarge,
                    color = if (up) Color(0xFFFFB4AB) else Color(0xFF7BE8A8))
                Spacer(Modifier.width(5.dp))
                Text("${abs(pct)}% $baselineLabel", style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeroChip("${state.unlocksToday}", "unlocks today", Modifier.weight(1f))
            HeroChip("${state.attemptsTodayTotal}", "blocks today", Modifier.weight(1f))
            HeroChip(InsightsViewModel.fmt(state.strictMinutes), "strict time", Modifier.weight(1f))
        }
    }
}

/** One translucent number chip in the hero (same language as the Profile header). */
@Composable
private fun HeroChip(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.14f))
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            color = Color.White)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.85f), maxLines = 1)
    }
}

@Composable
private fun StatListRow(row: StatRow, onClick: (() -> Unit)? = null) {
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
            fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface)
    }
}
