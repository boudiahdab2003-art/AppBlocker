package com.appblocker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
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
 * **The screen is laid out around the typing, because the first version was not.** It led with six
 * sentences of explanation, which at a large system font filled the display entirely — the
 * paragraph to be transcribed was not on screen at all, and the card holding it was sliced through
 * mid-word by the controls pinned below. So: the clock is a draining bar under the title, the
 * explanation is one line with the consequences behind a tap, and the paragraph gets the room left
 * over and scrolls *inside its own card* so its bottom edge is always a rounded edge rather than a
 * cut. Everything that decides whether the gate opens is untouched; this is a redraw, not a
 * loosening.
 *
 * Shared rather than copied. Three protections now stand behind this gate — the adult word pack
 * ([KeywordsScreen]), the off-switch guard ([ProfileScreen]) and the uninstall block
 * ([Permissions]) — and a second copy would be a second thing to keep honest: the day someone
 * weakened one (a shorter countdown, a paste field left enabled) the others would quietly stay
 * strong and nobody would notice the difference. The friction *is* the feature, so it lives in
 * exactly one place.
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
    /** One line: what this switch is and what has to be done. Always on screen. */
    blurb: String,
    /** What follows once the paragraph is typed — the waits, the windows. Behind a tap, because
     *  it is read once and then costs space for the rest of the attempt. */
    detail: String,
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
    var showDetail by remember { mutableStateOf(false) }
    val matched = TypedChallenge.matches(phrase, input)
    val progress = TypedChallenge.progress(phrase, input)
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

        ClockStrip(
            remaining = remaining,
            solved = solved,
            wordsDone = progress.correct,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        // Read before starting, in the way of everything afterwards. Once the first character is
        // typed the instructions have done their job and the room goes to the paragraph — which at
        // a large font size is the difference between seeing three lines of it and seeing ten.
        AnimatedVisibility(visible = input.isEmpty()) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(12.dp))
                Text(
                    blurb,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .clickable { showDetail = !showDetail }
                        .padding(vertical = 8.dp),
                ) {
                    Text(
                        "What happens next?",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        if (showDetail) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (showDetail) "Hide" else "Show",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                if (showDetail) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Says what just happened, rather than leaving a silently different paragraph and
                // a cleared field to be worked out. Counted, so a run of near-misses is visible.
                if (attempt > 0 && !solved) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "⏱ Time ran out. Here's a new paragraph — the clock starts again. " +
                            "(Attempt ${attempt + 1})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        PhraseCard(
            phrase = phrase,
            progress = progress,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 12.dp),
        )

        Column(Modifier.padding(horizontal = 16.dp)) {
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
                    placeholder = { Text("Type it here") },
                    singleLine = false,
                    // Three lines, not one: at the owner's font size a 56dp field showed about six
                    // words of the forty he had just typed, so there was no way to check his own
                    // work without scrolling a text field.
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(
                        autoCorrect = false,
                        capitalization = KeyboardCapitalization.None,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // Reserved height, so a line appearing and disappearing doesn't shove the buttons
            // under the typist's thumb mid-word.
            Box(Modifier.fillMaxWidth().heightIn(min = 28.dp).padding(top = 6.dp)) {
                when {
                    blockedPaste -> Text(
                        "Pasting is off — this one has to be typed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    solved -> Text(
                        "Typed ✓ — the clock has stopped.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    // Naming the word is the whole gain: "doesn't match yet" left him hunting
                    // through forty words for one letter. Past the end there is no word to name,
                    // so say what is actually true instead of pointing at word 41.
                    progress.wrong -> Text(
                        if (progress.correct >= TypedChallenge.WORDS)
                            "That's more than the paragraph — delete the extra words."
                        else "Word ${progress.correct + 1} doesn't match — fix it to carry on.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        GradientButton(
            text = confirmLabel,
            enabled = solved,
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp),
        )
        TextButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 4.dp, bottom = 12.dp),
        ) { Text(dismissLabel) }
    }
}

/**
 * The clock as something that drains, plus the count of words behind you.
 *
 * It was a line of grey body text between the paragraph and the field, which is where a deadline
 * is least visible. A bar says "running out" without being read.
 */
@Composable
private fun ClockStrip(
    remaining: Int,
    solved: Boolean,
    wordsDone: Int,
    modifier: Modifier = Modifier,
) {
    // Red only for the last half-minute, and never once it's typed — a solved gate showing an
    // alarming bar would be telling him to hurry over something already done.
    val accent = if (!solved && remaining <= 30) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.primary
    Column(modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = {
                if (solved) 1f else remaining.toFloat() / TypedChallenge.ATTEMPT_SECONDS
            },
            color = accent,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).height(6.dp),
        )
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (solved) "Clock stopped" else "${clock(remaining)} left",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
            Text(
                "$wordsDone / ${TypedChallenge.WORDS} words",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The paragraph, with the typing marked on it: what's behind you faded, the word you're on
 * highlighted (red the moment it goes wrong), what's ahead in full strength.
 *
 * Scrolls **inside itself**, which is the fix for the card that used to be sliced through mid-word
 * by the controls below it. And it follows the highlight: at a large font only a few lines fit, so
 * a marker you cannot see would be no marker at all. It moves only when the current word has left
 * the visible part, so the text isn't twitching under the eye on every word.
 */
@Composable
private fun PhraseCard(
    phrase: String,
    progress: TypedChallenge.Progress,
    modifier: Modifier = Modifier,
) {
    val words = remember(phrase) { phrase.split(" ") }
    val spent = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val ahead = MaterialTheme.colorScheme.onSurface
    val hereText = if (progress.wrong) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onPrimaryContainer
    val hereBg = if (progress.wrong) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.primaryContainer

    // Not memoized on purpose: forty spans is nothing, and every candidate cache key here (the
    // progress, and four theme colours that change with light/dark) is one more thing that has to
    // be right for the highlight to land on the correct word.
    val text = buildAnnotatedString {
        words.forEachIndexed { i, word ->
            if (i > 0) append(" ")
            val style = when {
                i < progress.correct -> SpanStyle(color = spent)
                i == progress.correct -> SpanStyle(
                    color = hereText,
                    background = hereBg,
                    fontWeight = FontWeight.Bold,
                )
                else -> SpanStyle(color = ahead)
            }
            withStyle(style) { append(word) }
        }
    }
    // Character offset the current word starts at — the words are joined by single spaces, so it
    // is just the lengths so far plus one separator each.
    val offset = remember(phrase, progress.correct) {
        words.take(progress.correct).sumOf { it.length + 1 }.coerceAtMost(text.length - 1)
    }

    val scroll = rememberScrollState()
    var layout by remember(phrase) { mutableStateOf<TextLayoutResult?>(null) }
    var viewport by remember { mutableStateOf(0) }
    LaunchedEffect(offset, layout, viewport) {
        val l = layout ?: return@LaunchedEffect
        if (viewport <= 0 || offset < 0) return@LaunchedEffect
        val line = l.getLineForOffset(offset)
        val top = l.getLineTop(line).toInt()
        val bottom = l.getLineBottom(line).toInt()
        when {
            top < scroll.value -> scroll.animateScrollTo(top)
            bottom > scroll.value + viewport ->
                scroll.animateScrollTo((bottom - viewport).coerceAtLeast(0))
        }
    }

    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onSizeChanged { viewport = it.height },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(14.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                onTextLayout = { layout = it },
            )
        }
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
