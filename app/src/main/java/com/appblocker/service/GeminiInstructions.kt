package com.appblocker.service

/**
 * Gemini's **instructions page** — "Personal context" ▸ "Your instructions for Gemini" — blocked
 * always, so the instructions he writes on a good day cannot be rewritten or deleted on a bad one.
 * His ask, 2 Nov 2026: *"i want to give instructions to google gemini but when i relapse i enter
 * and change it … can you help block the instructions screen"*, then "Always" from the options.
 * Invariant 100.
 *
 * ## What it is recognised by, measured on his phone (2 Nov 2026, HyperOS, main space)
 *
 * - **The Gemini app has no screen of its own for this.** Profile ▸ Personal context opens
 *   `https://gemini.google.com/saved-info` in a **Chrome Custom Tab** (`com.android.chrome`,
 *   `CustomTabActivity`), whose toolbar shows the page title and the bare host only. So it is a
 *   web page in a browser, and the website branch of the scan covers the app's way in and
 *   gemini.google.com in Chrome alike.
 * - The page reads "Personal context", then "Memory", then **"Your instructions for Gemini"**
 *   with the Add and Delete all buttons and the list of instructions under it.
 *
 * ⚠️ **The whole page is covered, Memory switch included** — it is one page, and the switch for
 * the instructions themselves sits on it, so leaving any of it open would leave a way to turn them
 * off. ⚠️ Matching "your instructions for gemini" alone would also fire on a chat in which those
 * words were typed; a heading pair is required, or the page's own address.
 */
internal object GeminiInstructions {

    /** The page's own path on gemini.google.com, when the address bar shows it. */
    private const val PATH = "/saved-info"

    /** Whether [url] is the instructions page. Needs [GeminiTempChat.isGeminiHost] as well. */
    fun isInstructionsUrl(url: String?): Boolean {
        if (!GeminiTempChat.isGeminiHost(url)) return false
        val path = url!!.lowercase().substringAfter("://").substringAfter('/', "")
        return ("/" + path).startsWith(PATH)
    }

    /**
     * Whether [text] is the instructions page: its section heading AND the page's own heading.
     * Curly apostrophes folded, as [GeminiTempChat.isStartScreenText] does — free, and Google
     * changes its quotes between builds.
     */
    fun isInstructionsText(text: String): Boolean {
        val t = text.lowercase().replace('’', '\'')
        return "your instructions for gemini" in t &&
            ("personal context" in t || "saved info" in t)
    }

    /** The page, from what the scan has: its address, or its text (the Custom Tab shows only the
     *  host, so the text is what decides there). */
    fun isInstructionsPage(url: String?, text: String): Boolean =
        isInstructionsUrl(url) || isInstructionsText(text)
}
