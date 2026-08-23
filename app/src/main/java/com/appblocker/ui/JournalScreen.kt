package com.appblocker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblocker.data.CleanStreak
import com.appblocker.R
import com.appblocker.data.JournalEntry
import com.appblocker.data.dayStartMillis
import com.appblocker.data.prevDayStamp
import com.appblocker.data.todayStamp
import com.appblocker.ui.theme.AppGradients
import com.appblocker.ui.theme.Radius
import com.appblocker.ui.theme.pageWidth
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Rendering-test handles: the writing area and the control that leaves it. Both have to survive
 *  an open keyboard at a large system font — see `JournalEntryTest`. */
const val JOURNAL_FIELD_TAG = "journal_field"
const val JOURNAL_DONE_TAG = "journal_done"

/** The smallest the writing area is ever allowed to become — roughly eight lines. Squeeze the
 *  page below that and it scrolls; the field does not shrink to a slot. */
val JOURNAL_FIELD_FLOOR = 200.dp

/**
 * Profile ▸ Journal: one entry per calendar day, kept forever.
 *
 * **The date is the identity, not a label.** Every entry belongs to a day and reopening that day
 * always finds it, which is what makes this a diary rather than a notes field — and it is why any
 * past day can be written about too, not only today.
 *
 * Index → entry lives inside this one overlay, using the same `AnimatedContent` + local
 * `BackHandler` shape as [InstructionsScreen], so back walks entry → index → Profile.
 *
 * **The writing is never uploaded.** Not in a bug report, not in the profile report, not in an AI
 * Coach prompt. If a PIN is set it is already behind it — [PinScreen] gates the whole app, and a
 * second lock here would only be a second thing to forget.
 */
@Composable
fun JournalScreen(
    onBack: () -> Unit,
    /** Open straight onto a day — the counter's "Write about today" arrives this way. */
    startDay: Int? = null,
    vm: JournalViewModel = viewModel(),
) {
    var openDay by rememberSaveable { mutableStateOf(startDay) }
    val entries by vm.entries.collectAsState()

    BackHandler(enabled = openDay != null) { openDay = null }

    AnimatedContent(
        targetState = openDay,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally { it / 4 } + fadeIn()) togetherWith fadeOut()
            } else {
                fadeIn() togetherWith (slideOutHorizontally { it / 4 } + fadeOut())
            }
        },
        label = "journal",
    ) { day ->
        if (day == null) {
            JournalIndex(
                entries = entries,
                onBack = onBack,
                onOpen = { openDay = it },
            )
        } else {
            JournalEntryPage(
                day = day,
                vm = vm,
                onBack = { openDay = null },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────── the index

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JournalIndex(
    entries: List<JournalEntry>,
    onBack: () -> Unit,
    onOpen: (Int) -> Unit,
) {
    val context = LocalContext.current
    val today = todayStamp()
    val todayEntry = entries.firstOrNull { it.day == today }
    val past = entries.filter { it.day != today }
    // Days a relapse was recorded on, so the list can mark them. Read once — the set is small and
    // the screen is rebuilt whenever it is reopened.
    val resetDays = remember { CleanStreak.resetDays(context) }
    var pickingDay by remember { mutableStateOf(false) }

    // No Scaffold here, so pad the system bars ourselves (edge-to-edge is forced on Android 15+).
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        EditorTopBar(
            title = stringResource(R.string.journal_title),
            onBack = onBack,
            actions = {
                IconButton(onClick = { pickingDay = true }) {
                    Icon(
                        Icons.Filled.CalendarMonth,
                        contentDescription = stringResource(R.string.journal_another_day),
                    )
                }
            },
        )
        LazyColumn(Modifier.fillMaxSize().pageWidth().padding(horizontal = 20.dp)) {
            item {
                Text(
                    stringResource(R.string.journal_index_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    stringResource(R.string.journal_index_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
                )
                TodayCard(entry = todayEntry, onClick = { onOpen(today) })
                Spacer(Modifier.height(20.dp))
            }
            if (past.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.journal_earlier),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                    )
                }
                items(past, key = { it.day }) { entry ->
                    EntryRow(
                        entry = entry,
                        wasReset = entry.day in resetDays,
                        onClick = { onOpen(entry.day) },
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
            item {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.medium))
                        .clickable { pickingDay = true }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.CalendarMonth, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.journal_another_day),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }

    if (pickingDay) {
        // Local midnight is not what the picker wants — see toPickerDate.
        val state = rememberDatePickerState(
            initialSelectedDateMillis = toPickerDate(dayStartMillis(today)),
        )
        DatePickerDialog(
            onDismissRequest = { pickingDay = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedDateMillis != null,
                    onClick = {
                        state.selectedDateMillis?.let { onOpen(utcDateToDayStamp(it)) }
                        pickingDay = false
                    },
                ) { Text(stringResource(R.string.common_open)) }
            },
            dismissButton = {
                TextButton(onClick = { pickingDay = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

/** The top card: today, whether or not anything is on it yet. */
@Composable
private fun TodayCard(entry: JournalEntry?, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.hero))
            .background(AppGradients.accent).clickable(onClick = onClick).padding(20.dp),
    ) {
        Column {
            Text(
                stringResource(R.string.journal_today, dayTitle(todayStamp())),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                entry?.text?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.journal_empty_today),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                maxLines = 4,
            )
        }
    }
}

/** One past day in the list. */
@Composable
private fun EntryRow(entry: JournalEntry, wasReset: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Radius.card)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), shape)
            .clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    dayLabel(entry.day),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (wasReset) {
                    Spacer(Modifier.width(8.dp))
                    // A quiet dot, not a word. The list should not read as a wall of failures.
                    Box(
                        Modifier.size(8.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error),
                    )
                }
            }
            Text(
                entry.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────── one day

/**
 * Writing on one day.
 *
 * **A page, never a `Dialog`.** Dialog windows report zero insets on the owner's phone while
 * drawing edge-to-edge, which is how a text field ends up underneath the keyboard — the bug this
 * repo has now fixed in three separate places. `safeDrawingPadding` on the root includes the IME,
 * so the field shrinks instead of being covered.
 */
@Composable
private fun JournalEntryPage(day: Int, vm: JournalViewModel, onBack: () -> Unit) {
    // null while the row is still being read. **Starting at "" would be a data-loss bug:** the
    // autosave below would fire against an empty draft and delete an entry that was simply not
    // loaded yet.
    var draft by remember(day) { mutableStateOf<String?>(null) }
    LaunchedEffect(day) { draft = vm.load(day) }

    // Save on the way out, whichever way that is — back, the system gesture, or the overlay being
    // closed from AppRoot. rememberUpdatedState so onDispose sees the latest text rather than the
    // text as it stood when the effect was created.
    val latest by rememberUpdatedState(draft)
    DisposableEffect(day) {
        onDispose { latest?.let { vm.save(day, it) } }
    }
    // …and a debounce, so a phone killed mid-sentence loses a second and a half, not the evening.
    LaunchedEffect(draft) {
        val text = draft ?: return@LaunchedEffect
        delay(1500)
        vm.save(day, text)
    }

    JournalEntryBody(
        day = day,
        draft = draft ?: "",
        onDraft = { draft = it },
        onBack = onBack,
        onDelete = {
            // Clear the draft first, or the save-on-dispose above writes it straight back.
            draft = ""
            vm.delete(day)
            onBack()
        },
    )
}

/**
 * The page itself, with no storage attached.
 *
 * Split out so its layout can be measured on a device without a database, a ViewModel or a real
 * entry — the same reason `EssentialStep` is reachable from `SetupStepVisibilityTest`. What is
 * being tested here is a shape, and a shape should not need a row in a table to exist.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun JournalEntryBody(
    day: Int,
    draft: String,
    onDraft: (String) -> Unit,
    onBack: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember(day) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        EditorTopBar(
            title = dayLabel(day),
            onBack = onBack,
            actions = {
                if (draft.isNotBlank()) {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.journal_delete_cd),
                        )
                    }
                }
                // **Done lives in the app bar, above the scroll.** That is what makes it
                // impossible for the keyboard to cover the way out — the failure this repo has
                // now fixed in three separate places.
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.testTag(JOURNAL_DONE_TAG),
                ) { Text(stringResource(R.string.common_done)) }
            },
        )
        // **The writing area is not the flexible one.** `weight(1f)` here would hand the field
        // whatever is left after the chips — and with the keyboard up at a large system font,
        // what is left is nothing. That is the exact shape of the bug that cost FrictionGate five
        // releases. A stated floor plus a scrolling page means a squeeze costs scrolling instead.
        Column(
            Modifier.fillMaxSize().pageWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PROMPTS.forEach { id ->
                    val heading = stringResource(id)
                    AssistChip(
                        onClick = { onDraft(withPrompt(draft, heading)) },
                        label = { Text(heading) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                placeholder = { Text(stringResource(R.string.journal_placeholder)) },
                shape = RoundedCornerShape(Radius.medium),
                modifier = Modifier.fillMaxWidth().heightIn(min = JOURNAL_FIELD_FLOOR)
                    .testTag(JOURNAL_FIELD_TAG),
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.journal_delete_title)) },
            text = {
                Text(stringResource(R.string.journal_delete_body, dayLabel(day)))
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/**
 * The optional headings.
 *
 * Not fields. A form would make the page a questionnaire and this has to stay a place to write
 * whatever is actually there — but "write a full report" is much easier when something has already
 * suggested where to start.
 */
private val PROMPTS = listOf(
    R.string.journal_prompt_what_happened,
    R.string.journal_prompt_set_off,
    R.string.journal_prompt_helped,
    R.string.journal_prompt_tomorrow,
)

/** Append a heading, leaving a blank line before it unless the page is still empty. */
internal fun withPrompt(text: String, heading: String): String {
    val base = text.trimEnd()
    return if (base.isEmpty()) "$heading\n" else "$base\n\n$heading\n"
}

// ─────────────────────────────────────────────────────────────────────── dates

/**
 * "Today" / "Yesterday" / "Friday, 8 August" — what the person would call the day themselves.
 *
 * Composable because the first two are words and the third is a date: only the caller's context
 * knows which language to say them in.
 */
@Composable
internal fun dayLabel(day: Int): String {
    val today = todayStamp()
    return when (day) {
        today -> stringResource(R.string.day_today)
        prevDayStamp(today) -> stringResource(R.string.day_yesterday)
        else -> dayTitle(day)
    }
}

/** The written-out date. The year appears only when it isn't this one. */
internal fun dayTitle(day: Int): String {
    val pattern = if (day / 1000 == todayStamp() / 1000) "EEEE, d MMMM" else "EEEE, d MMMM yyyy"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(dayStartMillis(day)))
}
