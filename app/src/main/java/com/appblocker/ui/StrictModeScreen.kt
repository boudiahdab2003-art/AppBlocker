package com.appblocker.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblocker.data.SettingsStore
import com.appblocker.ui.theme.AppGradients
import com.appblocker.ui.theme.pageWidth
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.stringResource
import com.appblocker.R

@Composable
fun StrictModeScreen(
    vm: FocusViewModel = viewModel(),
    homeVm: HomeViewModel = viewModel(),
    scheduleVm: ScheduleViewModel = viewModel(),
) {
    val active by vm.isActive.collectAsState()
    val remaining by vm.remainingMillis.collectAsState()
    val context = LocalContext.current

    // What Strict Mode would lock — used to stop a pointless no-op activation.
    val appsBlocked by homeVm.appsBlocked.collectAsState()
    val keywords by homeVm.keywordCount.collectAsState()
    val schedules by scheduleVm.schedules.collectAsState()
    val enabledSchedules = schedules.count { it.enabled }
    val adultOn = SettingsStore.blockAdult(context)
    val hasSomethingToLock = appsBlocked > 0 || keywords > 0 || adultOn || enabledSchedules > 0

    Column(
        // pageWidth, not fillMaxWidth: on a tablet this screen used to stretch the countdown
        // and the add-time chips across the whole display.
        Modifier.fillMaxHeight().pageWidth()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.strict_title), style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(28.dp))

        LockOrb(locked = active)
        Spacer(Modifier.height(24.dp))

        Text(
            stringResource(
                if (active) R.string.strict_locked else R.string.strict_unlocked,
            ),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))

        if (active) {
            Text(
                fmtCountdown(remaining, padMinutes = true),
                fontSize = 56.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.strict_no_stopping),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            AddTimeRow(remaining = remaining, onAdd = { vm.extend(it) })
            Spacer(Modifier.height(24.dp))
            LockedList()
        } else {
            Text(stringResource(R.string.strict_whats_locked),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            LockedList()
            Spacer(Modifier.height(28.dp))
            UnlockMethod(
                canActivate = hasSomethingToLock,
                summary = lockSummary(appsBlocked, enabledSchedules, keywords, adultOn),
                onActivate = { minutes ->
                    ensureDeviceAdmin(context)
                    vm.start(minutes)
                },
            )
        }
    }
}

@Composable
private fun LockOrb(locked: Boolean) {
    // Outer halo
    Box(
        Modifier.size(190.dp)
            .background(AppGradients.glow(if (locked) 0.45f else 0.12f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        // Gradient ring
        Box(
            Modifier.size(140.dp).clip(CircleShape)
                .then(
                    if (locked) Modifier.border(3.dp, AppGradients.accent, CircleShape)
                    else Modifier.border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                ),
            contentAlignment = Alignment.Center,
        ) {
            // Inner disc
            Box(
                Modifier.size(124.dp).clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.colorScheme.surface,
                            )
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = null,
                    tint = if (locked) AppGradients.AccentStart else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(58.dp),
                )
            }
        }
    }
}

@Composable
private fun LockedList() {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LockedRow(stringResource(R.string.strict_locked_blocks))
        LockedRow(stringResource(R.string.strict_locked_service))
        LockedRow(stringResource(R.string.strict_locked_uninstall))
    }
}

@Composable
private fun LockedRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Lock, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UnlockMethod(canActivate: Boolean, summary: String, onActivate: (Int) -> Unit) {
    var minutes by remember { mutableIntStateOf(60) }
    var showPicker by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }

    Text(stringResource(R.string.strict_unlock_method),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(10.dp))

    // Method (Timer) + duration selector row — tapping opens the full "Set the timer" wheel.
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { showPicker = true }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Schedule, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.strict_timer),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.weight(1f))
        Text(durationLabel(minutes), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Spacer(Modifier.height(16.dp))
    Text(
        if (canActivate) summary
        else stringResource(R.string.strict_nothing_to_lock),
        style = MaterialTheme.typography.bodyMedium,
        color = if (canActivate) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFFFB020),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    GradientButton(
        text = stringResource(R.string.strict_activate),
        enabled = canActivate,
        onClick = { confirm = true },
    )

    if (showPicker) {
        DurationPickerDialog(
            title = stringResource(R.string.strict_set_timer),
            initialMinutes = minutes,
            onSave = { minutes = it },
            onDismiss = { showPicker = false },
        )
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            confirmButton = {
                TextButton(onClick = { confirm = false; onActivate(minutes) }) {
                    Text(stringResource(R.string.strict_start_lock))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            title = { Text(stringResource(R.string.strict_confirm_title)) },
            text = {
                Text(
                    "Your blocks will be locked for ${humanDuration(minutes)} — until " +
                        "${endsAt(minutes)}.\n\nYou can't stop early. You'll be able to add " +
                        "more time while it runs, but never cut it short.",
                )
            },
        )
    }
}

/** Test tags for the rendering test — the fast path and the unbounded one. */
const val ADD_TIME_CHIP_TAG = "add_time_chip"
const val ADD_TIME_CHOOSE_TAG = "add_time_choose"

/**
 * "Add more time" — the only control a running Strict session offers, and the only change it
 * accepts.
 *
 * Extending is the safe direction, so it gets none of this app's usual friction: no typed
 * paragraph, no cooling-off, no PIN. Every one of those exists to stand between a bad moment and
 * *less* protection. The worst thing this button can do is leave someone protected for longer
 * than they meant.
 *
 * **Why the chips act immediately and "Choose…" confirms.** Adding time cannot be undone, and this
 * app puts friction in front of things that cannot be undone — but proportional to the size of the
 * mistake. A mis-tapped chip costs at most an hour, and the end time is on screen before the tap.
 * The wheel goes up to thirty days, which is why starting a session already confirms; the same
 * dialog guards the same magnitude here.
 *
 * Stateless apart from its own dialog flags, and separate from [StrictModeScreen], so a rendering
 * test can measure it without standing up a [FocusViewModel] and Room.
 */
@Composable
internal fun AddTimeRow(remaining: Long, onAdd: (Int) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    var pendingMinutes by remember { mutableIntStateOf(0) }
    // The session's current deadline — what an extension is measured from, not "now".
    val deadline = System.currentTimeMillis() + remaining

    Text(
        stringResource(R.string.strict_add_time_header),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(10.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AddChip("+15m", Modifier.weight(1f)) { onAdd(15) }
        AddChip("+30m", Modifier.weight(1f)) { onAdd(30) }
        AddChip("+1h", Modifier.weight(1f)) { onAdd(60) }
    }
    // "Choose…" gets its own full-width row rather than being a fourth chip. Four equal columns
    // put the longest label in the narrowest space, and at the large system font the owner runs
    // that is where a label gets clipped — the exact defect FontScaleTest exists for. Three short,
    // fixed labels share a row safely; an open-ended one does not.
    Spacer(Modifier.height(8.dp))
    AddChip("Choose another amount", Modifier.fillMaxWidth().testTag(ADD_TIME_CHOOSE_TAG)) {
        showPicker = true
    }
    Spacer(Modifier.height(10.dp))
    Text(
        stringResource(R.string.strict_ends_at, endsAt(0, deadline)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
    )

    if (showPicker) {
        DurationPickerDialog(
            title = stringResource(R.string.strict_add_more_time),
            initialMinutes = 30,
            // Measured from the session's deadline, so the picker's live "Ends …" preview tells
            // the truth about an extension rather than about a fresh session.
            baseMillis = deadline,
            onSave = { pendingMinutes = it },
            onDismiss = { showPicker = false },
        )
    }
    if (pendingMinutes > 0) {
        AlertDialog(
            onDismissRequest = { pendingMinutes = 0 },
            confirmButton = {
                TextButton(onClick = { val m = pendingMinutes; pendingMinutes = 0; onAdd(m) }) {
                    Text(stringResource(R.string.strict_add_the_time))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingMinutes = 0 }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            title = {
                Text(stringResource(R.string.strict_add_confirm_title, humanDuration(pendingMinutes)))
            },
            text = {
                Text(
                    "Your blocks will stay locked until ${endsAt(pendingMinutes, deadline)}." +
                        "\n\nYou can't undo this or cut it short.",
                )
            },
        )
    }
}

@Composable
private fun AddChip(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.testTag(ADD_TIME_CHIP_TAG).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}

/** Human duration like "45 min", "1 h 30 min", "2 d 3 h". */
private fun humanDuration(minutes: Int): String {
    val d = minutes / 1440; val h = (minutes % 1440) / 60; val m = minutes % 60
    val parts = mutableListOf<String>()
    if (d > 0) parts += "$d d"
    if (h > 0) parts += "$h h"
    if (m > 0 || parts.isEmpty()) parts += "$m min"
    return parts.joinToString(" ")
}

/**
 * When a lock of [minutes] measured from [baseMillis] would end, e.g. "Mon, Jun 23 at 3:45 PM".
 *
 * [baseMillis] defaults to now, which is right for starting a session and wrong for extending one:
 * adding an hour to a session with two hours left ends in three hours, not one.
 */
private fun endsAt(minutes: Int, baseMillis: Long = System.currentTimeMillis()): String {
    val end = Date(baseMillis + minutes * 60_000L)
    return SimpleDateFormat("EEE, MMM d 'at' h:mm a", Locale.getDefault()).format(end)
}

/** One-line summary of what Strict Mode will lock. */
private fun lockSummary(apps: Int, schedules: Int, keywords: Int, adultOn: Boolean): String {
    val parts = mutableListOf<String>()
    if (apps > 0) parts += "$apps app${if (apps == 1) "" else "s"}"
    if (schedules > 0) parts += "$schedules schedule${if (schedules == 1) "" else "s"}"
    if (keywords > 0) parts += "$keywords word${if (keywords == 1) "" else "s"}"
    if (adultOn) parts += "adult filter"
    return "Locks " + parts.joinToString(" · ") + "."
}

private fun durationLabel(minutes: Int): String = when {
    minutes >= 1440 -> "${minutes / 1440}d"
    minutes >= 60 -> "${minutes / 60}h"
    else -> "${minutes}m"
}

/**
 * Delegates rather than building the intent itself — which is the whole fix here.
 *
 * This function used to be its own copy of the ADD_DEVICE_ADMIN launch, and the copy never learnt
 * what [enableDeviceAdmin] learnt in v1.108: to tell [com.appblocker.data.AdminPrompt] that *we*
 * opened the activation screen. Without that stamp the guard reads a device-admin screen it can't
 * account for and bounces it, so starting a Strict session could not switch uninstall protection
 * on — the exact v1.107 bug, still alive on this path because the fix was applied to one call site
 * and not the other.
 */
private fun ensureDeviceAdmin(context: Context) = enableDeviceAdmin(
    context,
    explanation = context.getString(R.string.strict_needs_admin),
)
