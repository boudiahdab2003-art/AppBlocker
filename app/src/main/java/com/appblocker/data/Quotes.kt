package com.appblocker.data

/** A short motivational line shown on the block screen. */
data class Quote(val text: String, val author: String)

/**
 * Bundled motivational quotes for the block screen — the moment of temptation.
 * Short (1–2 lines), about attention, time, discipline and beating distraction.
 * A fresh one is picked every time the block screen appears.
 *
 * Quality bar: real quotes carry verified attributions (a famous line whose true
 * source is unclear is signed "Unknown", never a borrowed famous name); the app's
 * own lines are signed "Your coach" and must earn their place — no poster clichés.
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

    /** Poster-style text size: short quotes go huge, long ones stay readable. */
    fun sizeSpFor(text: String): Float = when {
        text.length <= 60 -> 38f
        text.length <= 100 -> 32f
        else -> 28f
    }

    private val ALL = listOf(
        // Stoics & classics
        Quote("It is not that we have a short time to live, but that we waste a lot of it.", "Seneca"),
        Quote("You could leave life right now. Let that determine what you do, say and think.", "Marcus Aurelius"),
        Quote("No man is free who is not master of himself.", "Epictetus"),
        Quote("The impediment to action advances action. What stands in the way becomes the way.", "Marcus Aurelius"),
        Quote("While we are postponing, life speeds by.", "Seneca"),
        Quote("First say to yourself what you would be; and then do what you have to do.", "Epictetus"),
        Quote("Concentrate every minute like a Roman on doing what's in front of you.", "Marcus Aurelius"),
        Quote("We suffer more often in imagination than in reality.", "Seneca"),
        Quote("Waste no more time arguing about what a good man should be. Be one.", "Marcus Aurelius"),
        Quote("Nothing is ours, except time.", "Seneca"),

        // Attention & time
        Quote("My experience is what I agree to attend to.", "William James"),
        Quote("Attention is the beginning of devotion.", "Mary Oliver"),
        Quote("A wealth of information creates a poverty of attention.", "Herbert Simon"),
        Quote("How we spend our days is, of course, how we spend our lives.", "Annie Dillard"),
        Quote("All of humanity's problems stem from man's inability to sit quietly in a room alone.", "Blaise Pascal"),
        Quote("The cost of a thing is the amount of life you exchange for it.", "Henry David Thoreau"),
        Quote("If you love life, don't waste time, for time is what life is made up of.", "Bruce Lee"),
        Quote("Lost time is never found again.", "Benjamin Franklin"),
        Quote("Time is what we want most, but what we use worst.", "William Penn"),
        Quote("Guard well your spare moments.", "Ralph Waldo Emerson"),
        Quote("You will never find time for anything. If you want time, you must make it.", "Charles Buxton"),
        Quote("Control of consciousness determines the quality of life.", "Mihaly Csikszentmihalyi"),
        Quote("Clarity about what matters provides clarity about what does not.", "Cal Newport"),
        Quote("Tell me, what is it you plan to do with your one wild and precious life?", "Mary Oliver"),

        // Discipline & habits
        Quote("We are what we repeatedly do. Excellence, then, is not an act, but a habit.", "Will Durant"),
        Quote("You do not rise to the level of your goals. You fall to the level of your systems.", "James Clear"),
        Quote("Every action you take is a vote for the person you wish to become.", "James Clear"),
        Quote("The pain of discipline weighs ounces. The pain of regret weighs tons.", "Jim Rohn"),
        Quote("Hard choices, easy life. Easy choices, hard life.", "Jerzy Gregorek"),
        Quote("Motivation is what gets you started. Habit is what keeps you going.", "Jim Ryun"),
        Quote("Success is the sum of small efforts, repeated day in and day out.", "Robert Collier"),
        Quote("You don't have to be great to start, but you have to start to be great.", "Zig Ziglar"),
        Quote("Discipline is choosing between what you want now and what you want most.", "Unknown"),
        Quote("A year from now you may wish you had started today.", "Karen Lamb"),
        Quote("Self-control is strength. Right thought is mastery. Calmness is power.", "James Allen"),

        // Beating the phone / distraction — the app's own lines
        Quote("The feed never ends. Your time does.", "Your coach"),
        Quote("Nothing on that app will matter in a week. What you do instead might.", "Your coach"),
        Quote("You blocked this for a reason. Trust the person who set it.", "Your coach"),
        Quote("This craving passes in about ninety seconds. Outlast it.", "Your coach"),
        Quote("Real life is happening right now, off this screen.", "Your coach"),
        Quote("Boredom is where your best ideas are hiding. Let yourself be bored.", "Your coach"),
        Quote("That app is engineered to hook you. You saw it coming — that's why you're here.", "Your coach"),
        Quote("You are not missing out. You are opting in — to your own life.", "Your coach"),
        Quote("Every scroll you skip is a rep for your willpower.", "Your coach"),
        Quote("Progress, not perfection. Closing this screen counts.", "Your coach"),
        Quote("Your attention is the most valuable thing you own. Spend it on purpose.", "Your coach"),
        Quote("Where your attention goes, your life goes.", "Your coach"),
        Quote("Don't trade this evening for a dopamine hit.", "Your coach"),
    )
}
