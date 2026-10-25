package com.appblocker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblocker.R
import com.appblocker.data.ItemLocks
import kotlinx.coroutines.delay

/** What the lock dialog is about: a kind, the stored key, and the name to show. */
private data class LockTarget(val kind: String, val key: String, val label: String)

/**
 * **Locks — Strict Mode for one thing at a time** (invariant 95). Lists what is locked and until
 * when, and every blocked app and blocked word that could be. Locking is the only action: a lock can
 * be lengthened here, never shortened or lifted, and nothing on this screen makes blocking weaker,
 * so nothing on it is disabled during Strict.
 */
@Composable
fun LocksScreen(
    onBack: () -> Unit,
    appsVm: AppListViewModel = viewModel(),
    webVm: WebFilterViewModel = viewModel(),
) {
    val context = LocalContext.current
    val apps by appsVm.apps.collectAsState()
    val words by webVm.keywords.collectAsState()
    // Re-read the locks after every change and once a minute, so an ending lock leaves on its own.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) {
        delay(60_000)
        tick++
    }
    val lockedApps = remember(tick) { ItemLocks.remaining(context, ItemLocks.Kind.APP) }
    val lockedWords = remember(tick) { ItemLocks.remaining(context, ItemLocks.Kind.WORD) }
    val blockedApps = apps.filter { it.isBlocked }.sortedBy { it.label.lowercase() }
    val labelOf = apps.associate { it.packageName to it.label }

    val locked: List<Pair<LockTarget, Long>> =
        lockedApps.map { (pkg, ms) -> LockTarget(ItemLocks.Kind.APP, pkg, labelOf[pkg] ?: pkg) to ms } +
            lockedWords.map { (w, ms) -> LockTarget(ItemLocks.Kind.WORD, w, w) to ms }
    var choosing by remember { mutableStateOf<LockTarget?>(null) }

    Box(Modifier.fillMaxSize().background(com.appblocker.ui.theme.appBackground())) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = { EditorTopBar(stringResource(R.string.locks_title), onBack) },
        ) { padding ->
            LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
                item {
                    Text(
                        stringResource(R.string.locks_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                    )
                }
                if (locked.isNotEmpty()) {
                    item { LocksHeader(stringResource(R.string.locks_now)) }
                    items(locked.sortedBy { it.second }, key = { "locked-${it.first.kind}-${it.first.key}" }) { (t, ms) ->
                        LockRow(
                            label = t.label,
                            detail = stringResource(R.string.locks_until, untilText(ms)),
                            action = stringResource(R.string.locks_extend),
                            lockedNow = true,
                        ) { choosing = t }
                    }
                }
                item { LocksHeader(stringResource(R.string.locks_apps)) }
                val freeApps = blockedApps.filter { it.packageName !in lockedApps }
                if (freeApps.isEmpty()) {
                    item { LocksEmpty(stringResource(R.string.locks_apps_none)) }
                }
                items(freeApps, key = { "app-${it.packageName}" }) { app ->
                    LockRow(app.label, null, stringResource(R.string.locks_lock), lockedNow = false) {
                        choosing = LockTarget(ItemLocks.Kind.APP, app.packageName, app.label)
                    }
                }
                item { LocksHeader(stringResource(R.string.locks_words)) }
                val freeWords = words.filter { it !in lockedWords }
                if (freeWords.isEmpty()) {
                    item { LocksEmpty(stringResource(R.string.locks_words_none)) }
                }
                items(freeWords, key = { "word-$it" }) { w ->
                    LockRow(w, null, stringResource(R.string.locks_lock), lockedNow = false) {
                        choosing = LockTarget(ItemLocks.Kind.WORD, w, w)
                    }
                }
                item { Box(Modifier.padding(top = 24.dp)) }
            }
        }
    }

    choosing?.let { t ->
        LockDaysDialog(
            label = t.label,
            onConfirm = { days ->
                ItemLocks.lock(context, t.kind, t.key, days)
                choosing = null
                tick++
            },
            onDismiss = { choosing = null },
        )
    }
}

/** "Mon 14 Dec 2026, 18:41" — the moment a lock with [remainingMs] left ends. */
private fun untilText(remainingMs: Long): String =
    java.text.SimpleDateFormat("EEE d MMM yyyy, HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(System.currentTimeMillis() + remainingMs))

@Composable
private fun LocksHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun LocksEmpty(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun LockRow(label: String, detail: String?, action: String, lockedNow: Boolean, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (lockedNow) {
            Icon(
                Icons.Filled.Lock, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = onAction) { Text(action) }
    }
}

/**
 * How long. Fixed choices on purpose: no text field, so no keyboard, so none of the dialog-inset
 * trouble this phone has (see `DurationPickerDialog`).
 */
@Composable
private fun LockDaysDialog(label: String, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var days by remember { mutableIntStateOf(30) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.locks_dialog_title, label)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.locks_dialog_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                ItemLocks.DAY_CHOICES.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = days == d, onClick = { days = d })
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = days == d, onClick = { days = d })
                        Text(pluralStringResource(R.plurals.locks_days, d, d.toString()))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(days) }) {
                Text(pluralStringResource(R.plurals.locks_confirm, days, days.toString()))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
