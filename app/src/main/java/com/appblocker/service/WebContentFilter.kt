package com.appblocker.service

import android.content.Context
import com.appblocker.data.ServiceHealth

/**
 * Decides whether some on-screen text (a web address or search query) should be
 * blocked: user keywords first, then the bundled adult word pack (if enabled),
 * then — if enabled — the bundled adult site lists. Lists are loaded once from assets.
 *
 * [check]'s optional `url` is the browser's omnibox text: when present, user keywords
 * match the site/search the user is ON rather than anything the page happens to
 * mention. The adult layers always match the full text — never weaker on purpose.
 */
// The constructor is internal (not private) purely so unit tests can build a filter from three
// plain word lists — no Context, no assets. Production code must still go through [get].
class WebContentFilter internal constructor(
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

        /** So a permanently broken asset doesn't write to prefs on every scan. */
        @Volatile private var loadFailureReported = false

        /**
         * The shared filter, built from the bundled lists.
         *
         * **A filter that failed to load is deliberately not cached.** Every list is read through
         * [runCatching], so a failure produced an empty list — and an empty list matches nothing
         * while the adult switches still say ON. Caching that turned one transient read failure
         * into the adult layers being silently off for the rest of the process's life: no crash,
         * no warning, and blocking that fails *open*. Nobody would ever know, because a block that
         * never happens is invisible.
         *
         * Not hypothetical for this app in particular: the in-app updater installs a new APK over
         * a service that keeps running, and an asset path replaced under a live process is exactly
         * when reads fail. That is also the moment [com.appblocker.data.UpdatePause] switches every
         * other layer off, leaving the adult layer as the only thing still meant to be working.
         *
         * So a partial load is returned but not kept: the next scan tries again, and the failure
         * is recorded once in [ServiceHealth], which the Profile screen surfaces.
         */
        fun get(context: Context): WebContentFilter {
            INSTANCE?.let { return it }
            return synchronized(this) {
                INSTANCE ?: run {
                    val domains = readLines(context, "adult_domains.txt")
                    val keywords = readLines(context, "adult_keywords.txt")
                    val pack = readLines(context, "adult_words_pack.txt")?.map(::normalizeArabic)
                    val loaded = domains != null && keywords != null && pack != null
                    WebContentFilter(domains.orEmpty(), keywords.orEmpty(), pack.orEmpty())
                        .also { if (loaded) INSTANCE = it }
                }
            }
        }

        /** Null when the asset could not be read at all — distinct from a legitimately empty
         *  file, so [get] can tell "nothing to match" from "we failed to look". */
        private fun readLines(context: Context, asset: String): List<String>? =
            runCatching {
                context.assets.open(asset).bufferedReader().useLines { lines ->
                    lines.map { it.trim().lowercase() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .toList()
                }
            }.onFailure {
                if (!loadFailureReported) {
                    loadFailureReported = true
                    ServiceHealth.recordError(context, "assets/$asset", it)
                }
            }.getOrNull()

        /** Whole-word substring search: a match only counts when it isn't glued to another
         *  letter/digit on either side (works for Latin and Arabic alike).
         *
         *  Internal, like [normalizeArabic] beside it, because the watcher's off-switch guard
         *  matches other accessibility services' labels the same way — "Files" must not fire
         *  inside "Profiles". One matcher, so the two can't drift apart. */
        internal fun containsWord(text: String, word: String): Boolean {
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
         *  alef variants → ا, ة → ه, ى → ي; strips tatweel and harakat (diacritics).
         *
         *  Internal rather than private because the watcher's off-switch guard folds Settings
         *  page text through it too (see GUARD_TEXT_MARKERS). One folding, so a marker written
         *  in one place can't quietly stop matching text folded in another. */
        internal fun normalizeArabic(s: String): String {
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
