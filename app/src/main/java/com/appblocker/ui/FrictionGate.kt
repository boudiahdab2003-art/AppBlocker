package com.appblocker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.appblocker.data.TypedChallenge
import kotlinx.coroutines.delay

/**
 * The app's one "are you really sure, and is it really you" gate: type a fresh random 40-word
 * paragraph (pasting disabled) **before a three-minute clock runs out**. Run out of time and the
 * paragraph is replaced with a new one and the clock starts again — as many times as it takes.
 *
 * The clock used to be a *wait*: the button unlocked after two minutes however fast you typed, so
 * the timer was something to sit through rather than something to beat, and the paragraph could be
 * copied out at leisure. Now it is a deadline. The rules themselves live in
 * [com.appblocker.data.TypedChallenge], where they can be tested; this file is the screen.
 *
 * Shared rather than copied. Two protections now stand behind this gate — the adult word pack
 * ([KeywordsScreen]) and the off-switch guard ([ProfileScreen]) — and a second copy would be a
 * second thing to keep honest: the day someone weakened one (a shorter countdown, a paste field
 * left enabled) the other would quietly stay strong and nobody would notice the difference. The
 * friction *is* the feature, so it lives in exactly one place.
 *
 * Confirming never turns anything off by itself. Every caller uses it to *request* an off, which
 * a [com.appblocker.data.GuardedDeadline] then makes wait; see [KeywordsScreen]'s cooling-off and
 * [com.appblocker.data.OffSwitchGuard].
 *
 * A full screen in the activity window, deliberately NOT a Dialog: dialog windows report zero
 * insets on the owner's device (see DurationPickerDialog in WheelPicker.kt), which left the
 * keyboard sitting on top of the challenge field — typing was invisible.
 */
@Composable
fun FrictionGate(
    title: String,
    blurb: String,
    /** Label for the confirm button once the paragraph is typed — name the *wait* that follows
     *  where there is one, not the switch, because confirming only starts that wait. */
    confirmLabel: String,
    dismissLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    // Bumping this is what "start again" means: the paragraph, the field and the clock are all
    // keyed to it, so one increment replaces all three at once and none can be left behind.
    var attempt by remember { mutableStateOf(0) }
    val phrase = remember(attempt) { TypedChallenge.newPhrase() }
    var input by remember(attempt) { mutableStateOf("") }
    var remaining by remember(attempt) { mutableStateOf(TypedChallenge.ATTEMPT_SECONDS) }
    // Keyed to the attempt like everything else: a warning left over from the last paragraph,
    // sitting under an empty field, reads as a bug in the new one.
    var blockedPaste by remember(attempt) { mutableStateOf(false) }
    val matched = TypedChallenge.matches(phrase, input)
    // Latches on the first match and stops the clock. Without it, finishing at 0:01 and reaching
    // for the button would lose the attempt — which does not read as strictness, it reads as the
    // app cheating. Having typed it once is the proof; the deadline was on the typing.
    var solved by remember(attempt) { mutableStateOf(false) }
    LaunchedEffect(attempt, matched) { if (matched) solved = true }
    LaunchedEffect(attempt, solved) {
        if (solved) return@LaunchedEffect
        while (remaining > 0) { delay(1000); remaining-- }
        // Out of time, and it was not typed: new paragraph, clock back to full.
        attempt++
    }

    BackHandler { onDismiss() }
    // Background BEFORE safeDrawingPadding so the app color still paints behind the system
    // bars; safeDrawing includes the keyboard, keeping the field and buttons above it.
    Column(
        Modifier.fillMaxSize().background(com.appblocker.ui.theme.appBackground())
            .safeDrawingPadding(),
    ) {
        EditorTopBar(title, onBack = onDismiss)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            Text(
                blurb,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Says what just happened, rather than leaving a silently different paragraph and a
            // cleared field to be worked out. Counted, so a run of near-misses is visible.
            if (attempt > 0 && !solved) {
                Spacer(Modifier.padding(top = 12.dp))
                Text(
                    "⏱ Time ran out. Here's a new paragraph — the clock starts again. " +
                        "(Attempt ${attempt + 1})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.padding(top = 12.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
            ) {
                Text(phrase, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.padding(top = 12.dp))
        }
        // Pinned below the scrollable paragraph: with the keyboard open, the paragraph area
        // above shrinks (and scrolls on its own) while the field, hint and buttons stay
        // visible just over the keyboard — type and read at the same time.
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                if (solved) "Typed ✓ — the clock has stopped."
                else "Time left: ${clock(remaining)}",
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    solved -> MaterialTheme.colorScheme.primary
                    remaining <= 30 -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(bottom = 8.dp),
            )
            // A no-op text toolbar removes the cut/copy/paste popup entirely, so the paragraph
            // must be hand-typed. That alone is not enough: a keyboard's own clipboard (Gboard's
            // clipboard chip) commits text through the IME without ever opening that popup, so
            // the size of each edit is checked as well — see TypedChallenge.isPaste.
            CompositionLocalProvider(LocalTextToolbar provides NoPasteToolbar) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { new ->
                        if (TypedChallenge.isPaste(input, new)) blockedPaste = true
                        else { input = new; blockedPaste = false }
                    },
                    placeholder = { Text("Type the paragraph here") },
                    singleLine = false,
                    keyboardOptions = KeyboardOptions(
                        autoCorrect = false,
                        capitalization = KeyboardCapitalization.None,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                )
            }
            if (blockedPaste) {
                Text(
                    "Pasting is off — this one has to be typed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else if (input.isNotBlank()) {
                Text(
                    if (matched) "Matches ✓" else "Doesn't match yet — keep going",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (matched) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        GradientButton(
            text = confirmLabel,
            enabled = solved,
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp),
        )
        TextButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 4.dp, bottom = 12.dp),
        ) { Text(dismissLabel) }
    }
}

/** `m:ss`, for a countdown a person reads at a glance while typing. */
private fun clock(seconds: Int): String =
    "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

/** Text toolbar that shows nothing — used to block paste (and copy/cut) on the challenge field.
 *  Only half the defence: see TypedChallenge.isPaste for the half this cannot cover. */
private val NoPasteToolbar = object : TextToolbar {
    override val status: TextToolbarStatus = TextToolbarStatus.Hidden
    override fun hide() {}
    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {}
}
