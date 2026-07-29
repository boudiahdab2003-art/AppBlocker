package com.appblocker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.alpha
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
 * **The screen is laid out around the typing, because three earlier versions were not.** Each led
 * with content that had a fixed height while the paragraph — the one thing the screen exists for —
 * had `weight(1f)`, which is not a size but *whatever is left over*. So the explanation ate it, and
 * then the keyboard ate what was left, and the paragraph rendered as one clipped line and then as
 * nothing at all. The rule the file now follows, and the reason for every layout choice in it:
 * **the element a screen exists for must never be the flexible one.** The clock and title are
 * pinned, everything else scrolls, and the paragraph's height is computed from the measured
 * viewport with a floor it can never go below. Everything that decides whether the gate opens is
 * untouched by all of this; it has only ever been a redraw, never a loosening.
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
    var showHelp by remember { mutableStateOf(false) }
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
        // The explanation lives behind this button and nowhere else. On screen it cost five lines
        // at the owner's font size, and since the paragraph card was the only flexible element,
        // those five lines came *out of the paragraph* — which is how a screen whose entire job is
        // "read this and type it" ended up showing one clipped line of it. A corner button costs
        // nothing, and the screen explains itself: a countdown, a word count, and a box saying
        // where to type.
        EditorTopBar(title, onBack = onDismiss) {
            IconButton(onClick = { showHelp = true }) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = "How this works",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        ClockStrip(
            remaining = remaining,
            solved = solved,
            wordsDone = progress.correct,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        // **Everything below the clock scrolls, and only this scroller flexes.**
        //
        // The screen used to be one Column where every child had a fixed height except the
        // paragraph card, which had `weight(1f)` — "take whatever is left over". Open the
        // keyboard and there was nothing left over, so the paragraph went to zero and the last
        // button was sliced in half. Removing content bought room with the keyboard down and
        // none with it up; the defect was the layout contract, not the amount of content.
        //
        // A weight on a *scroll container* is safe in the way it is not on content: squeezing it
        // scrolls instead of starving what is inside. Every child below keeps its own height
        // whatever the keyboard does, and the text field brings itself into view on focus.
        // (The top bar and the clock stay pinned above — a countdown you have to scroll to find
        // is not a countdown.)
        //
        // `heightIn(min = maxHeight)` + centre arrangement is what stops the page ending in a
        // void: the content is at least as tall as the viewport, so a short page sits centred
        // instead of clinging to the top with a third of the screen empty beneath it, and a tall
        // one (keyboard up) simply grows past it and scrolls as before. One expression covering
        // both states, rather than a height guessed for one of them.
        BoxWithConstraints(Modifier.weight(1f)) {
            // Captured, not read where it is used: Compose's layout scopes carry a @DslMarker, so
            // `maxHeight` cannot be reached by implicit receiver from inside the Column's content
            // lambda below. A local is clearer than an explicit qualifier anyway — it names the
            // thing every height on this screen is derived from.
            val viewport = maxHeight
            Column(
                Modifier.verticalScroll(rememberScrollState())
                    .heightIn(min = viewport)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
            ) {
                // Says what just happened, rather than leaving a silently different paragraph and a
                // cleared field to be worked out. Counted, so a run of near-misses is visible.
                if (attempt > 0 && !solved && input.isEmpty()) {
                    Text(
                        "Time ran out — attempt ${attempt + 1}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

                PhraseCard(
                    phrase = phrase,
                    progress = progress,
                    // **A stated height, computed from the space that exists — never leftovers.**
                    //
                    // The difference from `weight(1f)`, which broke this screen three times, is
                    // the clamp. A weight is whatever remains after everything else has taken its
                    // share, so it can reach zero; this starts from the *measured* viewport
                    // (BoxWithConstraints, not a guess) and can never leave less than 200dp
                    // however wrong the 220dp allowance for the box and button turns out to be at
                    // some font scale. A bad estimate costs a little scrolling; it cannot cost
                    // the paragraph.
                    //
                    // Growing into the space is also what removes the dead area under the button
                    // when the keyboard is down: the paragraph uses the room rather than the page
                    // ending early. The ceiling stops it swallowing a tall screen whole.
                    modifier = Modifier.height((viewport - 220.dp).coerceIn(200.dp, 420.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
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
                        placeholder = { Text("Type the paragraph above") },
                        // `singleLine`, not `minLines = 1` — and the difference is the whole bug.
                        // A minLines field *starts* at one line and grows as it fills, so by the
                        // fortieth word it would be ten lines tall and the paragraph would be back
                        // to a sliver. singleLine pins the height and scrolls sideways instead.
                        // Nothing is lost: newlines were never needed (matches() collapses
                        // whitespace), and the paragraph above is where progress is read now, so the
                        // field no longer has to be where he checks his own work.
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            autoCorrect = false,
                            capitalization = KeyboardCapitalization.None,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
                // Reserved height, so a line appearing and disappearing doesn't shove the buttons
                // under the typist's thumb mid-word.
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 28.dp)
                        .padding(start = 16.dp, end = 16.dp, top = 6.dp),
                ) {
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
                GradientButton(
                    text = confirmLabel,
                    enabled = solved,
                    onClick = onConfirm,
                    // Faded until it works. Disabled, the shared button is a solid grey pill that
                    // looks exactly like a live one — so the screen offered a button that did nothing
                    // when pressed, which reads as broken rather than as locked.
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .padding(top = 4.dp, bottom = 16.dp)
                        .alpha(if (solved) 1f else 0.45f),
                )
                // No "keep it on" button. It was a third way to do what the back arrow and the system
                // back gesture already do, and it sat in the middle of the empty space it helped
                // create. Leaving is not the thing this screen needs to make deliberate.
            }
        }
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("How this works") },
            text = {
                // Scrollable, because Material3's alert text is not — and this is the screen
                // whose one bug was text outgrowing the space it was given.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(blurb, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Text(detail, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Got it") } },
        )
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
                // Looser than the theme's body spacing (1.6 against its 1.44), because this is the
                // one block of text in the app nobody *reads* — it is scanned, word by word, for
                // the one that is highlighted. Air between the lines is what makes that findable.
                // Derived from the font size rather than a fixed sp value, so it still holds at
                // the owner's larger system font.
                style = MaterialTheme.typography.bodyLarge.copy(
                    lineHeight = MaterialTheme.typography.bodyLarge.fontSize * 1.6f,
                ),
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
