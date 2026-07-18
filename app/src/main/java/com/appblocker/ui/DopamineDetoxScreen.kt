package com.appblocker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appblocker.ui.theme.AppGradients
import com.appblocker.ui.theme.softGlow

/** Profile ▸ Dopamine detox: a permanent rulebook — no program, no lecture. Standing
 *  orders across life domains, in the block screen's editorial poster language. */
@Composable
fun DopamineDetoxScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        EditorTopBar(title = "Dopamine detox", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            item { HeroPanel() }

            // One continuous numbering across every domain — a single rulebook.
            var number = 1
            SECTIONS.forEach { (label, rules) ->
                item { SectionLabel(label) }
                val first = number
                items(rules.size) { i ->
                    RuleCard(
                        number = first + i,
                        rule = rules[i],
                        top = if (i == 0) 14.dp else 10.dp,
                    )
                }
                number += rules.size
            }

            item { SectionLabel("When a craving hits") }
            item { CravingCard() }

            item { SectionLabel("If you slip") }
            item {
                Card(top = 14.dp) {
                    Text(
                        "Restart the day, never the streak. One bad hour doesn't cancel " +
                            "the good days behind it — note what opened the door, close it " +
                            "tomorrow, and carry on. Quitting because it wasn't perfect is " +
                            "the only real failure.",
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 21.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                    )
                }
            }

            item {
                // Closing panel: the same gradient-poster voice as the hero.
                val shape = RoundedCornerShape(28.dp)
                Column(
                    Modifier.fillMaxWidth().padding(top = 30.dp)
                        .softGlow(shape)
                        .clip(shape)
                        .background(AppGradients.accentVertical)
                        .padding(24.dp),
                ) {
                    Text(
                        "Your attention is the most valuable thing you own.\nSpend it on purpose.",
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = FontFamily.Serif,
                        lineHeight = 30.sp,
                        color = Color.White,
                    )
                }
                GradientButton(
                    text = "I'm ready",
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 28.dp),
                )
            }
        }
    }
}

// ---- Design pieces ----

@Composable
private fun HeroPanel() {
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier.fillMaxWidth()
            .softGlow(shape)
            .clip(shape)
            .background(AppGradients.accentVertical)
            .padding(24.dp),
    ) {
        Text(
            "CLEAR RULES · NO END DATE",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
            color = Color.White.copy(alpha = 0.7f),
        )
        Text(
            "The Reset",
            fontSize = 40.sp,
            lineHeight = 44.sp,
            fontFamily = FontFamily.Serif,
            color = Color.White,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            "Not a program. A way to live. Start now.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Black,
        letterSpacing = 2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 30.dp),
    )
}

@Composable
private fun Card(top: Dp, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth()
            .padding(top = top)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), shape)
            .padding(16.dp),
    ) { content() }
}

@Composable
private fun RuleCard(number: Int, rule: Pair<String, String>, top: Dp) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth()
            .padding(top = top)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), shape)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            number.toString().padStart(2, '0'),
            fontSize = 30.sp,
            fontFamily = FontFamily.Serif,
            style = TextStyle(brush = AppGradients.accent),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(rule.first, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(rule.second, style = MaterialTheme.typography.bodyMedium,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun CravingCard() {
    Card(top = 14.dp) {
        SOS.forEachIndexed { i, step ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = if (i == 0) 0.dp else 12.dp),
            ) {
                Text(
                    "${i + 1}",
                    fontSize = 26.sp,
                    fontFamily = FontFamily.Serif,
                    style = TextStyle(brush = AppGradients.accent),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(step.first, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text(step.second, style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ---- Content (pure data — edit freely) ----

private val SECTIONS: List<Pair<String, List<Pair<String, String>>>> = listOf(
    "Phone & screens" to listOf(
        "Feeds stay blocked" to
            "Social feeds, Shorts, stories — blocked, with no end date. Apply the Social " +
            "Detox template or Quick Block your own list, and leave it on.",
        "Notifications: humans only" to
            "Turn off every notification that isn't a live person talking to you. " +
            "Apps don't get to tap you on the shoulder.",
        "Home screen: tools only" to
            "Clock, camera, maps, messages. Everything else lives in the drawer, " +
            "out of sight.",
        "The phone sleeps outside the bedroom" to
            "It charges in another room, at the same time every night. Get an alarm clock.",
        "The first 30 minutes are yours" to
            "No phone from waking until half an hour has passed — no exceptions.",
        "No phone at meals, no phone in the bathroom" to
            "Eat at a table and taste the food. The two easiest scroll holes in the day — " +
            "sealed.",
        "One screen at a time" to
            "Watching something? Watch it. Working? Work. No second screen in your hand, ever.",
        "Boredom stays unfilled" to
            "Lines, elevators, quiet minutes — don't reach for the pocket. The itch fades " +
            "faster every day you let it.",
    ),
    "Sleep" to listOf(
        "Same time, every night" to
            "Fixed sleep and wake time, weekends included. The schedule is the medicine.",
        "Screens off an hour before bed" to
            "The last hour belongs to paper, people, or silence — never a glowing rectangle.",
        "A dark, cool room" to
            "Blackout the light, drop the temperature, and keep the bed for sleeping only.",
        "Caffeine stops after lunch" to
            "No coffee, tea, or energy drinks after mid-afternoon. Bad sleep is where " +
            "cravings breed.",
    ),
    "Morning" to listOf(
        "Water before anything" to
            "A full glass before the first task, the first coffee, the first word.",
        "Daylight within 30 minutes" to
            "Step outside or stand at a bright window. Real light starts the clock properly.",
        "Ten minutes of movement" to
            "Stretch, walk, push-ups — anything. The body wakes the head.",
        "Make the bed" to
            "First win of the day, done before the world gets a vote.",
        "Hardest task first" to
            "Do the thing you're avoiding before noon, while your discipline is freshest.",
    ),
    "Body" to listOf(
        "Train every day" to
            "Thirty minutes minimum: walk, run, lift, swim. Non-negotiable — weather is " +
            "not an excuse.",
        "One walk with nothing in your ears" to
            "No podcast, no music. Let your head be quiet once a day.",
        "Get sun" to
            "Outside daily, even briefly. Skin in daylight beats a screen's glow every time.",
        "Eat real food, at a table" to
            "Meals are cooked, sat down for, and finished. Never eat from boredom — " +
            "that's scrolling with a spoon.",
        "End the shower cold" to
            "Thirty seconds of cold at the end. A free, honest jolt — the real version of " +
            "what the feed fakes.",
    ),
    "Work & mind" to listOf(
        "Work in timed blocks" to
            "Set a Pomodoro session, put the phone in another room, and go. The block ends " +
            "when the timer says so, not when you feel like it.",
        "One thing at a time" to
            "One tab, one task, one goal per block. Half-attention is no attention.",
        "Plan tomorrow on paper" to
            "Three tasks, written the night before. You wake up already knowing.",
        "Read paper" to
            "Books and print, twenty minutes a day. Long attention is a muscle — this is " +
            "its gym.",
        "Finish something daily" to
            "One task fully done beats five half-done. Completion is the reward that " +
            "actually pays.",
    ),
    "People & world" to listOf(
        "One real conversation a day" to
            "Face to face or a call — not text. Minimum one, no hiding behind emojis.",
        "Phones off the table" to
            "When you're with people, the phone is in a pocket or another room. Presence " +
            "is the gift.",
        "One phone-free outing a week" to
            "Errand, hike, gym, visit — leave the phone at home. Feel the empty pocket " +
            "and keep walking.",
        "Keep a hands hobby" to
            "Something your hands do: cook, draw, fix, build, garden. Skills compound; " +
            "scrolling doesn't.",
    ),
)

private val SOS = listOf(
    "Name it" to "Say it out loud: \"this is a craving, not a need\".",
    "Ten minutes" to "You only have to hold for ten minutes. The wave falls on its own.",
    "Move" to "Push-ups, a walk, cold water on your face. Cravings can't survive motion.",
    "Let the wall work" to "Opened the app anyway? The block screen is there. Press Got it, walk away — that's a win.",
)
