package com.appblocker.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appblocker.data.CleanStreak
import com.appblocker.data.dayStampOf
import com.appblocker.data.todayStamp
import com.appblocker.ui.theme.AppCard
import com.appblocker.ui.theme.AppGradients
import com.appblocker.ui.theme.Radius
import com.appblocker.ui.theme.pageWidth
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Rendering-test handles. The hero and the reset control are the two things that must be
 *  reachable on a small screen at a large system font — see `CleanCounterTest`. */
const val COUNTER_HERO_TAG = "counter_hero"
const val COUNTER_RESET_TAG = "counter_reset"

/**
 * Profile ▸ Your counter: how long it has been since the last relapse, ticking.
 *
 * **The number is the whole screen, and it is the only place it appears.** The owner chose that:
 * no chip in the Profile header, no card on the home tab. He goes and looks at it when he wants
 * to; it does not come and find him on a bad day.
 *
 * **Seconds are deliberate.** A rounded "9 days" is the same string for twenty-four hours, which
 * makes it something you check once and stop checking. A second hand is proof the thing is
 * running right now, and at the start — hour one of a fresh count — it is the only unit that is
 * moving at all.
 *
 * **And at zero this screen must not be a scoreboard.** The day of a reset is exactly when
 * somebody opens it feeling worst, and "your longest run: 41 days" printed under a row of zeroes
 * is the app kicking them. Under a day, the record line is not drawn and the headline is a
 * sentence.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanCounterScreen(
    onBack: () -> Unit,
    /** Open the journal on a given day — the counter's "write about today". */
    onOpenJournal: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    // Bumped after every write so the reads below re-run; resumeTick covers changes made while
    // the screen sat in the background.
    var version by remember { mutableIntStateOf(0) }
    val resumeTick = resumeTick()

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // One tick a second, and it stops the moment the screen leaves — the same shape as the
    // off-switch guard's countdown in ProfileScreen.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }

    val startedAt = remember(version, resumeTick) { CleanStreak.startedAt(context) }
    val best = remember(version, resumeTick) { CleanStreak.bestMs(context) }
    val undoAt = remember(version, resumeTick) { CleanStreak.undoAt(context) }
    val running = startedAt > 0L
    val elapsed = CleanStreak.elapsed(startedAt, now)
    val undoable = CleanStreak.canUndo(undoAt, now)

    var confirmRelapse by remember { mutableStateOf(false) }
    /** Which moment the picker is currently choosing — null when it is closed. */
    var picking by remember { mutableStateOf<MomentPurpose?>(null) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = { EditorTopBar("Your counter", onBack) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .pageWidth().padding(horizontal = 16.dp),
        ) {
            if (running) {
                CounterHero(elapsed)
                Spacer(Modifier.height(14.dp))
                Text(
                    "Since ${fullMoment(startedAt)}.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Under a day, the record stays off the screen entirely. See the class note.
                if (elapsed >= DAY_MS && best > elapsed) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Your longest run so far was ${plainLength(best)}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // And the other side of it: past the old record, say so. This is the one piece of
                // praise on the screen and it is a fact rather than a score — no badge, no level,
                // nothing invented. Only once there is a real previous run to have passed.
                if (elapsed >= DAY_MS && best in 1 until elapsed) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "This is the longest you have ever gone.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (elapsed < DAY_MS) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "It is running again from here. That is the whole of what today has to be.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                NotStartedHero()
            }

            Spacer(Modifier.height(20.dp))

            if (!running) {
                GradientButton(
                    text = "Start the count",
                    onClick = { CleanStreak.setStart(context, System.currentTimeMillis()); version++ },
                )
                Spacer(Modifier.height(10.dp))
                TextButton(
                    onClick = { picking = MomentPurpose.START },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) { Text("I started earlier than now") }
            } else {
                GradientButton(
                    text = "Write about today",
                    onClick = { onOpenJournal(todayStamp()) },
                )
            }

            if (undoable) {
                Spacer(Modifier.height(16.dp))
                UndoCard(
                    onUndo = { CleanStreak.undo(context); version++ },
                )
            }

            if (running) {
                Spacer(Modifier.height(16.dp))
                AppCard(elevation = 4.dp) {
                    ActionRow(
                        icon = Icons.Filled.EditCalendar,
                        title = "Change the start time",
                        subtitle = "If the count started before you told the app. This records " +
                            "nothing — it just corrects the date it is counting from.",
                        onClick = { picking = MomentPurpose.START },
                    )
                    Spacer(Modifier.height(4.dp))
                    ActionRow(
                        icon = Icons.Filled.RestartAlt,
                        title = "It happened — start again",
                        subtitle = "Records today and starts the count from zero. You can take " +
                            "this back for ${CleanStreak.UNDO_WINDOW_LABEL} afterwards.",
                        destructive = true,
                        modifier = Modifier.testTag(COUNTER_RESET_TAG),
                        onClick = { confirmRelapse = true },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "This count, and everything you write in the journal, is stored on this phone " +
                    "only. It is never sent anywhere — not in a bug report, and not to the AI " +
                    "Coach.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))
        }
    }

    if (confirmRelapse) {
        RelapseDialog(
            elapsed = elapsed,
            onNow = {
                CleanStreak.relapse(context, System.currentTimeMillis())
                version++
                confirmRelapse = false
            },
            onEarlier = {
                confirmRelapse = false
                picking = MomentPurpose.RELAPSE
            },
            onDismiss = { confirmRelapse = false },
        )
    }

    picking?.let { purpose ->
        MomentPicker(
            title = if (purpose == MomentPurpose.START) "When did it start?" else "When did it happen?",
            initial = if (running) startedAt else System.currentTimeMillis(),
            onDismiss = { picking = null },
            onPicked = { chosen ->
                when (purpose) {
                    MomentPurpose.START -> CleanStreak.setStart(context, chosen)
                    MomentPurpose.RELAPSE -> CleanStreak.relapse(context, chosen)
                }
                version++
                picking = null
            },
        )
    }
}

private const val DAY_MS = 24 * 60 * 60_000L

/** What the date-and-time picker is currently being used for. */
private enum class MomentPurpose { START, RELAPSE }

/** The four units, side by side, on the accent gradient. */
@Composable
private fun CounterHero(elapsedMs: Long) {
    val e = CleanStreak.breakdown(elapsedMs)
    Box(
        Modifier.fillMaxWidth().testTag(COUNTER_HERO_TAG)
            .clip(RoundedCornerShape(Radius.hero)).background(AppGradients.accent)
            .padding(vertical = 22.dp, horizontal = 12.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(
                "You have held on for",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.9f),
            )
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                UnitBlock(e.days.toString(), if (e.days == 1L) "day" else "days", Modifier.weight(1f))
                UnitBlock("%02d".format(e.hours), "hours", Modifier.weight(1f))
                UnitBlock("%02d".format(e.minutes), "min", Modifier.weight(1f))
                UnitBlock("%02d".format(e.seconds), "sec", Modifier.weight(1f))
            }
        }
    }
}

/** One number-over-label block in the hero. */
@Composable
private fun UnitBlock(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(Radius.medium)).background(Color.White.copy(alpha = 0.16f))
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.85f),
            maxLines = 1,
        )
    }
}

@Composable
private fun NotStartedHero() {
    Box(
        Modifier.fillMaxWidth().testTag(COUNTER_HERO_TAG)
            .clip(RoundedCornerShape(Radius.hero)).background(AppGradients.accent).padding(22.dp),
    ) {
        Column {
            Text(
                "Not counting yet",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Start it and this screen shows exactly how long it has been — days, hours, " +
                    "minutes and seconds — from that moment on. If you have already been going a " +
                    "while, you can tell it when you really started.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f),
            )
        }
    }
}

/**
 * The way back from a mis-tap.
 *
 * Sits above the actions rather than inside them, because the person who needs it has just done
 * something they did not mean to do and should not have to go looking.
 */
@Composable
private fun UndoCard(onUndo: () -> Unit) {
    AppCard(elevation = 4.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Undo, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Reset by mistake?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Undo puts the count back exactly where it was. Available for " +
                        "${CleanStreak.UNDO_WINDOW_LABEL} after a reset.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onUndo) { Text("Undo") }
        }
    }
}

/** One tappable action inside the actions card. */
@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    val accent =
        if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.medium)).clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = accent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (destructive) accent else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The confirmation in front of a reset.
 *
 * It exists to stop a mis-tap, not to make a point. There is no "you are about to lose 12 days"
 * here and there never should be: the person reading it already knows, and the app's job at that
 * moment is to be matter-of-fact and get out of the way.
 */
@Composable
private fun RelapseDialog(
    elapsed: Long,
    onNow: () -> Unit,
    onEarlier: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start the count again?") },
        text = {
            Column {
                Text(
                    if (elapsed >= DAY_MS) {
                        "The ${plainLength(elapsed)} you have just done still happened. This " +
                            "records today and starts the clock from now."
                    } else {
                        "This records today and starts the clock from now."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "You can undo it for the next ${CleanStreak.UNDO_WINDOW_LABEL}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onEarlier, modifier = Modifier.padding(0.dp)) {
                    Text("It happened earlier — pick when")
                }
            }
        },
        confirmButton = { TextButton(onClick = onNow) { Text("Start again from now") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Pick a date, then a time on it.
 *
 * Two dialogs rather than one screen because Material has both and neither has to hold a text
 * field — the insets trap that keeps [ReportProblemSheet] and [FrictionGate] out of dialogs does
 * not apply to a picker with nothing to type into. The time step already ships this way in
 * `ScheduleEditorScreen`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MomentPicker(
    title: String,
    initial: Long,
    onPicked: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var chosenDate by remember { mutableStateOf<Long?>(null) }

    if (chosenDate == null) {
        val state = rememberDatePickerState(initialSelectedDateMillis = toPickerDate(initial))
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    enabled = state.selectedDateMillis != null,
                    onClick = { chosenDate = state.selectedDateMillis },
                ) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp),
            )
            DatePicker(state = state, title = null)
        }
    } else {
        val start = Calendar.getInstance().apply { timeInMillis = initial }
        val state = rememberTimePickerState(
            start.get(Calendar.HOUR_OF_DAY), start.get(Calendar.MINUTE), false,
        )
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("What time?") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(
                    onClick = { onPicked(combine(chosenDate!!, state.hour, state.minute)) },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
}

/**
 * **A Material date picker speaks UTC; everything else in this app speaks local time.** Both
 * directions of that conversion are here, and both were wrong in the obvious way first.
 *
 * Going *in*: a real instant handed straight to `initialSelectedDateMillis` is read as a UTC date,
 * so anywhere east of Greenwich — Germany is UTC+2 in summer — a start time of half past midnight
 * lands the picker on the previous day. Going *out*: the value it returns is UTC midnight, so
 * reading it with a local calendar puts anyone west of Greenwich a day early.
 *
 * Neither is visible on an emulator set to UTC, which is exactly why this is written down.
 */
internal fun toPickerDate(millis: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = millis }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

/** The picker's answer as a real local instant, at [hour]:[minute] on the date that was chosen. */
internal fun combine(utcDateMillis: Long, hour: Int, minute: Int): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcDateMillis }
    return Calendar.getInstance().apply {
        clear()
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), hour, minute)
    }.timeInMillis
}

/** The day-stamp of a date the Material picker returned. */
internal fun utcDateToDayStamp(utcDateMillis: Long): Int = dayStampOf(combine(utcDateMillis, 12, 0))

/** "Friday, 8 August 2026 at 9:14 PM" — the full, unambiguous version, used once per screen. */
private fun fullMoment(millis: Long): String =
    SimpleDateFormat("EEEE, d MMMM yyyy 'at' h:mm a", Locale.getDefault()).format(Date(millis))

/** A duration as a sentence would say it: "12 days", "1 day", "9 hours". Never a bare number. */
internal fun plainLength(ms: Long): String {
    val e = CleanStreak.breakdown(ms)
    return when {
        e.days > 0L -> if (e.days == 1L) "1 day" else "${e.days} days"
        e.hours > 0L -> if (e.hours == 1L) "1 hour" else "${e.hours} hours"
        e.minutes > 0L -> if (e.minutes == 1L) "1 minute" else "${e.minutes} minutes"
        else -> "less than a minute"
    }
}
