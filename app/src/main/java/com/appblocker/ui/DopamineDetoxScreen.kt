package com.appblocker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Profile ▸ Dopamine detox: a permanent rulebook — no program, no lecture. Standing
 *  orders across life domains, in the block screen's editorial poster language. The poster
 *  pieces themselves are shared with the situational guides — see GuideComponents.kt. */
@Composable
fun DopamineDetoxScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        EditorTopBar(title = "Dopamine detox", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            item {
                GuideHero(
                    kicker = "CLEAR RULES · NO END DATE",
                    title = "The Reset",
                    subtitle = "Not a program. A way to live. Start now.",
                )
            }

            // The philosophical frame: Buddhism's three marks of existence, each tied to
            // the rules it powers. Deliberately unnumbered — truths, not rules.
            item { GuideSectionLabel("I", "Three truths to hold") }
            items(MARKS.size) { i ->
                GuideMarkCard(MARKS[i], top = if (i == 0) 14.dp else 10.dp)
            }

            // One continuous numbering across every domain — a single rulebook.
            var number = 1
            SECTIONS.forEachIndexed { s, (label, rules) ->
                item { GuideSectionLabel(ROMAN[s + 2], label) }
                val first = number
                items(rules.size) { i ->
                    GuideRuleCard(
                        number = first + i,
                        item = rules[i],
                        top = if (i == 0) 14.dp else 10.dp,
                    )
                }
                number += rules.size
            }

            item { GuideSectionLabel(ROMAN[SECTIONS.size + 2], "When a craving hits") }
            item { GuideStepsCard(SOS) }

            item { GuideSectionLabel(ROMAN[SECTIONS.size + 3], "If you slip") }
            item { GuidePlainCard(SLIP, top = 14.dp) }

            item { GuideClosingPanel(CLOSING, onBack) }
        }
    }
}

// ---- Content (pure data — edit freely) ----

/** Buddhism's three marks of existence, each tied to the rules it explains. */
private val MARKS = listOf(
    GuideItem("Nothing lasts",
        "Everything that arises passes — including every craving and every urge. That's " +
            "the whole trick behind \"Ten minutes\": you don't defeat the wave, you watch " +
            "it end on its own. The feed hides this truth by always dangling a next " +
            "thing; sit still and you'll see every feeling close by itself.",
        term = "Anicca"),
    GuideItem("Chasing never satisfies",
        "Grasping at quick pleasure can't fill you, because wanting is the engine — the " +
            "scroll is unsatisfying by design, not because you found the wrong feed. So " +
            "the rules don't chase a better hit: they stop the chase. Feeds blocked, " +
            "autoplay off, boredom left unfilled — and the slow real rewards in " +
            "\"Replace the hit\" are the ones that actually land.",
        term = "Dukkha"),
    GuideItem("The craving is not you",
        "Thoughts and urges are passing events in your awareness, not orders from your " +
            "true self. That's why \"Name it\" works: the moment you say \"this is a " +
            "craving\", you're the one watching it instead of the one obeying it. And " +
            "it's why a slip never defines you — get up clean, restart the day.",
        term = "Anattā"),
)

private val SECTIONS: List<Pair<String, List<GuideItem>>> = listOf(
    // Every rule earns its place by attacking the scroll-reward loop directly:
    // cut the supply, seal the moments it sneaks in, break the reflex, or
    // replace the fake hit with a real one. No generic health advice.
    "Block the sources" to listOf(
        GuideItem("Feeds stay blocked",
            "Social feeds, Shorts, stories — blocked with no end date. Apply the Social " +
            "Detox template or Quick Block your own list, and leave it on."),
        GuideItem("Notifications: humans only",
            "Every app notification is a slot-machine lever pulled for you. Turn off " +
            "everything that isn't a live person."),
        GuideItem("Home screen: tools only",
            "Clock, camera, maps, messages. An icon you see is a hit you'll crave — " +
            "everything else goes in the drawer."),
        GuideItem("Log out everywhere",
            "Sign out of the blocked apps' websites too, and let word blocking catch " +
            "the back doors. A logged-in account is an open tap."),
        GuideItem("Turn off autoplay",
            "The \"next video\" reflex IS the loop. Kill autoplay in every app and " +
            "player that has the switch."),
    ),
    "Seal the openings" to listOf(
        GuideItem("The bed",
            "The phone charges in another room — scrolling in bed is the deepest hook " +
            "of all, and it ends today. Get an alarm clock."),
        GuideItem("The first 30 minutes",
            "No phone after waking. Give your brain its first hit of the day and it " +
            "chases that hit until midnight."),
        GuideItem("The last hour",
            "Screens off an hour before sleep. End the day off the drip, or the feed " +
            "is the last voice you hear every night."),
        GuideItem("Meals and the bathroom",
            "The two easiest scroll holes in the day — sealed. The phone stays in " +
            "another room for both."),
        GuideItem("Waiting",
            "Lines, elevators, red lights. The itch to reach for the pocket is the " +
            "detox working — wait it out, every time."),
        GuideItem("With people",
            "Phone off the table, always. Half-presence is the feed winning while " +
            "you're not even scrolling."),
    ),
    "Break the reflex" to listOf(
        GuideItem("One screen at a time",
            "The second screen exists to feed you hits during the slow parts. Watching? " +
            "Watch. Working? Work. Nothing in your hand."),
        GuideItem("Boredom stays unfilled",
            "Boredom is the withdrawal symptom — filling it resets the clock. Sit in " +
            "it and it fades faster every day."),
        GuideItem("Work in timed blocks, phone in another room",
            "A reachable phone drips micro-hits all day. Start a Pomodoro session and " +
            "put it physically out of reach until the timer says done."),
    ),
    "Porn & urges" to listOf(
        GuideItem("The pack stays on",
            "Porn is the strongest artificial hit there is — the loop's final boss. " +
            "The adult content pack stays on, guarded by Strict Mode, with no end date."),
        GuideItem("Close it at the first step",
            "Relapses don't start at the end — they start with \"just browsing\". The " +
            "moment you notice yourself drifting toward the edge, close the app and " +
            "leave the room. The first step is the cheapest one to refuse."),
        GuideItem("An urge is a wave, not an order",
            "Treat it exactly like a craving: name it, hold ten minutes, move your " +
            "body — push-ups, a walk, a cold face wash. It passes whether or not you " +
            "obey it, and every wave you ride out makes the next one smaller."),
        GuideItem("Never bored, alone and in bed",
            "That combination is where almost every relapse happens. If the urge hits " +
            "at night: lights on, out of bed, phone in the other room where it " +
            "already sleeps. Change the scene and the urge loses its stage."),
        GuideItem("If you fall, get up clean",
            "No spiral, no \"the day is ruined anyway\". Restart the day, note what " +
            "opened the door — tiredness, the hour, the app — and close that door " +
            "tomorrow. Shame feeds the loop; a plan starves it."),
    ),
    "Replace the hit" to listOf(
        GuideItem("Train every day",
            "Exercise is the strongest clean hit there is — the real version of the " +
            "rush the feed fakes. Thirty minutes minimum."),
        GuideItem("Walk with empty ears",
            "One walk a day, no podcast, no music. A quiet head is how your brain " +
            "relearns slow rewards."),
        GuideItem("Finish something daily",
            "Completion is a real hit the feed can never give you — it only ever " +
            "promises the next thing. Finish one thing fully, every day."),
        GuideItem("Read paper, twenty minutes",
            "Long attention is exactly what the feed dismantled. A paper book is the " +
            "gym where it grows back."),
        GuideItem("See people for real",
            "One face-to-face conversation or call a day. The feed is a substitute " +
            "for this — take the original instead."),
        GuideItem("Keep a hands hobby",
            "Cook, draw, fix, build. Slow reward, real skill, visible progress — " +
            "everything the scroll pretends to be."),
    ),
)

private val SOS = listOf(
    GuideItem("Name it", "Say it out loud: \"this is a craving, not a need\"."),
    GuideItem("Ten minutes", "You only have to hold for ten minutes. The wave falls on its own."),
    GuideItem("Move", "Push-ups, a walk, cold water on your face. Cravings can't survive motion."),
    GuideItem("Let the wall work", "Opened the app anyway? The block screen is there. Press Got it, walk away — that's a win."),
)

private val SLIP = GuideItem("",
    "Restart the day, never the streak. One bad hour doesn't cancel " +
        "the good days behind it — note what opened the door, close it " +
        "tomorrow, and carry on. Quitting because it wasn't perfect is " +
        "the only real failure.")

private const val CLOSING =
    "Your attention is the most valuable thing you own.\nSpend it on purpose."
