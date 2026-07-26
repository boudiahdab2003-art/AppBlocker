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
import kotlinx.coroutines.delay

/**
 * The app's one "are you really sure, and is it really you" gate: type a fresh random ~60-word
 * paragraph (pasting disabled) AND wait out a two-minute countdown before Confirm unlocks.
 * Regenerates the paragraph and resets the timer every time it opens.
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
    /** Label for the confirm button once the countdown is served — name the *wait* that follows,
     *  not the switch, because confirming only starts that wait. */
    confirmLabel: String,
    dismissLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val phrase = remember { (1..CHALLENGE_LEN).joinToString(" ") { CHALLENGE_WORDS.random() } }
    var input by remember { mutableStateOf("") }
    var remaining by remember { mutableStateOf(GATE_SECONDS) }
    LaunchedEffect(Unit) {
        while (remaining > 0) { delay(1000); remaining-- }
    }
    // Forgiving compare: a capital letter or stray double space must not silently keep the
    // button locked — typing all 60 words is the friction, not transcription perfection.
    val matched = input.trim().replace(Regex("\\s+"), " ").equals(phrase, ignoreCase = true)
    val ready = matched && remaining == 0

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
            // A no-op text toolbar removes the cut/copy/paste popup entirely, so the paragraph
            // must be hand-typed (soft keyboards have no paste key).
            CompositionLocalProvider(LocalTextToolbar provides NoPasteToolbar) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
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
            if (input.isNotBlank()) {
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
            text = if (remaining > 0) {
                "Wait ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}…"
            } else confirmLabel,
            enabled = ready,
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

/** Length of the random paragraph you must type, and the wait before Confirm unlocks. */
private const val CHALLENGE_LEN = 60
private const val GATE_SECONDS = 120

// Long everyday words on purpose: transcribing sixty of these takes real effort,
// which is the point of the gate.
private val CHALLENGE_WORDS = listOf(
    "wheelbarrow", "watermelon", "grasshopper", "candlestick", "thunderstorm", "countryside",
    "grandmother", "grandfather", "typewriter", "helicopter", "lighthouse", "caterpillar",
    "watercolor", "skyscraper", "playground", "toothbrush", "basketball", "strawberry",
    "blackboard", "sunflowers", "windmills", "cobblestone", "candlelight", "riverbank",
    "mountainside", "thunderbolt", "wintergreen", "summertime", "afternoon", "wilderness",
    "waterfall", "farmhouse", "fireplace", "bookshelves", "chalkboard", "clockmaker",
    "shopkeeper", "carpenter", "gardener", "landscape", "horizon", "telescope",
    "microscope", "keyboard", "notebook", "backpack", "raincoat", "umbrella",
    "staircase", "doorframe", "windowsill", "tablecloth", "silverware", "chandelier",
    "wallpaper", "floorboard", "greenhouse", "birdhouse", "treehouse", "footbridge",
    "crossroads", "signpost", "milestone", "cornerstone", "stepladder", "workbench",
    "toolboxes", "paintbrush", "sandcastle", "seashells", "driftwood", "riverbed",
)

/** Text toolbar that shows nothing — used to block paste (and copy/cut) on the challenge field. */
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
