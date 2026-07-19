package com.appblocker.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Waves
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/** How a guide section renders. */
enum class GuideKind { TRUTHS, RULES, STEPS, PLAIN }

/** One entry in a section. [term] is the gradient serif-italic label used by TRUTHS cards;
 *  a blank [title] renders body-only (a plain paragraph card). */
data class GuideItem(val title: String, val body: String, val term: String? = null)

data class GuideSection(val label: String, val kind: GuideKind, val items: List<GuideItem>)

/** A full guide for one hard moment, rendered by ScenarioGuide in the block screen's poster style. */
data class Scenario(
    val id: String,
    val hubTitle: String,
    val hubSubtitle: String,
    val icon: ImageVector,
    val colors: List<Color>,
    val kicker: String,
    val title: String,
    val subtitle: String,
    val sections: List<GuideSection>,
    val closing: String,
)

private fun rule(title: String, body: String) = GuideItem(title, body)

// NOTE: The Dopamine detox guide is deliberately kept OUTSIDE this hub, as its own
// standalone screen (DopamineDetoxScreen.kt) reached from its own Profile row. This list
// holds the situational guides only.
val SCENARIOS: List<Scenario> = listOf(

    // 1 ─────────────────────────────────────────────────────────── I relapsed
    Scenario(
        id = "relapse",
        hubTitle = "I relapsed",
        hubSubtitle = "Right after a slip — stop the spiral, reset clean.",
        icon = Icons.Filled.Refresh,
        colors = listOf(Color(0xFFFB7185), Color(0xFFE11D48)),
        kicker = "A SLIP, NOT A COLLAPSE",
        title = "Get Up Clean",
        subtitle = "The next ten minutes matter more than what just happened.",
        sections = listOf(
            GuideSection("Right now", GuideKind.STEPS, listOf(
                GuideItem("Stand up", "Leave the room you're in. Physically change the scene before you do anything else."),
                GuideItem("Cold water", "Splash your face, or thirty seconds of cold at the end of a shower. It resets the nervous system fast."),
                GuideItem("Move your body", "Twenty push-ups, a brisk walk, anything hard for two minutes. Burn off the chemical fog."),
                GuideItem("Say it straight", "“I slipped. It's over now.” No lie, no drama — name it and close it."),
            )),
            GuideSection("Stop the spiral", GuideKind.RULES, listOf(
                rule("One slip is not the day", "The shame spiral — “I already ruined it” — is what turns one slip into a lost week. Refuse the second thought."),
                rule("Don't re-live it", "Close the tabs, and then stop replaying it in your head. Re-living it is re-feeding it."),
                rule("No grand promises", "Don't vow “never again” — that sets up the next fall. Just win the next hour, then the next one."),
            )),
            GuideSection("Reset the day", GuideKind.RULES, listOf(
                rule("Do one real thing", "Right now, do something that matters — a workout, a task, a call. Completion is the antidote to the empty feeling."),
                rule("Get around people", "Isolation is the soil this grows in. Text a friend, sit with family, go outside. Presence breaks the trance."),
                rule("Sleep on schedule", "Don't stay up spiraling. Phone in the other room, lights out on time — tomorrow you start clean."),
            )),
            GuideSection("Prevent the next one", GuideKind.RULES, listOf(
                rule("Find the trigger", "Tiredness? A certain hour? Boredom? Alone in bed? Name what opened the door today, and plan around it tomorrow."),
                rule("Keep the walls up", "Adult pack on, Strict Mode running. Don't lower a single guard while you feel strong — you're protecting future-you."),
                rule("The urge is a wave", "Next time it rises: name it, hold ten minutes, move. It always passes — feeding it just teaches it to come back louder."),
            )),
        ),
        closing = "You are not your worst ten minutes.\nYou are what you do next.",
    ),

    // 3 ─────────────────────────────────────────────────────────── Can't focus
    Scenario(
        id = "focus",
        hubTitle = "Can't focus",
        hubSubtitle = "Time to study or work and your mind won't settle.",
        icon = Icons.Filled.School,
        colors = listOf(Color(0xFF14B8A6), Color(0xFF22C55E)),
        kicker = "STOP WAITING TO FEEL READY",
        title = "Just Begin",
        subtitle = "Focus follows action. You don't think your way in — you start, and the mind catches up.",
        sections = listOf(
            GuideSection("Clear the deck", GuideKind.RULES, listOf(
                rule("Phone in another room", "Not face-down on the desk — another room. A visible phone taxes your attention even when you never touch it."),
                rule("One tab, one task", "Close everything else. Decide the single thing you're doing this block and hide the rest."),
                rule("Turn the walls on", "Start a Pomodoro or Strict session before you begin. Make the distraction impossible, not just discouraged."),
            )),
            GuideSection("The 2-minute start", GuideKind.STEPS, listOf(
                GuideItem("Shrink it", "Don't “study for the exam”. Open the book to page one. Write one sentence. Make the first step embarrassingly small."),
                GuideItem("Set a timer", "Twenty-five minutes, one task. You're not committing to hours — just to the timer."),
                GuideItem("Start ugly", "Messy notes, rough first draft, slow reading. Momentum beats quality at the start — you can fix ugly, you can't fix blank."),
                GuideItem("Ride the warm-up", "The first ten minutes feel like friction. Push through them — that's the on-ramp, not the road."),
            )),
            GuideSection("Work in blocks", GuideKind.RULES, listOf(
                rule("One block at a time", "Twenty-five to fifty minutes of single-tasking, then a real break. Sprints, not a marathon."),
                rule("Break away from screens", "In the break, stand up, look far, move. Don't reward focus with a scroll — it undoes the reset."),
                rule("Protect the deep hours", "Do the hardest work when your mind is freshest — usually the morning. Guard that window."),
            )),
            GuideSection("When your mind wanders", GuideKind.STEPS, listOf(
                GuideItem("Notice, don't judge", "Wandering is normal, not failure. The skill is catching it — not never drifting."),
                GuideItem("Park the thought", "Jot the distraction on a scrap of paper — “reply to X”, “check Y later” — and return. The paper holds it so your head doesn't."),
                GuideItem("Back to one line", "Re-read the last sentence you wrote or read. Re-entry is easy from a single concrete point."),
            )),
        ),
        closing = "Discipline is doing it before you feel like it.\nStart, and the feeling arrives.",
    ),

    // 4 ─────────────────────────────────────────────────────────── Feeling lazy
    Scenario(
        id = "lazy",
        hubTitle = "Feeling lazy",
        hubSubtitle = "Low energy, no motivation — get moving anyway.",
        icon = Icons.Filled.Bolt,
        colors = listOf(Color(0xFFFB923C), Color(0xFFF97316)),
        kicker = "MOTION BEFORE MOTIVATION",
        title = "Move First",
        subtitle = "You won't feel like it and then act. You act, and the feeling shows up.",
        sections = listOf(
            GuideSection("The 5-minute rule", GuideKind.STEPS, listOf(
                GuideItem("Pick one thing", "The smallest useful task. Not the whole list — one thing."),
                GuideItem("Commit to five minutes", "Tell yourself you can quit after five. You almost never will — starting is the hard part, not continuing."),
                GuideItem("Just start it", "Put your shoes on. Open the doc. Pick up the one dish. Begin the physical motion before the mind argues."),
                GuideItem("Let momentum carry", "Five minutes in, you're moving. Ride it as far as it goes; stop without guilt when it fades."),
            )),
            GuideSection("Lower the bar", GuideKind.RULES, listOf(
                rule("Half is not nothing", "A short workout beats a skipped one. Ten minutes of work beats zero. Shrink the task until it's impossible to refuse."),
                rule("Make starting stupidly easy", "Clothes laid out, book open, workspace clear. Remove every small friction between you and the first move."),
                rule("Don't trust the mood", "Waiting to “feel motivated” is the trap. Mood is weather; action is the only thing you actually control."),
            )),
            GuideSection("Build momentum", GuideKind.RULES, listOf(
                rule("Stack one win first", "Make the bed, drink water, two minutes of movement. A tiny early win primes the bigger ones."),
                rule("Body before brain", "Move, get daylight, get blood flowing. Lazy is often just under-moved and under-lit, not truly tired."),
                rule("Guard against the scroll", "Laziness plus a phone equals hours gone. Turn Quick Block on when your willpower is low — decide once, coast on it."),
            )),
        ),
        closing = "You don't need motivation.\nYou need to start. The rest follows.",
    ),

    // 5 ─────────────────────────────────────────────────────────── Can't sleep
    Scenario(
        id = "sleep",
        hubTitle = "Can't sleep",
        hubSubtitle = "Late-night phone, racing mind — put the day down.",
        icon = Icons.Filled.Bedtime,
        colors = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6)),
        kicker = "THE PHONE IS THE PROBLEM",
        title = "Put It Down",
        subtitle = "The scroll doesn't relax you into sleep — it robs the sleep you were about to get.",
        sections = listOf(
            GuideSection("Put the phone to bed", GuideKind.RULES, listOf(
                rule("It charges in another room", "The single biggest change. In-bed scrolling wrecks sleep and it's where the worst urges live. Get an alarm clock."),
                rule("Screens off an hour before", "Blue light and endless feeds tell your brain it's daytime. End the day off the drip."),
                rule("No “just five minutes”", "There is no five-minute scroll at night — it's always an hour. Don't open the door at all."),
            )),
            GuideSection("Wind down", GuideKind.STEPS, listOf(
                GuideItem("Dim everything", "Lights low, room cool and dark. Signal the body that the day is closing."),
                GuideItem("Off-load the head", "Racing thoughts? Write tomorrow's worries and to-dos on paper. On the page, out of the mind."),
                GuideItem("Slow the breath", "Breathe out longer than you breathe in — four in, six out — for a couple of minutes. It downshifts the nervous system."),
                GuideItem("Read something dull", "A paper book, nothing exciting. Boredom is your friend here — let it pull you under."),
            )),
            GuideSection("If you're still awake", GuideKind.RULES, listOf(
                rule("Don't watch the clock", "Turn it away. Doing the math on lost sleep only wakes you up more."),
                rule("Get up, don't scroll", "Awake for 20 minutes? Leave the bed, sit somewhere dim and boring, return when sleepy. Never reach for the phone."),
                rule("One bad night isn't a crisis", "Pressure to sleep keeps you awake. You'll be fine tomorrow — let go of forcing it."),
            )),
        ),
        closing = "Guard your sleep like a foundation —\nbecause everything else is built on it.",
    ),

    // 6 ─────────────────────────────────────────────────────────── Urge to scroll
    Scenario(
        id = "scroll",
        hubTitle = "Urge to scroll",
        hubSubtitle = "The pull to grab your phone right now.",
        icon = Icons.Filled.Waves,
        colors = listOf(Color(0xFFF0598A), Color(0xFFB5179E)),
        kicker = "A WAVE, NOT AN ORDER",
        title = "Ride It Out",
        subtitle = "The urge peaks and falls on its own in minutes — whether or not you feed it.",
        sections = listOf(
            GuideSection("Ride the wave", GuideKind.STEPS, listOf(
                GuideItem("Name it", "“This is an urge, not a need.” Saying it moves you from being the urge to watching it."),
                GuideItem("Wait ten minutes", "You don't have to resist forever — just ten minutes. The wave crests and falls if you don't feed it."),
                GuideItem("Move your body", "Stand up, leave the room, ten push-ups, cold water. An urge lives in stillness; motion breaks it."),
                GuideItem("Notice it pass", "It shrank, didn't it? Every wave you ride out teaches your brain the urge isn't an emergency."),
            )),
            GuideSection("Make it harder", GuideKind.RULES, listOf(
                rule("Put the phone away", "Out of your hand, out of the room if you can. Distance buys you the ten minutes you need."),
                rule("Turn Quick Block on", "Flip the Quick Block tile the moment the urge hits. Make the app impossible, not just tempting."),
                rule("Kill the entry points", "Feeds off the home screen, notifications off. Most scrolling starts with a lever someone else pulled."),
            )),
            GuideSection("Replace it", GuideKind.RULES, listOf(
                rule("Have a default ready", "Decide beforehand what you'll do instead — a book on the desk, a walk, a set of push-ups. Don't negotiate mid-urge."),
                rule("Fill the real gap", "The urge often just means bored or tired. Feed the real need — rest, movement, a person — not the fake one."),
            )),
        ),
        closing = "Every urge you don't obey makes the next one quieter.\nYou're training your brain either way.",
    ),

    // 7 ─────────────────────────────────────────────────────────── Overwhelmed
    Scenario(
        id = "overwhelmed",
        hubTitle = "Overwhelmed",
        hubSubtitle = "Too much at once, mind spinning — find solid ground.",
        icon = Icons.Filled.Spa,
        colors = listOf(Color(0xFF38BDF8), Color(0xFF3B82F6)),
        kicker = "BODY FIRST, THEN THE PROBLEM",
        title = "Slow It Down",
        subtitle = "You can't think clearly in a spinning body. Settle the system first, then face the list.",
        sections = listOf(
            GuideSection("Slow the body first", GuideKind.STEPS, listOf(
                GuideItem("Breathe out long", "Four counts in, six counts out, five times. A long exhale is the fastest off-switch for panic."),
                GuideItem("Feet on the floor", "Feel your feet, the chair, three things you can see. Come out of your head and into the room."),
                GuideItem("One glass of water", "Drink it slowly. A tiny physical act interrupts the spin and gives your hands something to do."),
            )),
            GuideSection("Empty your head", GuideKind.RULES, listOf(
                rule("Brain-dump on paper", "Every worry and task out of your head onto a page. The mind spins to avoid forgetting; the paper holds it so you can stop."),
                rule("Pick one next action", "Not the whole mountain — the single next physical step. Overwhelm is just too many things held at once."),
                rule("Do that one thing", "Complete it, cross it off, then choose the next. Momentum shrinks the pile faster than planning does."),
            )),
            GuideSection("Shrink the problem", GuideKind.RULES, listOf(
                rule("Sort: yours or not", "Half of overwhelm is carrying what you can't control. Name what's actually yours to do, and set the rest down."),
                rule("Now, later, or never", "Most “urgent” things aren't. Triage into now / later / never and delete ruthlessly."),
                rule("Step away from the feed", "Scrolling while overwhelmed pours noise on the fire. Turn the blockers on and give your mind quiet to reset."),
            )),
        ),
        closing = "You don't have to carry it all at once.\nOne breath, one page, one next thing.",
    ),
)
