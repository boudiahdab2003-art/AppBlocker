package com.appblocker.data

/**
 * The rules of the typed paragraph that stands in front of every protection this app can lower.
 *
 * **Why this is a plain object.** The gate itself ([com.appblocker.ui.FrictionGate]) is a Compose
 * screen, so nothing inside it can be tested here. The three decisions that actually make the gate
 * work — is this typed correctly, is this a paste, what is the paragraph — are pulled out to this
 * side of the seam, the same move `OffSwitchGuard`, `decideBlock` and `AdminScreens` make for the
 * same reason. A gate that silently stopped being difficult would look exactly like a gate that
 * was working.
 *
 * **The clock is a deadline, not a wait.** It used to be a wait: the paragraph could be typed at
 * any pace and the button unlocked once two minutes had passed, so the timer was something to sit
 * through rather than something to beat. Now the paragraph must be typed *before* the clock runs
 * out, and when it runs out the paragraph is replaced and the attempt starts over. The friction is
 * no longer "wait a bit"; it is "do this properly, now, or do it again".
 */
object TypedChallenge {

    /**
     * How many words to type, and how long there is to type them.
     *
     * The pair is what matters, not either number: 80 words in 360 seconds is about four and a
     * half seconds a word, which steady phone typing reaches with room to fix a typo, while
     * leaving no time to wander off mid-way. Changing one without the other is how this becomes
     * either pointless or impossible, so a test asserts the ratio stays in a sane band.
     *
     * 26 Nov 2026, his pick after uninstalling the app on a bad evening: twice as long (was 40 words
     * in 3 minutes), and **exact** — capitals and punctuation must match ([EXACT]).
     */
    const val WORDS = 80
    const val ATTEMPT_SECONDS = 360

    /** Share of words that start with a capital, and that are followed by a comma / a full stop. */
    internal const val CAPITAL_SHARE = 0.35
    internal const val COMMA_SHARE = 0.15
    internal const val STOP_SHARE = 0.15

    /**
     * The largest text an insert may grow by in one edit before it is treated as a paste.
     *
     * Sixteen because a *word* can legitimately arrive all at once — swipe typing commits the
     * whole of "mountainside" in one change, and so does accepting a correction — while a pasted
     * paragraph arrives as hundreds of characters in a single change. This is the check that
     * closes the door `NoPasteToolbar` cannot: that toolbar removes the selection popup, but a
     * keyboard's own clipboard (Gboard's clipboard chip) commits straight through the IME and
     * never opens a popup at all.
     */
    const val MAX_INSERT = 16

    /**
     * Long everyday words on purpose: transcribing eighty of these takes real effort, which is the
     * point of the gate. Nothing here is obscure — the difficulty is the volume and the clock, not
     * spelling something you have never seen.
     */
    val CHALLENGE_WORDS = listOf(
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

    /** A fresh paragraph. Random every time — one that repeated could be learnt, and a gate you
     *  can type from memory is a gate that has stopped costing anything. */
    fun newPhrase(random: kotlin.random.Random = kotlin.random.Random.Default): String =
        (1..WORDS).joinToString(" ") { i ->
            val base = CHALLENGE_WORDS.random(random)
            // Capitals and punctuation at random places, not where a sentence would put them, so the
            // shape can't be typed on autopilot either. The keyboard's own auto-capitals are off in
            // the gate (KeyboardCapitalization.None), so a lowercase word after a full stop stays so.
            val word = if (random.nextDouble() < CAPITAL_SHARE) base.replaceFirstChar { it.uppercase() } else base
            val r = random.nextDouble()
            when {
                i == WORDS -> "$word."
                r < COMMA_SHARE -> "$word,"
                r < COMMA_SHARE + STOP_SHARE -> "$word."
                else -> word
            }
        }

    /**
     * **Exact** compare: every capital, comma and full stop must match (his pick, 26 Nov 2026 —
     * this used to forgive capitals). Only whitespace is forgiven — a stray double space or a line
     * break is invisible on the screen, and a gate that refuses a correct-looking answer for an
     * invisible reason just reads as broken. Every other difference is shown by [progress] the
     * moment it is typed, so nothing that keeps the button locked is ever hidden.
     */
    fun matches(phrase: String, input: String): Boolean =
        input.trim().replace(WHITESPACE, " ") == phrase

    /** Capitals and punctuation count (see [matches]). A test pins it. */
    const val EXACT = true

    /**
     * Whether an edit inserted more than a person can type in one go — i.e. a paste.
     *
     * Deliberately one-directional: **deletions are always allowed**. Clearing the field is not a
     * way round anything, and refusing it would trap someone who wanted to start over.
     */
    fun isPaste(oldText: String, newText: String): Boolean =
        newText.length - oldText.length > MAX_INSERT

    /**
     * How far into the paragraph the typing has got, and whether it has already gone wrong.
     *
     * @param correct words typed correctly from the start — also the index of the word being
     *   typed now, which is what the screen highlights.
     * @param wrong the text has diverged, so no amount of further typing can match. The word at
     *   [correct] is the one that broke it.
     */
    data class Progress(val correct: Int, val wrong: Boolean)

    /**
     * Where the typist is. Drives the display only — [matches] remains the sole authority on
     * whether the gate opens, so a bug in here can make the screen unhelpful but cannot unlock
     * anything.
     *
     * **Why the screen needs this at all.** Forty words with nothing but a pass/fail at the end
     * means a wrong letter in word three is invisible until the clock runs out and the whole
     * attempt is thrown away. That does not read as strictness, it reads as the app cheating —
     * the same reason the gate latches [matches] the instant it is met rather than at the moment
     * the button is pressed. Knowing where you are does not shrink the work: it is still forty
     * words, still against the same clock.
     *
     * A word counts as finished when it is typed in full, or when a space has been typed after it.
     * Until then a partial word is judged as a *prefix* — "wheelbar" on the way to "wheelbarrow"
     * is not an error, "wheelbap" is, and saying so immediately is the entire point.
     */
    fun progress(phrase: String, input: String): Progress {
        val expected = phrase.split(WHITESPACE).filter { it.isNotEmpty() }
        val typed = input.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (typed.isEmpty()) return Progress(0, false)
        // A trailing space is the typist saying "that word is done" — without it, a short word is
        // still on its way to a longer one and must not be called an error yet.
        val committed = input.isNotEmpty() && input.last().isWhitespace()
        var correct = 0
        typed.forEachIndexed { i, word ->
            // Typed past the end: there is nothing left for this word to be.
            if (i >= expected.size) return Progress(correct, true)
            val want = expected[i]
            when {
                // Equal counts however it was left. Without this the fortieth word would sit at
                // 39/40 while `matches` already said yes, and the two would contradict each other
                // on the one screen showing both.
                word == want -> correct++
                i == typed.lastIndex && !committed && want.startsWith(word) ->
                    return Progress(correct, false) // still being typed
                else -> return Progress(correct, true)
            }
        }
        return Progress(correct, false)
    }

    private val WHITESPACE = Regex("\\s+")
}
