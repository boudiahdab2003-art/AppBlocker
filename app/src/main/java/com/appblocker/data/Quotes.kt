package com.appblocker.data

/** A short motivational line shown on the block screen. */
data class Quote(val text: String, val author: String)

/**
 * Bundled motivational quotes for the block screen — the moment of temptation.
 * Short (1–2 lines), about discipline, focus, time and beating distraction.
 * A fresh one is picked every time the block screen appears. Every quote is
 * attributed; the app's own lines are signed "Your coach".
 */
object Quotes {

    private var lastIndex = -1

    /** A random quote, never the same one twice in a row. */
    fun random(): Quote {
        var i = ALL.indices.random()
        if (i == lastIndex) i = (i + 1) % ALL.size
        lastIndex = i
        return ALL[i]
    }

    private val ALL = listOf(
        // Stoics & classics
        Quote("It is not that we have a short time to live, but that we waste a lot of it.", "Seneca"),
        Quote("You could leave life right now. Let that determine what you do, say and think.", "Marcus Aurelius"),
        Quote("No man is free who is not master of himself.", "Epictetus"),
        Quote("The impediment to action advances action. What stands in the way becomes the way.", "Marcus Aurelius"),
        Quote("People find time is short, yet waste much of it.", "Seneca"),
        Quote("First say to yourself what you would be; and then do what you have to do.", "Epictetus"),
        Quote("Concentrate every minute like a Roman on doing what's in front of you.", "Marcus Aurelius"),
        Quote("We suffer more often in imagination than in reality.", "Seneca"),
        Quote("Waste no more time arguing about what a good man should be. Be one.", "Marcus Aurelius"),
        Quote("Self-control is strength. Right thought is mastery. Calmness is power.", "James Allen"),

        // Time & attention
        Quote("Where your attention goes, your life goes.", "Your coach"),
        Quote("You will never find time for anything. You must make it.", "Charles Buxton"),
        Quote("Lost time is never found again.", "Benjamin Franklin"),
        Quote("The cost of a thing is the amount of life you exchange for it.", "Henry David Thoreau"),
        Quote("Time is what we want most, but what we use worst.", "William Penn"),
        Quote("Your attention is the most valuable thing you own. Spend it on purpose.", "Your coach"),
        Quote("A year from now you may wish you had started today.", "Karen Lamb"),
        Quote("How we spend our days is how we spend our lives.", "Annie Dillard"),
        Quote("Every minute you scroll is a minute you traded away.", "Your coach"),
        Quote("Guard your time fiercely. It is your life in small pieces.", "Your coach"),

        // Discipline & habits
        Quote("Discipline is choosing between what you want now and what you want most.", "Abraham Lincoln"),
        Quote("Motivation gets you started. Discipline keeps you going.", "Your coach"),
        Quote("We are what we repeatedly do. Excellence, then, is not an act, but a habit.", "Will Durant"),
        Quote("The pain of discipline weighs ounces. The pain of regret weighs tons.", "Jim Rohn"),
        Quote("Small daily wins become unstoppable momentum.", "Your coach"),
        Quote("You don't have to be great to start, but you have to start to be great.", "Zig Ziglar"),
        Quote("Every time you resist, the next time gets easier.", "Your coach"),
        Quote("Success is the sum of small efforts, repeated day in and day out.", "Robert Collier"),
        Quote("Hard choices, easy life. Easy choices, hard life.", "Jerzy Gregorek"),
        Quote("The best way to break a habit is to never feed it.", "Your coach"),

        // Beating the phone / distraction
        Quote("Your phone is a tool. Don't let it become your master.", "Your coach"),
        Quote("The apps are designed to hook you. You are designed to overcome.", "Your coach"),
        Quote("Real life is happening right now, off this screen.", "Your coach"),
        Quote("Boredom is where your best ideas are hiding. Let yourself be bored.", "Your coach"),
        Quote("You blocked this for a reason. Trust the person who set that block.", "Your coach"),
        Quote("Nothing on that app will matter in a week. What you do instead might.", "Your coach"),
        Quote("This craving will pass in ninety seconds. Breathe and walk away.", "Your coach"),
        Quote("You are not missing out. You are opting in — to your own life.", "Your coach"),
        Quote("Every scroll you skip is a rep for your willpower.", "Your coach"),
        Quote("The feed never ends. Your time does.", "Your coach"),

        // Punchy modern one-liners
        Quote("Your future self is watching. Make them proud.", "Your coach"),
        Quote("One day, or day one. You decide.", "Your coach"),
        Quote("Don't trade your goals for a dopamine hit.", "Your coach"),
        Quote("Strong people build habits. Weak moments break them. Stay strong.", "Your coach"),
        Quote("You've already won this battle once — you set this block. Win it again.", "Your coach"),
        Quote("Progress, not perfection. Closing this screen counts.", "Your coach"),
        Quote("Great things never came from comfort zones.", "Your coach"),
        Quote("Focus is a superpower in a distracted world.", "Your coach"),
        Quote("The distance between dreams and reality is called discipline.", "Your coach"),
        Quote("Do something today your future self will thank you for.", "Your coach"),
    )
}
