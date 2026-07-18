package com.appblocker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appblocker.ui.theme.AppGradients
import com.appblocker.ui.theme.softGlow

/** Profile ▸ Dopamine detox: a rulebook, not a lecture — whole-life orders in the block
 *  screen's editorial poster language (serif numerals, uppercase kickers, gradient panels). */
@Composable
fun DopamineDetoxScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        EditorTopBar(title = "Dopamine detox", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            item { HeroPanel() }

            item { SectionLabel("The 10 rules") }
            items(RULES.size) { i ->
                RuleCard(number = i + 1, rule = RULES[i], top = if (i == 0) 14.dp else 10.dp)
            }

            item { SectionLabel("Every day, same skeleton") }
            items(DAY_SHAPE.size) { i ->
                DayShapeCard(DAY_SHAPE[i], top = if (i == 0) 14.dp else 10.dp)
            }

            item { SectionLabel("The 7 days") }
            item {
                Column(Modifier.padding(top = 14.dp)) {
                    PLAN.forEachIndexed { i, day ->
                        TimelineDay(day = i + 1, entry = day, last = i == PLAN.lastIndex)
                    }
                }
            }

            item { SectionLabel("When a craving hits") }
            item { CravingCard() }

            item { SectionLabel("If you slip") }
            item {
                Card(top = 14.dp) {
                    Text(
                        "Restart the day, never the week. One bad hour doesn't cancel " +
                            "three good days — note what opened the door, close it " +
                            "tomorrow, and carry on. Quitting the reset because it wasn't " +
                            "perfect is the only real failure.",
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
            "7 DAYS · CLEAR RULES",
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
            "Follow the rules. Don't negotiate with yourself.",
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
private fun DayShapeCard(entry: Triple<ImageVector, String, String>, top: Dp) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth()
            .padding(top = top)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), shape)
            .padding(16.dp),
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(AppGradients.accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(entry.first, contentDescription = null, tint = Color.White,
                modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.second, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(entry.third, style = MaterialTheme.typography.bodyMedium,
                lineHeight = 21.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun TimelineDay(day: Int, entry: Pair<String, List<String>>, last: Boolean) {
    // IntrinsicSize.Min lets the left rail match the day's content height, so the
    // connector line can stretch between the numbered circles.
    Row(Modifier.height(IntrinsicSize.Min)) {
        // Left rail: numbered gradient circle + connector to the next day.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(34.dp).fillMaxHeight(),
        ) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(AppGradients.accent),
                contentAlignment = Alignment.Center,
            ) {
                Text("$day", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = Color.White)
            }
            if (!last) {
                Box(
                    Modifier.width(2.dp).weight(1f)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 18.dp)) {
            Text(entry.first, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 5.dp))
            entry.second.forEach { order ->
                Row(Modifier.padding(top = 6.dp)) {
                    Text("•", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(order, style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 21.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f))
                }
            }
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

private val RULES = listOf(
    "No feeds for 7 days" to
        "Social feeds, Shorts, stories — blocked today, all week. No exceptions, no \"just checking\".",
    "The phone sleeps outside the bedroom" to
        "It charges in another room. Get an alarm clock if you need one.",
    "The first 30 minutes are yours" to
        "No phone from waking until half an hour has passed. Water, light, movement first.",
    "No phone at meals" to
        "Eat. Taste the food. Talk to whoever is there. The phone stays in another room.",
    "Walk without headphones" to
        "At least one walk a day with nothing in your ears. Let your head be quiet.",
    "One screen at a time" to
        "Watching something? Then watch it. No second screen in your hand, ever.",
    "Boredom stays unfilled" to
        "Waiting in line, sitting on the toilet, a quiet minute — don't reach for the phone. Wait it out.",
    "Move every day" to
        "Thirty minutes minimum: walk, run, lift, swim. Non-negotiable, weather is not an excuse.",
    "Only real rewards" to
        "Sun, training, people, finished work, a good meal. If it comes from a screen, it doesn't count this week.",
    "Weekends are not a loophole" to
        "Same rules on Saturday and Sunday. The reset doesn't take days off, and neither do the apps.",
)

private val DAY_SHAPE = listOf(
    Triple(Icons.Filled.WbSunny, "Morning",
        "Water, daylight, and movement before any screen. Make the bed. Then — and only " +
            "then — the phone, standing up, for essentials only."),
    Triple(Icons.Filled.WorkOutline, "Day",
        "Work in timed blocks with the phone in another room. Between blocks: stand up, " +
            "stretch, look out a window — not at a screen."),
    Triple(Icons.Filled.MenuBook, "Evening",
        "Screens off one hour before bed. Paper book, conversation, stretching, tomorrow's " +
            "plan on real paper."),
    Triple(Icons.Filled.Bedtime, "Night",
        "Phone on the charger — in the other room — at the same time every night. Sleep is " +
            "the strongest reset there is."),
)

private val PLAN = listOf(
    "Block and strip" to listOf(
        "Apply the Social Detox template (or Quick Block your own worst apps).",
        "Turn on YouTube Shorts blocking and word blocking.",
        "Turn off every notification that isn't a live human being.",
        "Home screen: tools only — clock, maps, camera, messages.",
        "Tell one person you're doing this week.",
    ),
    "Take the morning" to listOf(
        "Alarm clock bought or borrowed; phone charges outside the bedroom tonight.",
        "First 30 minutes: water, daylight, ten minutes of movement.",
        "No phone before you've done all three.",
    ),
    "Silence the gaps" to listOf(
        "All meals phone-free, starting with breakfast.",
        "One walk, no headphones.",
        "Every wait — line, elevator, toilet — stays phone-free. Count them if it helps.",
    ),
    "Go strict" to listOf(
        "Start a Strict Mode session covering the rest of the week — decide once, stop deciding.",
        "One hard task today inside a Pomodoro session: study, work, or training.",
    ),
    "Rebuild the evening" to listOf(
        "Screens off one hour before bed, starting tonight.",
        "Replace with a paper book, people, or stretching.",
        "Write tomorrow's plan on paper before sleep.",
    ),
    "Leave it home" to listOf(
        "Half a day out — errand, hike, visit, gym — with the phone left at home.",
        "Notice the reflex reaches for a pocket that's empty. Let it.",
    ),
    "Lock it in" to listOf(
        "Open Insights and compare with Day 1.",
        "Decide what returns and on what terms: daily limits, schedules, launch counts.",
        "Keep the blocks, the phone-free mornings and meals, and the bedroom rule — permanently.",
    ),
)

private val SOS = listOf(
    "Name it" to "Say it out loud: \"this is a craving, not a need\".",
    "Ten minutes" to "You only have to hold for ten minutes. The wave falls on its own.",
    "Move" to "Push-ups, a walk, cold water on your face. Cravings can't survive motion.",
    "Let the wall work" to "Opened the app anyway? The block screen is there. Press Got it, walk away — that's a win.",
)
