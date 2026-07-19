package com.appblocker.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.font.FontStyle
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

            // The philosophical frame: Buddhism's three marks of existence, each tied to
            // the rules it powers. Deliberately unnumbered — truths, not rules.
            item { SectionLabel("I", "Three truths to hold") }
            items(MARKS.size) { i ->
                MarkCard(MARKS[i], top = if (i == 0) 14.dp else 10.dp)
            }

            // One continuous numbering across every domain — a single rulebook.
            var number = 1
            SECTIONS.forEachIndexed { s, (label, rules) ->
                item { SectionLabel(ROMAN[s + 2], label) }
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

            item { SectionLabel(ROMAN[SECTIONS.size + 2], "When a craving hits") }
            item { CravingCard() }

            item { SectionLabel(ROMAN[SECTIONS.size + 3], "If you slip") }
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
                        "REMEMBER",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    Text(
                        "Your attention is the most valuable thing you own.\nSpend it on purpose.",
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = FontFamily.Serif,
                        lineHeight = 30.sp,
                        color = Color.White,
                        modifier = Modifier.padding(top = 8.dp),
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

/** Editorial section header: roman numeral in the accent gradient, uppercase title,
 *  and a hairline running to the right edge. */
@Composable
private fun SectionLabel(numeral: String, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 30.dp),
    ) {
        Text(
            numeral,
            fontSize = 17.sp,
            fontFamily = FontFamily.Serif,
            style = TextStyle(brush = AppGradients.accent),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.weight(1f).height(1.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        )
    }
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
        // Fixed-width numeral column so every rule's text starts on the same line.
        Box(Modifier.width(46.dp)) {
            Text(
                number.toString().padStart(2, '0'),
                fontSize = 30.sp,
                fontFamily = FontFamily.Serif,
                style = TextStyle(brush = AppGradients.accent),
            )
        }
        Spacer(Modifier.width(6.dp))
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
private fun MarkCard(mark: Triple<String, String, String>, top: Dp) {
    val shape = RoundedCornerShape(20.dp)
    // The truths get the accent-gradient border — set apart from the rules below.
    val accentBorder = BorderStroke(
        1.dp,
        androidx.compose.ui.graphics.Brush.linearGradient(
            listOf(
                AppGradients.AccentStart.copy(alpha = 0.55f),
                AppGradients.AccentEnd.copy(alpha = 0.55f),
            )
        ),
    )
    Column(
        Modifier.fillMaxWidth()
            .padding(top = top)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(accentBorder, shape)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                mark.first,
                fontSize = 22.sp,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                style = TextStyle(brush = AppGradients.accent),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                mark.second.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        Text(
            mark.third,
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = 21.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun CravingCard() {
    Card(top = 14.dp) {
        SOS.forEachIndexed { i, step ->
            if (i > 0) {
                Box(
                    Modifier.fillMaxWidth().height(1.dp).padding(horizontal = 2.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(
                    top = if (i == 0) 0.dp else 12.dp,
                    bottom = if (i == SOS.lastIndex) 0.dp else 12.dp,
                ),
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

/** Section numerals: index 1..n as the guide reads top to bottom. */
private val ROMAN = listOf("", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX")

/** Buddhism's three marks of existence, each tied to the rules it explains. */
private val MARKS = listOf(
    Triple("Anicca", "Nothing lasts",
        "Everything that arises passes — including every craving and every urge. That's " +
            "the whole trick behind \"Ten minutes\": you don't defeat the wave, you watch " +
            "it end on its own. The feed hides this truth by always dangling a next " +
            "thing; sit still and you'll see every feeling close by itself."),
    Triple("Dukkha", "Chasing never satisfies",
        "Grasping at quick pleasure can't fill you, because wanting is the engine — the " +
            "scroll is unsatisfying by design, not because you found the wrong feed. So " +
            "the rules don't chase a better hit: they stop the chase. Feeds blocked, " +
            "autoplay off, boredom left unfilled — and the slow real rewards in " +
            "\"Replace the hit\" are the ones that actually land."),
    Triple("Anattā", "The craving is not you",
        "Thoughts and urges are passing events in your awareness, not orders from your " +
            "true self. That's why \"Name it\" works: the moment you say \"this is a " +
            "craving\", you're the one watching it instead of the one obeying it. And " +
            "it's why a slip never defines you — get up clean, restart the day."),
)

private val SECTIONS: List<Pair<String, List<Pair<String, String>>>> = listOf(
    // Every rule earns its place by attacking the scroll-reward loop directly:
    // cut the supply, seal the moments it sneaks in, break the reflex, or
    // replace the fake hit with a real one. No generic health advice.
    "Block the sources" to listOf(
        "Feeds stay blocked" to
            "Social feeds, Shorts, stories — blocked with no end date. Apply the Social " +
            "Detox template or Quick Block your own list, and leave it on.",
        "Notifications: humans only" to
            "Every app notification is a slot-machine lever pulled for you. Turn off " +
            "everything that isn't a live person.",
        "Home screen: tools only" to
            "Clock, camera, maps, messages. An icon you see is a hit you'll crave — " +
            "everything else goes in the drawer.",
        "Log out everywhere" to
            "Sign out of the blocked apps' websites too, and let word blocking catch " +
            "the back doors. A logged-in account is an open tap.",
        "Turn off autoplay" to
            "The \"next video\" reflex IS the loop. Kill autoplay in every app and " +
            "player that has the switch.",
    ),
    "Seal the openings" to listOf(
        "The bed" to
            "The phone charges in another room — scrolling in bed is the deepest hook " +
            "of all, and it ends today. Get an alarm clock.",
        "The first 30 minutes" to
            "No phone after waking. Give your brain its first hit of the day and it " +
            "chases that hit until midnight.",
        "The last hour" to
            "Screens off an hour before sleep. End the day off the drip, or the feed " +
            "is the last voice you hear every night.",
        "Meals and the bathroom" to
            "The two easiest scroll holes in the day — sealed. The phone stays in " +
            "another room for both.",
        "Waiting" to
            "Lines, elevators, red lights. The itch to reach for the pocket is the " +
            "detox working — wait it out, every time.",
        "With people" to
            "Phone off the table, always. Half-presence is the feed winning while " +
            "you're not even scrolling.",
    ),
    "Break the reflex" to listOf(
        "One screen at a time" to
            "The second screen exists to feed you hits during the slow parts. Watching? " +
            "Watch. Working? Work. Nothing in your hand.",
        "Boredom stays unfilled" to
            "Boredom is the withdrawal symptom — filling it resets the clock. Sit in " +
            "it and it fades faster every day.",
        "Work in timed blocks, phone in another room" to
            "A reachable phone drips micro-hits all day. Start a Pomodoro session and " +
            "put it physically out of reach until the timer says done.",
    ),
    "Porn & urges" to listOf(
        "The pack stays on" to
            "Porn is the strongest artificial hit there is — the loop's final boss. " +
            "The adult content pack stays on, guarded by Strict Mode, with no end date.",
        "Close it at the first step" to
            "Relapses don't start at the end — they start with \"just browsing\". The " +
            "moment you notice yourself drifting toward the edge, close the app and " +
            "leave the room. The first step is the cheapest one to refuse.",
        "An urge is a wave, not an order" to
            "Treat it exactly like a craving: name it, hold ten minutes, move your " +
            "body — push-ups, a walk, a cold face wash. It passes whether or not you " +
            "obey it, and every wave you ride out makes the next one smaller.",
        "Never bored, alone and in bed" to
            "That combination is where almost every relapse happens. If the urge hits " +
            "at night: lights on, out of bed, phone in the other room where it " +
            "already sleeps. Change the scene and the urge loses its stage.",
        "If you fall, get up clean" to
            "No spiral, no \"the day is ruined anyway\". Restart the day, note what " +
            "opened the door — tiredness, the hour, the app — and close that door " +
            "tomorrow. Shame feeds the loop; a plan starves it.",
    ),
    "Replace the hit" to listOf(
        "Train every day" to
            "Exercise is the strongest clean hit there is — the real version of the " +
            "rush the feed fakes. Thirty minutes minimum.",
        "Walk with empty ears" to
            "One walk a day, no podcast, no music. A quiet head is how your brain " +
            "relearns slow rewards.",
        "Finish something daily" to
            "Completion is a real hit the feed can never give you — it only ever " +
            "promises the next thing. Finish one thing fully, every day.",
        "Read paper, twenty minutes" to
            "Long attention is exactly what the feed dismantled. A paper book is the " +
            "gym where it grows back.",
        "See people for real" to
            "One face-to-face conversation or call a day. The feed is a substitute " +
            "for this — take the original instead.",
        "Keep a hands hobby" to
            "Cook, draw, fix, build. Slow reward, real skill, visible progress — " +
            "everything the scroll pretends to be.",
    ),
)

private val SOS = listOf(
    "Name it" to "Say it out loud: \"this is a craving, not a need\".",
    "Ten minutes" to "You only have to hold for ten minutes. The wave falls on its own.",
    "Move" to "Push-ups, a walk, cold water on your face. Cravings can't survive motion.",
    "Let the wall work" to "Opened the app anyway? The block screen is there. Press Got it, walk away — that's a win.",
)
