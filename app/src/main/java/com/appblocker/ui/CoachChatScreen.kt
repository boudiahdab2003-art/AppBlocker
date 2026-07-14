package com.appblocker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblocker.data.ChatMsg
import com.appblocker.data.Goal
import com.appblocker.ui.theme.AppGradients
import com.appblocker.ui.theme.softGlow

/** Full-screen chat with the AI Coach: goal chips up top, message bubbles, and an input bar. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CoachChatScreen(onBack: () -> Unit, vm: CoachChatViewModel = viewModel()) {
    val messages by vm.messages.collectAsState()
    val sending by vm.sending.collectAsState()
    val goals by vm.goals.collectAsState()
    val suggestions by vm.suggestions.collectAsState()
    val profile by vm.profile.collectAsState()
    var input by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Keep the newest bubble (or the typing indicator) in view.
    LaunchedEffect(messages.size, sending) {
        val count = messages.size + if (sending) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    // safeDrawing = status bar + nav bar + keyboard: the screen has no Scaffold of its own, and
    // targetSdk 35 forces edge-to-edge on Android 15+ (imePadding alone left the top bar under
    // the status bar there).
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        EditorTopBar(title = "AI Coach", onBack = onBack) {
            IconButton(onClick = { showProfile = true }) {
                Icon(Icons.Filled.Person, contentDescription = "What your coach knows")
            }
            IconButton(onClick = { confirmClear = true }) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = "Clear chat")
            }
        }

        if (goals.isNotEmpty()) {
            GoalChips(goals, onRemove = { vm.removeGoal(it) })
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages.size) { i -> Bubble(messages[i]) }
            if (sending) {
                item {
                    CoachRow {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Thinking…", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        if (suggestions.isNotEmpty() && !sending) {
            SuggestionChips(suggestions, onTap = { vm.send(it) })
        }

        InputBar(
            value = input,
            onChange = { input = it },
            sendEnabled = input.isNotBlank() && !sending,
            onSend = { vm.send(input); input = "" },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear chat?") },
            text = { Text("The conversation is deleted from this device. Your goals and what " +
                "your coach knows about you are kept.") },
            confirmButton = {
                TextButton(onClick = { vm.clearChat(); confirmClear = false }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }

    if (showProfile) {
        ProfileDialog(
            profile = profile,
            onForget = { vm.clearProfile(); showProfile = false },
            onClose = { showProfile = false },
        )
    }
}

/** Everything the coach has learned about the user — visible, and erasable in one tap. */
@Composable
private fun ProfileDialog(
    profile: Map<String, String>,
    onForget: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("What your coach knows") },
        text = {
            if (profile.isEmpty()) {
                Text("Nothing yet — the more you chat, the better your coach knows you.")
            } else {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    profile.forEach { (key, value) ->
                        Column {
                            Text(key.replace('_', ' ').replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary)
                            Text(value, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        dismissButton = {
            if (profile.isNotEmpty()) {
                TextButton(onClick = onForget) {
                    Text("Forget everything", color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoalChips(goals: List<Goal>, onRemove: (Goal) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Flag, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("Your goals", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.padding(top = 6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            goals.forEach { goal ->
                Row(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, AppGradients.accent, RoundedCornerShape(50))
                        .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(goal.label(), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.Close, contentDescription = "Remove goal",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp).clip(CircleShape)
                            .clickable { onRemove(goal) })
                }
            }
        }
    }
}

/** Tappable one-tap prompts above the input bar — starters on open, then the coach's own
 *  suggested follow-ups after each reply. */
@Composable
private fun SuggestionChips(suggestions: List<String>, onTap: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        suggestions.forEach { s ->
            Box(
                Modifier.clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                        RoundedCornerShape(50))
                    .clickable { onTap(s) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(s, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Coach-side layout: small gradient badge + a bubble capped at ~80% width. */
@Composable
private fun CoachRow(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        Box(
            Modifier.padding(top = 2.dp).size(24.dp).clip(CircleShape)
                .background(AppGradients.accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Color.White,
                modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.weight(1f, fill = false)
                .clip(RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) { content() }
    }
}

@Composable
private fun Bubble(msg: ChatMsg) {
    when (msg.role) {
        "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier.widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp))
                    .background(AppGradients.accent)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(msg.text, style = MaterialTheme.typography.bodyMedium, color = Color.White)
            }
        }
        "model" -> CoachRow { CoachMessage(msg.text) }
        else -> CoachRow { // "local": greeting / error notes — same side, muted
            Text(msg.text, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Renders a coach reply with light structure so reports read like reports, not walls of text:
 * short lines ending in ':' become bold headings, '-'/'•'/'*' lines become gradient-dot
 * bullets, '1. '/'2) ' lines become numbered steps, blank lines become spacing, and **bold**
 * spans highlight key numbers. Plain replies (old messages included) come through unchanged.
 */
@Composable
private fun CoachMessage(text: String) {
    Column {
        text.lines().forEach { raw ->
            val line = raw.trimEnd()
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> Spacer(Modifier.padding(top = 6.dp))

                trimmed.length <= 48 && trimmed.endsWith(":") &&
                    !trimmed.startsWith("-") && !trimmed.startsWith("•") -> {
                    Text(parseBold(trimmed), style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
                }

                trimmed.startsWith("- ") || trimmed.startsWith("• ") ||
                    trimmed.startsWith("* ") -> {
                    Row(Modifier.padding(top = 4.dp)) {
                        Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape)
                            .background(AppGradients.accent))
                        Spacer(Modifier.width(9.dp))
                        Text(parseBold(trimmed.substring(2).trim()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }

                trimmed.matches(NUMBERED_LINE) -> {
                    val sep = trimmed.indexOfFirst { it == '.' || it == ')' }
                    Row(Modifier.padding(top = 4.dp)) {
                        Text(trimmed.substring(0, sep + 1),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(parseBold(trimmed.substring(sep + 1).trim()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }

                else -> Text(parseBold(trimmed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** "1. Do this" / "2) Then that" step lines — 1-2 digits so years/plain numbers never match. */
private val NUMBERED_LINE = Regex("""^\d{1,2}[.)] .+""")

/** Turns `**bold**` spans into real bold; a line with unmatched markers renders plain. */
private fun parseBold(line: String): AnnotatedString = buildAnnotatedString {
    val parts = line.split("**")
    // Well-formed lines split into an odd count (text, bold, text, …); an even count means
    // an unmatched marker — drop the noise and render the text plain.
    if (parts.size % 2 == 0) {
        append(line.replace("**", ""))
        return@buildAnnotatedString
    }
    parts.forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) }
        else append(part)
    }
}

@Composable
private fun InputBar(
    value: String,
    onChange: (String) -> Unit,
    sendEnabled: Boolean,
    onSend: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message your coach…") },
            shape = RoundedCornerShape(24.dp),
            maxLines = 4,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
        )
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.padding(bottom = 4.dp).size(48.dp)
                .then(if (sendEnabled) Modifier.softGlow(CircleShape, elevation = 8.dp) else Modifier)
                .clip(CircleShape)
                .background(
                    if (sendEnabled) AppGradients.accent
                    else androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.surface)
                )
                .clickable(enabled = sendEnabled) { onSend() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send",
                tint = if (sendEnabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp))
        }
    }
}
