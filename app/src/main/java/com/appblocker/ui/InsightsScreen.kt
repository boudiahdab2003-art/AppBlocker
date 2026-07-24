package com.appblocker.ui

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblocker.ui.theme.AppGradients
import com.appblocker.ui.theme.softGlow
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun InsightsScreen(
    onOpenCoach: () -> Unit = {},
    onNewGoalSchedule: () -> Unit = {},
    vm: InsightsViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val coach by vm.coach.collectAsState()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) } // 0 Day, 1 Week, 2 Trend
    var showMood by remember { mutableStateOf(false) }

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

        // Balance: screen time as a share of a 16h waking day.
        if (state.usageAccess) {
            item {
                BalanceCard(tab, state)
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

        // Peak time (Day): the busiest hour of the day, as a labelled range.
        if (tab == 0 && state.usageAccess && (state.hourly.maxOrNull() ?: 0) > 0) {
            item {
                PeakTimeCard(state)
                Spacer(Modifier.padding(top = 24.dp))
            }
        }

        // Usage (Day): a Productive / Distracting / Neutral split of today's screen time.
        if (tab == 0 && state.categories.isNotEmpty()) {
            item {
                UsageRollupCard(state)
                Spacer(Modifier.padding(top = 24.dp))
            }
        }

        // AI Coach: Gemini's daily read of the numbers above.
        item {
            CoachCard(coach, state.goals.map { it.goal.label() },
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

        // Trend podiums: apps that lost / gained the most time vs last week.
        if (tab == 2 && state.biggestDrops.isNotEmpty()) {
            item {
                SectionCard("Top time-savers", "You spent less time here than last week.",
                    icon = Icons.AutoMirrored.Filled.TrendingDown) {
                    StatRows(state.biggestDrops, vm)
                }
                Spacer(Modifier.padding(top = 24.dp))
            }
        }
        if (tab == 2 && state.biggestIncreases.isNotEmpty()) {
            item {
                SectionCard("Top increase", "These took more of your time — worth a look.",
                    icon = Icons.AutoMirrored.Filled.TrendingUp) {
                    StatRows(state.biggestIncreases, vm)
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

        // Focus + Distractions tiles + Mood check-in close out the page (Day).
        if (tab == 0 && state.usageAccess) {
            item {
                Spacer(Modifier.padding(top = 24.dp))
                Text("Focus", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    StatTile("Longest focus", InsightsViewModel.fmt(state.longestFocusMin),
                        Icons.Filled.CenterFocusStrong, Modifier.weight(1f))
                    StatTile("Continuous use", InsightsViewModel.fmt(state.continuousUseMin),
                        Icons.Filled.Smartphone, Modifier.weight(1f))
                }
                Spacer(Modifier.padding(top = 24.dp))

                Text("Distractions", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    StatTile(
                        "Notifications",
                        if (state.notificationAccess) "${state.notificationsToday}×" else "—",
                        Icons.Filled.Notifications, Modifier.weight(1f),
                        onClick = if (state.notificationAccess) null else {
                            { openNotificationAccess(context) }
                        },
                    )
                    StatTile("Pickups", "${state.unlocksToday}×",
                        Icons.Filled.PhoneAndroid, Modifier.weight(1f))
                }
                Spacer(Modifier.padding(top = 24.dp))

                MoodCard(state) { showMood = true }
            }
        }
        item { Spacer(Modifier.padding(top = 24.dp)) }
    }

    val detail by vm.detail.collectAsState()
    detail?.let { AppDetailSheet(it, onDismiss = vm::clearDetail) }

    if (showMood) {
        MoodSheet(
            initialRating = state.moodRating,
            initialNote = state.moodNote,
            onSave = { rating, note -> vm.saveMood(rating, note); showMood = false },
            onDismiss = { showMood = false },
        )
    }

}

/** The AI Coach panel: Gemini-written tips from today's aggregate stats. Styled as the page's
 *  special card — gradient icon + gradient border — so the AI feature stands out. */


@Composable
internal fun InsightsHero(tab: Int, state: InsightsState) {
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
internal fun HeroChip(value: String, label: String, modifier: Modifier = Modifier) {
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
