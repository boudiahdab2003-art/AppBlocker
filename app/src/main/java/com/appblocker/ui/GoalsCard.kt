package com.appblocker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appblocker.data.Goal
import com.appblocker.data.GoalKind
import com.appblocker.data.GoalProgress
import com.appblocker.data.Goals
import com.appblocker.data.InstalledAppsRepository
import com.appblocker.ui.theme.AppGradients
import com.appblocker.ui.theme.softGlow

private val HitGreen = Color(0xFF22C55E)
private val MissRed = Color(0xFFEF4444)

/** Real goals: live progress bars against today's usage, 7-day hit dots, streaks, and a
 *  one-tap path to enforce a goal with a real Usage-limit schedule. */
@Composable
fun GoalsCard(
    goals: List<GoalProgress>,
    onAddGoal: (Goal) -> Unit,
    onRemoveGoal: (Goal) -> Unit,
    onEnforce: () -> Unit,
    onOpenCoach: () -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<Goal?>(null) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(AppGradients.accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Flag, contentDescription = null, tint = Color.White,
                modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Goals", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text("Daily targets the app tracks for you",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { showAdd = true }) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("New goal")
        }
    }
    Spacer(Modifier.padding(top = 8.dp))
    Column(
        Modifier.fillMaxWidth()
            .softGlow(RoundedCornerShape(20.dp), elevation = 4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        if (goals.isEmpty()) {
            Text(
                "No goals yet. Set one here, or agree on one with your coach — the app " +
                    "will track it against your real usage every day.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row {
                TextButton(onClick = { showAdd = true }) { Text("Set a goal") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onOpenCoach) { Text("Ask the coach") }
            }
        } else {
            goals.forEachIndexed { i, gp ->
                if (i > 0) {
                    Spacer(Modifier.height(12.dp))
                }
                GoalRow(gp, onRemove = { confirmRemove = gp.goal }, onEnforce = onEnforce)
            }
        }
    }

    if (showAdd) {
        AddGoalDialog(onAdd = { onAddGoal(it); showAdd = false },
            onDismiss = { showAdd = false })
    }
    confirmRemove?.let { goal ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove goal?") },
            text = { Text("\"${goal.label()}\" and its history will be removed.") },
            confirmButton = {
                TextButton(onClick = { onRemoveGoal(goal); confirmRemove = null }) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun GoalRow(gp: GoalProgress, onRemove: () -> Unit, onEnforce: () -> Unit) {
    val g = gp.goal
    val under = gp.usedToday < g.target
    val barColor = if (under) HitGreen else MissRed
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(g.label(), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f))
            if (gp.streak > 0) {
                Icon(Icons.Filled.LocalFireDepartment, contentDescription = null,
                    tint = Color(0xFFFF9E45), modifier = Modifier.size(14.dp))
                Text(" ${gp.streak}", style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(8.dp))
            }
            Icon(Icons.Filled.Close, contentDescription = "Remove goal",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp).clip(CircleShape).clickable { onRemove() })
        }
        Spacer(Modifier.height(8.dp))
        // Today's live bar: green while you're under the target, red once you're past it.
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier
                .fillMaxWidth((gp.usedToday.toFloat() / g.target).coerceIn(0.02f, 1f))
                .fillMaxHeight().clip(RoundedCornerShape(50)).background(barColor))
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (g.kind) {
                    GoalKind.UNLOCKS -> "${gp.usedToday} of ${g.target} unlocks today"
                    else -> "${InsightsViewModel.fmt(gp.usedToday)} of " +
                        "${InsightsViewModel.fmt(g.target)} today"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (under) MaterialTheme.colorScheme.onSurfaceVariant else MissRed,
                fontWeight = if (under) FontWeight.Normal else FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            // The last 7 finished days: green = hit, red = miss, dim = no data yet.
            gp.hits7.forEach { hit ->
                Box(Modifier.padding(start = 4.dp).size(8.dp).clip(CircleShape)
                    .background(when (hit) {
                        true -> HitGreen
                        false -> MissRed
                        null -> MaterialTheme.colorScheme.surfaceVariant
                    }))
            }
        }
        if (g.kind != GoalKind.UNLOCKS) {
            Row {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onEnforce, contentPadding =
                    androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                    Text("Enforce with a schedule",
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** Create a goal by hand: pick what to measure, set the target with steppers. */
@Composable
private fun AddGoalDialog(onAdd: (Goal) -> Unit, onDismiss: () -> Unit) {
    var kind by remember { mutableStateOf(GoalKind.SCREEN_TIME) }
    var hours by remember { mutableIntStateOf(3) }
    var minutes by remember { mutableIntStateOf(0) }
    var unlocks by remember { mutableIntStateOf(40) }
    var pickedPkg by remember { mutableStateOf<String?>(null) }
    var pickedLabel by remember { mutableStateOf<String?>(null) }

    // Most-used apps first — the launch-warmed cache + usage map, no fresh system queries.
    val topApps = remember {
        val usage = InstalledAppsRepository.usage.value
        InstalledAppsRepository.apps.value
            .sortedByDescending { usage[it.packageName] ?: 0 }
            .take(12)
    }

    val valid = when (kind) {
        GoalKind.APP_LIMIT -> pickedPkg != null && (hours * 60 + minutes) > 0
        GoalKind.UNLOCKS -> unlocks > 0
        else -> (hours * 60 + minutes) > 0
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New goal") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KindChip("Screen time", kind == GoalKind.SCREEN_TIME) {
                        kind = GoalKind.SCREEN_TIME
                    }
                    KindChip("One app", kind == GoalKind.APP_LIMIT) { kind = GoalKind.APP_LIMIT }
                    KindChip("Unlocks", kind == GoalKind.UNLOCKS) { kind = GoalKind.UNLOCKS }
                }
                Spacer(Modifier.height(14.dp))
                when (kind) {
                    GoalKind.UNLOCKS -> {
                        Text("Stay under this many unlocks a day:",
                            style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Stepper(unlocks, "unlocks", min = 5, step = 5) { unlocks = it }
                    }
                    else -> {
                        if (kind == GoalKind.APP_LIMIT) {
                            Text("Which app?", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(6.dp))
                            Column(Modifier.height(150.dp).verticalScroll(rememberScrollState())) {
                                topApps.forEach { app ->
                                    val on = app.packageName == pickedPkg
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                            .background(
                                                if (on) MaterialTheme.colorScheme.primary
                                                    .copy(alpha = 0.18f)
                                                else Color.Transparent)
                                            .clickable {
                                                pickedPkg = app.packageName
                                                pickedLabel = app.label
                                            }
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                    ) {
                                        Text(app.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (on) FontWeight.Bold
                                            else FontWeight.Normal,
                                            color = if (on) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        Text("Stay under this much per day:",
                            style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Stepper(hours, "h", min = 0, step = 1) { hours = it }
                            Stepper(minutes, "m", min = 0, max = 55, step = 5) { minutes = it }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val goal = when (kind) {
                    GoalKind.SCREEN_TIME ->
                        Goal(Goals.newId(), kind, hours * 60 + minutes)
                    GoalKind.APP_LIMIT ->
                        Goal(Goals.newId(), kind, hours * 60 + minutes, pickedPkg, pickedLabel)
                    GoalKind.UNLOCKS -> Goal(Goals.newId(), kind, unlocks)
                }
                onAdd(goal)
            }) { Text("Add goal") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun KindChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .then(
                if (selected) Modifier.background(AppGradients.accent)
                else Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Stepper(value: Int, unit: String, min: Int, max: Int = 999, step: Int,
                    onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepBtn("−") { onChange((value - step).coerceAtLeast(min)) }
        Text("$value $unit", style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 10.dp))
        StepBtn("+") { onChange((value + step).coerceAtMost(max)) }
    }
}

@Composable
private fun StepBtn(sign: String, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(sign, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface)
    }
}
