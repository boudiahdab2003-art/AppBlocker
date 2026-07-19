package com.appblocker.service

import android.content.Context

/**
 * Decides whether some on-screen text (a web address or search query) should be
 * blocked: user keywords first, then the bundled adult word pack (if enabled),
 * then — if enabled — the bundled adult site lists. Lists are loaded once from assets.
 *
 * [check]'s optional `url` is the browser's omnibox text: when present, user keywords
 * match the site/search the user is ON rather than anything the page happens to
 * mention. The adult layers always match the full text — never weaker on purpose.
 */
class WebContentFilter private constructor(
    private val adultDomains: List<String>,
    private val adultKeywords: List<String>,
    private val packWords: List<String>,
) {
    /** [site] = matched because the user blocked an app and this is that app's WEBSITE (not a
     *  typed word). Callers treat site hits more gently (cover the page, but don't lock the
     *  whole browser), and they never fire on a mere page mention. */
    data class Hit(val title: String, val message: String, val word: String? = null, val site: Boolean = false)

    /**
     * @param siteKeywords domain words for apps the user blocked (e.g. "facebook"). Matched
     *   against the URL ONLY — never the page text — so facebook.com blocks but an article that
     *   merely says "facebook" does not. Skipped entirely when no URL could be read.
     */
    fun check(
        text: String,
        url: String?,
        userKeywords: List<String>,
        siteKeywords: List<String>,
        adultPack: Boolean,
        blockAdult: Boolean,
    ): Hit? {
        if (text.isBlank()) return null
        val lower = text.lowercase()

        // User keywords match the URL when the caller could read one (so a page merely
        // MENTIONING "instagram" doesn't block — only being on instagram.com or searching
        // for it does), and fall back to the whole visible text when it couldn't
        // (fullscreen video, non-browser app) so a hidden omnibox is never a bypass.
        val keywordHay = url?.lowercase()?.takeIf { it.isNotBlank() } ?: lower
        for (k in userKeywords) {
            // Whole-word like the pack below: a bare keyword ("instagram") must not fire on
            // loose UI text that merely contains it ("instagrammer", icon labels). '.' and '/'
            // are boundaries, so it still matches inside "instagram.com/reels".
            val kw = k.trim().lowercase()
            if (kw.isNotEmpty() && containsWord(keywordHay, kw)) {
                return Hit("Blocked word", "“$kw” is on your blocked list.", kw)
            }
        }
        // Blocked-app websites: match the URL only. "Block the website, not the word" — so a
        // blocked app's site is covered, but a page that just mentions its name is not, and no
        // URL means no match (never blocks on page text).
        val host = url?.lowercase()?.takeIf { it.isNotBlank() }
        if (host != null) {
            for (k in siteKeywords) {
                val kw = k.trim().lowercase()
                if (kw.isNotEmpty() && containsWord(host, kw)) {
                    return Hit(
                        "Website blocked",
                        "This site is blocked because its app is on your blocked list.",
                        site = true,
                    )
                }
            }
        }
        if (adultPack && packWords.isNotEmpty()) {
            // Pack words match whole-word only (a short entry like "anal" or Arabic "كس" must
            // not fire inside "analysis" or "كسر"), against Arabic-normalized text so spelling
            // variants (alef forms, diacritics, tatweel) still match.
            val norm = normalizeArabic(lower)
            for (w in packWords) {
                if (containsWord(norm, w)) {
                    return Hit("Adult content blocked", "“$w” is a blocked adult word.", w)
                }
            }
        }
        if (blockAdult) {
            for (d in adultDomains) {
                if (lower.contains(d)) {
                    return Hit("Adult site blocked", "This site is on the adult-content list.")
                }
            }
            for (k in adultKeywords) {
                if (lower.contains(k)) {
                    return Hit("Adult content blocked", "That search or page looks like adult content.")
                }
            }
        }
        return null
    }

    companion object {
        @Volatile private var INSTANCE: WebContentFilter? = null

        fun get(context: Context): WebContentFilter =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: WebContentFilter(
                    readLines(context, "adult_domains.txt"),
                    readLines(context, "adult_keywords.txt"),
                    readLines(context, "adult_words_pack.txt").map(::normalizeArabic),
                ).also { INSTANCE = it }
            }

        private fun readLines(context: Context, asset: String): List<String> =
            runCatching {
                context.assets.open(asset).bufferedReader().useLines { lines ->
                    lines.map { it.trim().lowercase() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .toList()
                }
            }.getOrDefault(emptyList())

        /** Whole-word substring search: a match only counts when it isn't glued to another
         *  letter/digit on either side (works for Latin and Arabic alike). */
        private fun containsWord(text: String, word: String): Boolean {
            if (word.isEmpty()) return false
            var i = text.indexOf(word)
            while (i >= 0) {
                val beforeOk = i == 0 || !text[i - 1].isLetterOrDigit()
                val end = i + word.length
                val afterOk = end >= text.length || !text[end].isLetterOrDigit()
                if (beforeOk && afterOk) return true
                i = text.indexOf(word, i + 1)
            }
            return false
        }

        /** Folds common Arabic spelling variants so one stored form catches them all:
         *  alef variants → ا, ة → ه, ى → ي; strips tatweel and harakat (diacritics). */
        private fun normalizeArabic(s: String): String {
            val sb = StringBuilder(s.length)
            for (c in s) {
                when (c) {
                    'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا')
                    'ة' -> sb.append('ه')
                    'ى' -> sb.append('ي')
                    'ـ' -> {} // tatweel (elongation) — drop
                    in 'ً'..'ٟ' -> {} // harakat/diacritics — drop
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }
    }
}
