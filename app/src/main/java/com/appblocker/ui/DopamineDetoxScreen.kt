package com.appblocker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appblocker.ui.theme.AppGradients

/** Profile ▸ Dopamine detox guide: a designed, scrollable education page — what the
 *  scroll-reward loop does, a 7-day reset plan, and how AppBlocker's tools fit each step. */
@Composable
fun DopamineDetoxScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        EditorTopBar(title = "Dopamine detox", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(52.dp).clip(RoundedCornerShape(16.dp))
                            .background(AppGradients.accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.SelfImprovement, contentDescription = null,
                            tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Reset your reward system",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground)
                        Text("A 7-day plan to make ordinary life feel good again.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    "A dopamine detox isn't about quitting pleasure — it's about taking a " +
                        "short break from the apps engineered to fire your reward system " +
                        "on demand, so that slower, real things (books, people, work, rest) " +
                        "get their flavor back.",
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = 26.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 18.dp),
                )
            }

            item { DetoxSectionHeader("What's happening in your brain") }
            items(BRAIN.size) { i -> DetoxCard(BRAIN[i], top = if (i == 0) 12.dp else 10.dp) }

            item { DetoxSectionHeader("Signs you need this") }
            items(SIGNS.size) { i -> DetoxCard(SIGNS[i], top = if (i == 0) 12.dp else 10.dp) }

            item { DetoxSectionHeader("The rules") }
            items(RULES.size) { i -> DetoxCard(RULES[i], top = if (i == 0) 12.dp else 10.dp) }

            item { DetoxSectionHeader("Your 7-day plan") }
            items(PLAN.size) { i ->
                DayCard(day = i + 1, entry = PLAN[i], top = if (i == 0) 12.dp else 10.dp)
            }

            item { DetoxSectionHeader("When a craving hits") }
            items(SOS.size) { i -> DetoxCard(SOS[i], top = if (i == 0) 12.dp else 10.dp) }

            item { DetoxSectionHeader("If you slip") }
            items(SLIP.size) { i -> DetoxCard(SLIP[i], top = if (i == 0) 12.dp else 10.dp) }

            item { DetoxSectionHeader("Use AppBlocker at every step") }
            items(TOOLS.size) { i -> DetoxCard(TOOLS[i], top = if (i == 0) 12.dp else 10.dp) }

            item {
                // Closing quote, in the block screen's editorial voice.
                Column(Modifier.fillMaxWidth().padding(top = 28.dp)) {
                    Text(
                        "“Your attention is the most valuable thing you own. Spend it on purpose.”",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Serif,
                        lineHeight = 28.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                GradientButton(
                    text = "I'm ready",
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 28.dp),
                )
            }
        }
    }
}

@Composable
private fun DetoxSectionHeader(title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 26.dp),
    ) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(AppGradients.accent))
        Spacer(Modifier.width(9.dp))
        Text(title, style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun DetoxCard(entry: Pair<String, String>, top: androidx.compose.ui.unit.Dp) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth()
            .padding(top = top)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), shape)
            .padding(14.dp),
    ) {
        Text(entry.first, style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Text(
            entry.second,
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = 21.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun DayCard(day: Int, entry: Pair<String, String>, top: androidx.compose.ui.unit.Dp) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth()
            .padding(top = top)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), shape)
            .padding(14.dp),
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(AppGradients.accent),
            contentAlignment = Alignment.Center,
        ) {
            Text("$day", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.first, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                entry.second,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 21.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// ---- Content (pure data — edit freely) ----

private val BRAIN = listOf(
    "Dopamine is a promise, not a pleasure" to
        "Dopamine isn't the feeling of enjoying something — it's the chemical of wanting, " +
        "the surge that says \"maybe the next thing is great\". Feeds, Shorts and endless " +
        "stories fire that promise on every swipe, which is why you keep scrolling long " +
        "after you stopped enjoying it.",
    "The slot-machine trick" to
        "Sometimes the next post is amazing, usually it's nothing. That unpredictable " +
        "maybe is the same mechanism a slot machine uses, and it's the strongest habit " +
        "loop known. Your phone isn't more fun than your life — it just pulls the lever " +
        "faster.",
    "Why everything else feels flat" to
        "After hours of fast, free hits, your brain recalibrates: reading, studying, even " +
        "conversation feel slow and gray by comparison. The good news — this is not " +
        "permanent. A short break resets the baseline, usually within days.",
)

private val SIGNS = listOf(
    "You know the signs" to
        "You open an app without deciding to. You scroll while eating, in bed, mid-" +
        "conversation. Twenty minutes vanish and you feel worse, not better. You reach " +
        "for the phone at the first second of boredom. Quiet feels uncomfortable. If a " +
        "few of these sound familiar, the loop is running you.",
)

private val RULES = listOf(
    "Not forever — just clean" to
        "This is a 7-day reset, not a lifetime ban. For one week, the hooked apps stay " +
        "closed. Afterwards you decide, deliberately, what earns its way back.",
    "Replace, don't just remove" to
        "An empty slot gets refilled by the old habit. Every blocked app needs a named " +
        "replacement: a book on the nightstand, a walk route, a workout, a person to " +
        "call. Decide the replacement before the craving, not during it.",
    "Make the phone boring, not absent" to
        "You don't need to live without a phone — you need a phone that does its job " +
        "and nothing more. Calls, messages, maps, music stay. The slot machines go.",
    "Boredom is the medicine" to
        "The itchy, restless feeling in a quiet moment is the reset actually happening. " +
        "Don't rush to fill it. It fades faster each day.",
)

private val PLAN = listOf(
    "See the damage" to
        "Change nothing yet. Open Insights tonight and look at the real numbers — " +
        "minutes, opens, what pulled you in. You can't fight what you can't see. " +
        "Write down the one app that costs you the most.",
    "Remove the hooks" to
        "Block the endless-feed apps with Quick Block (or apply the Social Detox " +
        "template). Turn on YouTube Shorts blocking and word blocking. Move what's " +
        "left off your home screen. Turn off every notification that isn't a person.",
    "Guard the openings" to
        "Cravings sneak in through moments: waking up, meals, the toilet, waiting in " +
        "line. Pick your three weakest moments and give each one a rule — the phone " +
        "stays in the pocket until you've done something else first.",
    "Replace the habit" to
        "Today the replacements start earning their keep. Read the book. Take the " +
        "walk. Call the friend. It will feel slower than the feed — that's the point. " +
        "Slow is what you're re-learning to enjoy.",
    "Go strict" to
        "Motivation dips mid-week, so stop relying on it: start a Strict Mode session " +
        "for the rest of the detox. Now the decision is already made, and future-you " +
        "can't renegotiate at 11 pm.",
    "Do the hard thing" to
        "Use a Pomodoro session and put the reclaimed hours into the thing you've been " +
        "avoiding — study, work, training. Deep effort is the strongest proof that " +
        "your attention belongs to you again.",
    "Lock it in" to
        "Look at Insights again and compare with Day 1. Decide what returns and on " +
        "what terms — daily limits, schedules, launch counts. Keep the blockers on. " +
        "A detox ends; the guardrails stay.",
)

private val SOS = listOf(
    "1 · Name it" to
        "Say it plainly: \"this is a craving, not a need\". Naming it moves it from " +
        "autopilot into daylight, where it's weaker.",
    "2 · Delay ten minutes" to
        "You don't have to resist forever — only for ten minutes. Cravings crest and " +
        "fall like a wave whether or not you feed them. Ride one out and you've " +
        "proven who's in charge.",
    "3 · Move your body" to
        "Stand up, leave the room, ten push-ups, a glass of water, one minute of slow " +
        "breathing. A craving lives in stillness; motion breaks its grip.",
    "4 · Let the block screen work" to
        "If you open the app anyway, the wall is there — that's not failure, that's " +
        "the system working. Press \"Got it\" and walk away with the win.",
)

private val SLIP = listOf(
    "A slip is data, not defeat" to
        "You scrolled for an hour on Day 4? That doesn't erase three good days. " +
        "Note what opened the door — tiredness, loneliness, a specific hour — and " +
        "close that door tomorrow. Restart the day, never the whole week. The only " +
        "real failure is quitting the reset because it wasn't perfect.",
)

private val TOOLS = listOf(
    "Quick Block + templates" to
        "One tap on the Social Detox template blocks the feed apps for the week. " +
        "Customize its app list to match your own worst offenders.",
    "Strict Mode" to
        "For the days when you don't trust future-you. While a Strict session runs, " +
        "the blocks can't be talked out of — and neither can the app's own settings.",
    "Timer & Pomodoro" to
        "Block everything for a focused hour, or run work/break cycles. Breaks open " +
        "the apps briefly; work rounds slam them shut again — automatically.",
    "Blocked words & the adult pack" to
        "Words you never want to see are caught in every app, and a caught word now " +
        "locks that app for 30 minutes. The adult pack guards hundreds of words " +
        "out of the box.",
    "Insights" to
        "Your before-and-after proof. Watch minutes and opens drop across the week — " +
        "seeing the line fall is its own reward, and this one is real.",
)
