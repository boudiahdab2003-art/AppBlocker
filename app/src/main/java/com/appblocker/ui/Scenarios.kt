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

/** One entry in a section. [term] is the gradient serif-italic label used by TRUTHS cards
 *  (e.g. a thinker's name); a blank [title] renders body-only (a plain paragraph card). */
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
private fun truth(name: String, headline: String, body: String) = GuideItem(headline, body, term = name)

// NOTE: The Dopamine detox guide is deliberately kept OUTSIDE this hub, as its own
// standalone screen (DopamineDetoxScreen.kt) reached from its own Profile row. This list
// holds the situational guides only. Each opens with a short philosophical frame (existential
// / evolutionary) before the practical steps — depth first, then do.
val SCENARIOS: List<Scenario> = listOf(

    // 1 ─────────────────────────────────────────────────────────── I relapsed
    Scenario(
        id = "relapse",
        hubTitle = "I relapsed",
        hubSubtitle = "Right after a slip — stop the spiral, start clean.",
        icon = Icons.Filled.Refresh,
        colors = listOf(Color(0xFFFB7185), Color(0xFFE11D48)),
        kicker = "A SLIP, NOT A COLLAPSE",
        title = "Get Up Clean",
        subtitle = "What you do in the next ten minutes matters more than what you just did.",
        sections = listOf(
            GuideSection("Hold this", GuideKind.TRUTHS, listOf(
                truth("Sartre", "You are your acts", "There is no hidden “real you” that the slip exposed. Existence comes first: you are nothing but the sum of what you do — and the next act is always yours to choose. The relapse is behind you now; who you are is being decided by the next ten minutes, not the last ten."),
                truth("Darwin", "Ancient wiring, modern trap", "The urge that pulled you under is old survival machinery — built to chase reward, never built for a screen engineered to trip it on command. It isn't your character; it's a reflex from another world. You are not obliged to obey a hundred-thousand-year-old instinct that never met an algorithm."),
                truth("Epictetus", "Only the next move is yours", "The Stoics split the world in two: what is up to you and what isn't. The slip is already done — outside your power now, not worth another ounce of force. What IS up to you is the very next choice. Spend your energy there, and nowhere else."),
            )),
            GuideSection("Right now", GuideKind.STEPS, listOf(
                GuideItem("Get up and move", "Leave the room. Change the scene with your body before your mind starts writing the story."),
                GuideItem("Cold water", "Face or shower, thirty seconds cold. It clears the fog faster than any thought can."),
                GuideItem("Burn it off", "Twenty push-ups or a hard two-minute walk. Give the leftover charge somewhere to go."),
                GuideItem("Call it, then close it", "Say it once: “That happened. It's done.” No speech, no verdict — then move."),
            )),
            GuideSection("Kill the spiral", GuideKind.RULES, listOf(
                rule("The second thought is the trap", "The slip cost you minutes. “I've already ruined it” costs you the week. Don't take the bait."),
                rule("Stop replaying it", "Every time you run the scene again, you feed it again. Close the loop and face forward."),
                rule("Win the next hour, not forever", "“Never again” is a promise you'll break by tonight. Just take the next hour clean, then the next."),
            )),
            GuideSection("Guard tomorrow", GuideKind.RULES, listOf(
                rule("Name what opened the door", "Tired? A certain hour? Bored and alone in bed? Find today's trigger and close that door before it opens again."),
                rule("Don't lower the walls", "Adult pack on, Strict Mode running. The moment you feel strong is exactly when future-you needs the guard kept up."),
                rule("Next urge: ride it", "Name it, hold ten minutes, move. It always passes — feed it once and it only learns to shout louder."),
            )),
        ),
        closing = "You are not your worst ten minutes.\nYou are what you do in the next ten.",
    ),

    // 2 ─────────────────────────────────────────────────────────── Can't focus
    Scenario(
        id = "focus",
        hubTitle = "Can't focus",
        hubSubtitle = "Time to study or work and your mind won't settle.",
        icon = Icons.Filled.School,
        colors = listOf(Color(0xFF14B8A6), Color(0xFF22C55E)),
        kicker = "STOP WAITING TO FEEL READY",
        title = "Just Begin",
        subtitle = "Focus follows action. You don't think your way in — you start, and your mind catches up.",
        sections = listOf(
            GuideSection("Hold this", GuideKind.TRUTHS, listOf(
                truth("Sartre", "You become it by doing it", "You are not “a focused person” or “a distracted one” — those are stories, not facts. You make yourself, act by act. Sit down and work, and in that moment you already are the disciplined person you thought you had to become first."),
                truth("Darwin", "Built to be distracted", "Your mind evolved to sweep the horizon for threat and novelty — wandering kept your ancestors alive. The feed just sells that ancient reflex back to you. It's not a flaw in you; it's old software running in a world it never predicted, and old software can be overruled."),
                truth("Marcus Aurelius", "Do the thing in front of you", "“Concentrate every minute on doing what's in front of you,” wrote Marcus, “with real seriousness — as if it were the last thing you were doing in your life.” Focus isn't a mood to summon. It's a decision: give this one task everything, then the next."),
            )),
            GuideSection("Clear the deck", GuideKind.RULES, listOf(
                rule("Phone in another room", "Not face-down on the desk — gone. A phone you can see drains attention even while you ignore it."),
                rule("One tab, one task", "Close the rest. Name the single thing this block is for, and hide everything that isn't it."),
                rule("Turn the walls on first", "Start a Pomodoro or Strict session before you begin — make distraction impossible, not just discouraged."),
            )),
            GuideSection("The two-minute start", GuideKind.STEPS, listOf(
                GuideItem("Shrink it to nothing", "Not “study for the exam.” Open the book to page one. Write one line. Make step one too small to refuse."),
                GuideItem("Set the timer", "Twenty-five minutes, one task. You're not promising hours — only the timer."),
                GuideItem("Start ugly", "Rough notes, bad draft, slow reading. You can fix ugly — you can't fix blank."),
                GuideItem("Push through the on-ramp", "The first ten minutes are friction, not the road. Drive through them and focus arrives."),
            )),
            GuideSection("Work in blocks", GuideKind.RULES, listOf(
                rule("Sprints, not marathons", "Twenty-five to fifty minutes of single-tasking, then a real break. Depth beats duration."),
                rule("Break without a screen", "Stand, stretch, look far, walk. Reward focus with a scroll and you erase the reset."),
                rule("Spend your best hours well", "Do the hardest thing when your head is clearest — usually the morning. Guard that window like gold."),
            )),
            GuideSection("When your mind wanders", GuideKind.STEPS, listOf(
                GuideItem("Catch it, don't scold it", "Drifting is what minds do, not a failure. The skill is noticing — not never leaving."),
                GuideItem("Park the thought", "Write the distraction on paper — “reply to X”, “check Y” — and let the page hold it so your head can let go."),
                GuideItem("Re-enter on one line", "Re-read the last sentence you wrote. One concrete point is the easiest door back in."),
            )),
        ),
        closing = "Discipline is doing it before you feel like it.\nBegin, and the feeling shows up.",
    ),

    // 3 ─────────────────────────────────────────────────────────── Feeling lazy
    Scenario(
        id = "lazy",
        hubTitle = "Feeling lazy",
        hubSubtitle = "Low energy, no motivation — get moving anyway.",
        icon = Icons.Filled.Bolt,
        colors = listOf(Color(0xFFFB923C), Color(0xFFF97316)),
        kicker = "MOTION BEFORE MOTIVATION",
        title = "Move First",
        subtitle = "You won't feel like it and then act. You act, and the feeling arrives.",
        sections = listOf(
            GuideSection("Hold this", GuideKind.TRUTHS, listOf(
                truth("Sartre", "“I'm lazy” is bad faith", "Calling yourself lazy is what Sartre named bad faith — pretending you're a fixed thing so you don't have to choose. There is no lazy essence, only a choice not yet made. Drop the label and the choice is right there, waiting for you to make it."),
                truth("Darwin", "Your body hoards energy", "Doing nothing once meant surviving the famine — your body still guards its energy as if scarcity is coming tomorrow. The pull toward the couch is ancient thrift, not the truth about you. In a world of plenty you override it on purpose: motion, not rest, is what you actually need."),
                truth("Marcus Aurelius", "Rise to do your work", "At dawn, when he couldn't get out of bed, Marcus told himself: I am rising to do the work of a human being. You weren't made for warmth under a blanket — you were made to act. The reluctance is real; do it anyway, because that is what you are for."),
            )),
            GuideSection("The five-minute rule", GuideKind.STEPS, listOf(
                GuideItem("Pick one thing", "The smallest useful move. Not the list — one thing."),
                GuideItem("Promise only five minutes", "Tell yourself you can quit at five. You rarely will — starting is the whole battle."),
                GuideItem("Move your body into it", "Shoes on. Doc open. One dish in hand. Begin the physical act before the mind can argue."),
                GuideItem("Let momentum take over", "Five minutes in, you're already moving. Ride it as far as it goes, and stop without guilt."),
            )),
            GuideSection("Lower the bar", GuideKind.RULES, listOf(
                rule("Half beats zero", "A short workout, ten minutes of work, one paragraph. Shrink the task until saying no feels ridiculous."),
                rule("Remove the friction", "Clothes laid out, book open, desk clear. Kill every tiny obstacle between you and the first move."),
                rule("Don't wait for the mood", "Motivation is weather — it comes and goes. Action is the one lever you can always pull."),
            )),
            GuideSection("Build momentum", GuideKind.RULES, listOf(
                rule("Bank an easy win", "Make the bed, drink water, two minutes of movement. One small win primes the bigger ones."),
                rule("Body before brain", "Move, get daylight, get blood flowing. “Lazy” is usually under-moved and under-lit, not truly tired."),
                rule("Lock out the scroll", "Low willpower plus a phone eats the day. Flip Quick Block on and decide once, so you don't have to keep deciding."),
            )),
        ),
        closing = "You don't need motivation.\nYou need the first move. The rest follows.",
    ),

    // 4 ─────────────────────────────────────────────────────────── Can't sleep
    Scenario(
        id = "sleep",
        hubTitle = "Can't sleep",
        hubSubtitle = "Late-night phone, racing mind — put the day down.",
        icon = Icons.Filled.Bedtime,
        colors = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6)),
        kicker = "THE PHONE IS THE PROBLEM",
        title = "Put It Down",
        subtitle = "The scroll doesn't ease you to sleep — it steals the sleep you were about to fall into.",
        sections = listOf(
            GuideSection("Hold this", GuideKind.TRUTHS, listOf(
                truth("Darwin", "A brain built for the sun", "For a million years light meant day and dark meant sleep, and your body still obeys that clock. The screen is a counterfeit sun held inches from your eyes, telling an ancient brain to stay awake. Put out the fake sun and the old rhythm comes home on its own."),
                truth("Sartre", "Let go to fall", "Sleep is the one thing you can't force by will — grip for it and it runs. Here freedom means surrender, not effort: stop steering, stop trying, and let the body do what it has always known how to do."),
                truth("Seneca", "Close the day in peace", "Seneca ended each night by reviewing the day — then letting it go entirely. Settle your accounts with the day on paper, forgive what can't be changed, and hand the rest to tomorrow. Sleep comes easily to the mind that has set its burden down."),
            )),
            GuideSection("Put the phone to bed", GuideKind.RULES, listOf(
                rule("It sleeps in another room", "The single biggest change. In-bed scrolling wrecks your sleep and hides your worst urges. Buy an alarm clock."),
                rule("Screens off an hour early", "Bright light and bottomless feeds tell your brain it's noon. Get off the drip before bed."),
                rule("There is no “five minutes”", "At night, five minutes is always an hour. Don't crack the door at all."),
            )),
            GuideSection("Wind down", GuideKind.STEPS, listOf(
                GuideItem("Dim the world", "Lights low, room cool and dark. Tell the body the day is closing."),
                GuideItem("Empty your head onto paper", "Racing thoughts? Write tomorrow's worries and tasks down. On the page, out of the mind."),
                GuideItem("Lengthen the exhale", "Breathe in four, out six, for two minutes. A long exhale is the body's brake."),
                GuideItem("Read something boring", "Paper, nothing gripping. Let dullness do the work and pull you under."),
            )),
            GuideSection("Still awake?", GuideKind.RULES, listOf(
                rule("Don't watch the clock", "Turn it away. Counting lost hours only wakes you further."),
                rule("Get up, don't reach", "Awake twenty minutes? Leave the bed for somewhere dim and dull, and come back sleepy. Never the phone."),
                rule("One rough night is fine", "Trying hard to sleep keeps you awake. Loosen your grip — tomorrow will hold."),
            )),
        ),
        closing = "Protect your sleep like a foundation —\neverything else is built on it.",
    ),

    // 5 ─────────────────────────────────────────────────────────── Urge to scroll
    Scenario(
        id = "scroll",
        hubTitle = "Urge to scroll",
        hubSubtitle = "The pull to grab your phone right now.",
        icon = Icons.Filled.Waves,
        colors = listOf(Color(0xFFF0598A), Color(0xFFB5179E)),
        kicker = "A WAVE, NOT AN ORDER",
        title = "Ride It Out",
        subtitle = "The urge rises, peaks, and falls on its own within minutes — fed or not.",
        sections = listOf(
            GuideSection("Hold this", GuideKind.TRUTHS, listOf(
                truth("Darwin", "A supernormal lure", "Evolution wired you to chase novelty, because novelty once meant food, safety, a mate. The feed is a supernormal version of that lure — brighter and faster than anything real, built to jam an instinct that never learned to say no. The pull is genuine; the reward is a decoy."),
                truth("Sartre", "The urge is not an order", "You are free — even from your own impulses. The craving arrives on its own, but obeying it is a separate choice, and it is yours. In the gap between the urge and the hand reaching for the phone lives your whole freedom. Live in that gap."),
                truth("Epictetus", "The impression is not the act", "Epictetus taught that an impression — the pull, the itch — arrives on its own, but your assent to it is entirely yours. The urge is not up to you; reaching for the phone is. Between the two is a gap, and in that gap is everything the Stoics called freedom."),
            )),
            GuideSection("Ride the wave", GuideKind.STEPS, listOf(
                GuideItem("Name it", "“This is an urge, not a need.” Naming it makes you the watcher, not the one obeying."),
                GuideItem("Give it ten minutes", "You don't have to win forever — just outlast ten minutes. Unfed, the wave breaks on its own."),
                GuideItem("Move", "Stand, leave the room, ten push-ups, cold water. Urges live in stillness; motion kills them."),
                GuideItem("Watch it shrink", "Smaller already, isn't it? Every wave you outlast teaches your brain it was never an emergency."),
            )),
            GuideSection("Make it harder", GuideKind.RULES, listOf(
                rule("Put distance between you and it", "Phone out of your hand, out of the room if you can. Distance buys you the ten minutes."),
                rule("Flip Quick Block on", "The instant the urge hits, hit the tile. Make the app impossible, not merely tempting."),
                rule("Close the entry points", "Feeds off the home screen, notifications off. Most scrolls start with a lever someone pulled for you."),
            )),
            GuideSection("Replace it", GuideKind.RULES, listOf(
                rule("Have a default ready", "Decide now what you'll do instead — a book on the desk, a walk, a set of push-ups. Don't negotiate mid-urge."),
                rule("Feed the real need", "The urge usually just means bored or tired. Answer that — rest, movement, a person — not the fake craving."),
            )),
        ),
        closing = "Every urge you don't obey makes the next one quieter.\nYou're training your brain — train it well.",
    ),

    // 6 ─────────────────────────────────────────────────────────── Overwhelmed
    Scenario(
        id = "overwhelmed",
        hubTitle = "Overwhelmed",
        hubSubtitle = "Too much at once, mind spinning — find solid ground.",
        icon = Icons.Filled.Spa,
        colors = listOf(Color(0xFF38BDF8), Color(0xFF3B82F6)),
        kicker = "BODY FIRST, THEN THE PROBLEM",
        title = "Slow It Down",
        subtitle = "You can't think straight in a racing body. Settle the system first — then face the list.",
        sections = listOf(
            GuideSection("Hold this", GuideKind.TRUTHS, listOf(
                truth("Darwin", "An alarm for the wrong danger", "The racing heart, the tight chest — that's a survival alarm built to outrun predators, now firing at a full inbox. The body can't tell the difference. It isn't weakness; it's ancient hardware misreading a modern threat. Calm the body and the “danger” shrinks to its real size."),
                truth("Sartre", "Condemned to be free", "Sartre said we are condemned to be free — choosing, always, whether we like it or not. Overwhelm is the weight of too many choices held at once. You can't make them all, but you can always make the single next one. That one choice is solid ground; stand on it."),
                truth("Seneca", "We suffer more in imagination", "“We suffer more often in imagination than in reality,” said Seneca. Most of the crushing weight is the mind stacking future catastrophes that haven't happened. Handle only what is actually in front of you today; the towering rest is largely a story, and it shrinks the moment you act."),
            )),
            GuideSection("Settle the body", GuideKind.STEPS, listOf(
                GuideItem("Breathe out long", "In for four, out for six, five times. A slow exhale is the fastest off-switch for panic."),
                GuideItem("Land in the room", "Feel your feet, the chair, name three things you can see. Come out of the spin and into now."),
                GuideItem("One glass of water", "Drink it slowly. A small physical act breaks the loop and gives your hands a job."),
            )),
            GuideSection("Empty your head", GuideKind.RULES, listOf(
                rule("Dump it all on paper", "Every worry and task, out of your head and onto a page. The mind races to avoid forgetting — the paper remembers so you can stop."),
                rule("Choose one next step", "Not the mountain — the single next physical action. Overwhelm is just too many things held at once."),
                rule("Do that one, then the next", "Finish it, cross it off, choose again. Motion shrinks the pile faster than planning ever will."),
            )),
            GuideSection("Shrink the problem", GuideKind.RULES, listOf(
                rule("Sort what's yours", "Half the weight is things you can't control. Keep what's actually yours to do; set the rest down."),
                rule("Now, later, or never", "Most “urgent” isn't. Triage hard into now / later / never, and delete without mercy."),
                rule("Cut the noise", "Scrolling on top of overwhelm is fuel on a fire. Turn the blockers on and give your head silence to reset."),
            )),
        ),
        closing = "You don't have to carry it all at once.\nOne breath, one page, one next thing.",
    ),
)
