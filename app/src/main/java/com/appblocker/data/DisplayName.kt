package com.appblocker.data

/**
 * Everything the app does with the one name it knows about the person using it.
 *
 * Pure Kotlin with no [android.content.Context], for the same reason
 * [com.appblocker.service.BlockDecision] is: it can then be unit tested, and this logic has to
 * cope with an input the app previously never had — **an empty name**.
 *
 * Until v1.120 [SettingsStore.userName] defaulted to the owner's own name, so every caller could
 * assume a real name was there. Blanking that default (so a stranger who installs the app isn't
 * greeted by someone else's name) means "" is now a normal value on a normal phone, and the
 * strings built from it have to still read like English: "Hi !" and "on the phone of ." are what
 * a bare `substringBefore(' ')` produces.
 *
 * Blank is deliberately kept distinct from the word "You": it is the difference between "we have
 * never asked" and "they chose to be called You", and the setup wizard needs to tell them apart.
 */
internal object DisplayName {

    /** Longest name we store. Long enough for a full name, short enough to stay on one line. */
    const val MAX_LENGTH = 40

    /**
     * The stored form of a typed name: no leading/trailing space, no double spaces, capped.
     *
     * The cap trims whole characters rather than whole `Char`s — cutting at 40 UTF-16 units can
     * land between the two halves of an emoji or a non-BMP letter, which renders as a broken box
     * forever after because the damage is done at *write* time.
     */
    fun sanitize(raw: String): String {
        val collapsed = raw.trim().replace(Regex("\\s+"), " ")
        if (collapsed.length <= MAX_LENGTH) return collapsed
        val cut = collapsed.take(MAX_LENGTH)
        return (if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut).trimEnd()
    }

    /** True once the person has actually told us a name — not merely opened the app. */
    fun isSet(raw: String): Boolean = sanitize(raw).isNotEmpty()

    /** What to print where the name is the subject: the name, or "You". */
    fun display(raw: String): String = sanitize(raw).ifEmpty { "You" }

    /**
     * The first name, for prose that addresses the person ("Nice work, Sam").
     *
     * Lower-case "you" when unset, because every call site drops it mid-sentence.
     */
    fun first(raw: String): String = sanitize(raw).substringBefore(' ').ifEmpty { "you" }

    /**
     * How the person is referred to in the third person, for the AI Coach's system prompt
     * ("a screen-time app on the phone of X"). "the user" rather than "You", which would read as
     * the model being addressed.
     */
    fun possessiveSubject(raw: String): String = sanitize(raw).ifEmpty { "the user" }

    /** Up-to-two initials for the avatar circle, e.g. "Abdallah Ahdab" -> "AA". */
    fun initials(raw: String): String {
        val parts = sanitize(raw).split(' ').filter { it.isNotEmpty() }
        return when {
            parts.isEmpty() -> "?"
            parts.size == 1 -> firstGlyph(parts[0]).uppercase()
            else -> (firstGlyph(parts[0]) + firstGlyph(parts.last())).uppercase()
        }
    }

    /** The first character of [word], keeping both halves of a surrogate pair together. */
    private fun firstGlyph(word: String): String =
        if (word[0].isHighSurrogate() && word.length > 1) word.take(2) else word.take(1)
}
