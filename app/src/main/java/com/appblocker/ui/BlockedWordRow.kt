package com.appblocker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.appblocker.data.SiteWord

/**
 * One row of the blocked-words list, shared by the Blocked words screen and the Quick Block
 * editor's "Websites & words" page so the two can't drift.
 *
 * [covering] is the app whose website block already covers this word (null when nothing does).
 * That is what makes the row removable during a Strict session: deleting a word every address of
 * which stays blocked by another rule takes nothing away, and the rule that covers it can't itself
 * be lowered before the session ends. See `StrictEdits.coveredBy` and invariant 16.
 */
@Composable
fun BlockedWordRow(
    word: String,
    covering: SiteWord?,
    strictActive: Boolean,
    onRemove: () -> Unit,
) {
    var confirming by remember(word) { mutableStateOf(false) }
    val needsExplaining = strictActive && covering != null

    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                word,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
            // Shown whether or not Strict is running: outside a session it is the same true fact,
            // and it is the one that explains why this word is doing nothing your app rule wasn't
            // already doing (the reason the nine template words had to be purged in v1.85).
            if (covering != null) {
                Text(
                    "${covering.appLabel} is blocked, so its website is too",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        IconButton(
            enabled = !strictActive || covering != null,
            onClick = { if (needsExplaining) confirming = true else onRemove() },
        ) {
            Icon(
                Icons.Filled.Delete, contentDescription = "Remove",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirming && covering != null) {
        RemoveCoveredWordDialog(
            word = word,
            appLabel = covering.appLabel,
            onConfirm = { confirming = false; onRemove() },
            onDismiss = { confirming = false },
        )
    }
}

/**
 * Asked before a word is removed mid-Strict. It exists to be honest: the removal is allowed
 * because nothing gets unblocked, but it is not free, and the two things it costs are exactly the
 * two the owner would otherwise discover by surprise.
 *
 * The text slot scrolls. M3's AlertDialog doesn't scroll its own, and three paragraphs at a large
 * system font on a short screen is the shape of the v1.113-v1.115 layout bugs.
 */
@Composable
private fun RemoveCoveredWordDialog(
    word: String,
    appLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove “$word”?") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "The website stays blocked. You've blocked $appLabel, and blocking an app " +
                        "blocks its website too — that's why Strict Mode can let this one go.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "What you lose: the word stops being blocked inside other apps, so a chat or " +
                        "an article that only mentions it won't be covered any more. And in your " +
                        "browser the site gets covered without locking the browser for half an " +
                        "hour afterwards.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    "You can add it back any time, Strict Mode or not.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Remove", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep it") } },
    )
}
