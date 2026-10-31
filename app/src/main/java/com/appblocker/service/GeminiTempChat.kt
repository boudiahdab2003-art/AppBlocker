package com.appblocker.service

/**
 * What the screen says about Gemini's temporary chat, as one reading — the same three-way-plus-one
 * shape as [PlayerView], for the same reason: *"can't tell" is not "no"*.
 */
internal enum class TempChatView {
    /** The temporary chat's start screen is in front of him right now. */
    OPEN,

    /** Gemini, readable, and no temporary-chat start screen in it — an ordinary chat. */
    CLOSED,

    /** Some other package is in front. He is out of Gemini; there is nothing left to do. */
    GONE,

    /** No window tree, or our own package in front of it. **Never** treated as progress. */
    UNREADABLE,
}

/**
 * Gemini's **temporary chat** — the mode whose chats "don't appear in recent chats" — blocked on
 * its own while the rest of Gemini keeps working. His ask, 31 Oct 2026: *"can we block gemini
 * anonymous"*, then "Temporary chat only" from the options, then *"make it blocked always"*.
 * Invariant 99.
 *
 * ⚠️ **Always means always.** There is no switch for it and it does not wait for Quick Block — a
 * paused Quick Block, a Strict session or none, it is blocked. The first build had a switch under
 * Gemini in the editor (default off) and only blocked while Quick Block was enforcing; he asked for
 * it gone before that build was released. `GeminiTempChatTest.itIsBlockedAlways` reads the watcher
 * and fails if either condition comes back.
 *
 * ## What it is recognised by, measured on his phone (31 Oct 2026, HyperOS, Second Space)
 *
 * - The Gemini app is a shell: `com.google.android.apps.bard` launches straight into
 *   `com.google.android.googlequicksearchbox` (`…assistant.surfaces.voice.robin.main.MainActivity`),
 *   so the Google app is the package actually in front. Both are listed in [PACKAGES].
 * - **The "New temporary chat" button is on screen in EVERY ordinary chat**, as a content
 *   description. Matching the words "temporary chat" anywhere would block all of Gemini — which is
 *   why the match below is on the start screen's *text* and never on a label.
 * - A temporary chat opens on a start screen whose body reads *"Temporary chats don't appear in
 *   recent chats and aren't used to improve Google AI. Stored for 72 hours for safety."*, inside the
 *   zero-state container (`assistant_robin_zero_state_compose_view`). gemini.google.com in Chrome
 *   shows the same sentence, split into link and text nodes.
 * - ⚠️ **Once a message is sent, nothing on screen says "temporary" any more** — the toolbar reads
 *   "New chat" and "More options" exactly like an ordinary chat, and the menu holds only Help and
 *   Feedback. So the block is at the **entrance**: the start screen is covered before anything can
 *   be typed into it. A temporary chat started while the watcher was not running cannot be told
 *   apart afterwards; that is a limit of what Gemini shows, not something to guess around.
 * - Back from the start screen: the first BACK closes the keyboard, the second leaves temporary
 *   mode for an ordinary new chat. That is what [next] walks.
 */
internal object GeminiTempChat {

    /** The counter key the in-app cover is raised under. Exempt from dismiss suppression, as the
     *  Shorts cover is, and for the same reason — see [CoverGate.suppressed]. */
    const val KEY = "gemini-temp"

    /** Where Gemini's chat actually runs: the Google app, and the Gemini app's own shell. */
    val PACKAGES = setOf(
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.bard",
    )

    /** The view-id fragment of the container the start screen sits in, in the Google app. */
    const val ZERO_STATE_ID = "zero_state"

    /**
     * Whether [text] is the temporary chat's start-screen sentence.
     *
     * Two phrases, both required, rather than the sentence whole: the website splits it into a
     * link ("Temporary chats") and plain text, and the separator the page walk puts between them
     * is not something to depend on. Curly apostrophes are folded so a typographic quote in a
     * later Gemini build does not matter — neither phrase contains one, but the fold is free.
     */
    fun isStartScreenText(text: String): Boolean {
        val t = text.lowercase().replace('’', '\'')
        return "temporary chats" in t && "recent chats" in t
    }

    /** gemini.google.com, by host — the address the website's temporary chat lives on. */
    fun isGeminiHost(url: String?): Boolean {
        val host = url?.lowercase()?.substringAfter("://")?.substringBefore('/')
            ?.substringBefore('?') ?: return false
        return host == "gemini.google.com" || host.endsWith(".gemini.google.com")
    }

    // ---- the exit after "Got it" -------------------------------------------------------------

    /** BACKs the walk will spend: one closes the keyboard, one leaves temporary mode, one spare
     *  for a sheet over it. Past this it leaves Gemini instead. */
    const val MAX_BACKS = 3

    /** Gap between readings — long enough for a BACK to land and the tree to settle. */
    const val POLL_MS = 350L

    /** The whole walk's budget. Past this, an unreadable screen ends it pressing nothing. */
    const val GIVE_UP_MS = 2_000L

    enum class Step {
        /** Press BACK, then read again. */
        BACK,

        /** Still in a temporary chat with every BACK spent: leave Gemini. */
        HOME,

        /** Read again without acting. */
        WAIT,

        /** An ordinary chat, or out of Gemini, or out of time — nothing more to press. The scan
         *  owns the screen again and re-covers a temporary chat that is somehow still there. */
        DONE,
    }

    /**
     * The next move, from the current reading and what the walk has spent.
     *
     * Unlike [ShortsExit], HOME is a safe last resort here — nothing floats away — so a temporary
     * chat that survives every BACK leaves Gemini rather than stays. A reading that could not be
     * made never presses anything: it waits, and past [GIVE_UP_MS] it stops.
     */
    fun next(view: TempChatView, backsSent: Int, sinceStartMs: Long): Step = when (view) {
        TempChatView.CLOSED, TempChatView.GONE -> Step.DONE
        TempChatView.OPEN -> if (backsSent < MAX_BACKS) Step.BACK else Step.HOME
        TempChatView.UNREADABLE -> if (sinceStartMs >= GIVE_UP_MS) Step.DONE else Step.WAIT
    }
}
