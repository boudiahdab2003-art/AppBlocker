package com.appblocker.service

import android.content.Context
import android.os.SystemClock
import com.appblocker.R
import com.appblocker.data.ServiceHealth
import com.appblocker.data.Words

/**
 * What the browser's address bar says — three answers, not two.
 *
 * It used to be a `String?`, and the null meant two opposite things: *"the bar is hidden, I could
 * not read it"* (fullscreen video, a browser whose toolbar we can't parse — keep matching the page
 * text, or hiding the toolbar becomes a bypass) and *"the bar is right there and it is empty"* (a
 * start page — there is no page at all). Only the first meaning existed, so a blank bar got the
 * fullscreen treatment and everything on screen was matched. That blocked the owner for *opening
 * Chrome* (20 Aug 2026): a porn word out of his own history, sitting in a most-visited tile or the
 * suggestion list, read as "he is on an adult page" and locked the browser for half an hour.
 *
 * `docs/BLOCKING_INVARIANTS.md` had this function listed under "Not yet swept" — *"where else does
 * a null answer mean 'no' instead of 'don't know'?"* — which is exactly the shape it turned out to
 * have. [Blank] is the missing third answer, and note which direction it runs in: it is a
 * **successful** measurement of "there is no page here", where [Unreadable] is a failed one.
 */
sealed interface BrowserAddress {
    /** A real address — or the text being typed into the bar, which is the same question asked
     *  earlier ("where is he going?"), and is why a typed search is still caught. */
    data class At(val url: String) : BrowserAddress

    /** The bar is on screen and empty: a start/new-tab page, or a bar waiting to be typed into.
     *  Nothing on that screen is a page the user opened. */
    data object Blank : BrowserAddress

    /** No address bar could be read: fullscreen video, a browser none of the tiers recognise, or
     *  an app that has no address bar at all. Invariant 4 — a failed measurement is never
     *  evidence, so every layer keeps falling back to the page text exactly as it always has. */
    data object Unreadable : BrowserAddress

    /** The address when there is one, for the callers that only want the string. */
    val urlOrNull: String? get() = (this as? At)?.url
}

/**
 * Decides whether some on-screen text (a web address or search query) should be
 * blocked: user keywords first, then the bundled adult word pack (if enabled),
 * then — if enabled — the bundled adult site lists. Lists are loaded once from assets.
 *
 * [check]'s [BrowserAddress] is the browser's omnibox: when it holds an address, the layers that
 * ask *"which site is this?"* match that address rather than anything the page happens to mention.
 * The word pack is the one layer that reads the page itself, and it is curated and whole-word
 * matched for exactly that job.
 */
// The constructor is internal (not private) purely so unit tests can build a filter from three
// plain word lists — no Context, no assets. Production code must still go through [get].
class WebContentFilter internal constructor(
    private val adultDomains: List<String>,
    private val adultKeywords: List<String>,
    private val packWords: List<String>,
    /** The wider net, matched ONLY while the danger zone is armed — see `danger_words.txt` for
     *  the curation rule, which is deliberately the opposite of the pack's. */
    private val dangerWords: List<String> = emptyList(),

    /**
     * The cover's wording, in the app's language.
     *
     * A constructor parameter rather than a lookup, so the unit tests — which build this
     * directly — can hand it the shipped English resources and keep asserting on real wording.
     * Production passes [Words.of], backed by [com.appblocker.data.AppLocale.wrap]: a filter
     * that formatted its hits with the *phone's* locale would put an English line on an otherwise
     * Arabic block screen.
     */
    private val words: Words,
) {
    /** [site] = matched because the user blocked an app and this is that app's WEBSITE (not a
     *  typed word). Callers treat site hits more gently (cover the page, but don't lock the
     *  whole browser), and they never fire on a mere page mention.
     *
     *  [adult] = one of the three built-in adult layers fired rather than a word the user typed
     *  in themselves. It changes nothing about the cover; it is there so the diagnostic log can
     *  tell the two apart. Both used to record `why=word`, so a report about over-blocking could
     *  not say which layer did it — invariant 17, and the same split v1.132 made for word/site. */
    data class Hit(
        val title: String,
        val message: String,
        val word: String? = null,
        val site: Boolean = false,
        val adult: Boolean = false,
    )

    /**
     * @param siteKeywords domain words for apps the user blocked (e.g. "facebook"). Matched
     *   against the URL ONLY — never the page text — so facebook.com blocks but an article that
     *   merely says "facebook" does not. Skipped entirely when no URL could be read.
     */
    fun check(
        text: String,
        address: BrowserAddress,
        userKeywords: List<String>,
        siteKeywords: List<String>,
        adultPack: Boolean,
        blockAdult: Boolean,
        /**
         * Match `danger_words.txt` as well.
         *
         * **Named for what it does, not for why it is on.** It was `inDangerZone`, and then a
         * second window started setting it — five different words keep the wider list running for
         * 24 hours after the browser lockdown has ended, so "in the danger zone" became false for
         * half the cases that switch it on. A flag whose name and meaning drift apart is
         * invariant 17's bug, and the last one cost a whole report.
         */
        wideList: Boolean = false,
    ): Hit? {
        // **A blank address bar is a start page, and a start page is not a page.**
        //
        // Everything on it belongs to the phone rather than to anything the user opened: the
        // most-visited tiles, the suggestion list that drops down the moment the bar is tapped,
        // recently closed tabs, the news feed. All of it is built out of his own history, which
        // is why matching it was worse than useless — it blocked him for *opening Chrome*, and
        // then locked the browser for half an hour, over a word he had not gone anywhere near.
        //
        // Nothing is lost by declining: the instant he types, the bar carries that text, the
        // caller hands it over as [BrowserAddress.At], and every layer below runs against it.
        // What stops counting is only what the phone shows him about where he has already been.
        if (address is BrowserAddress.Blank) return null
        if (text.isBlank()) return null
        val lower = text.lowercase()

        // User keywords match the URL when the caller could read one (so a page merely
        // MENTIONING "instagram" doesn't block — only being on instagram.com or searching
        // for it does), and fall back to the whole visible text when it couldn't
        // (fullscreen video, non-browser app) so a hidden omnibox is never a bypass.
        val host = address.urlOrNull?.lowercase()?.takeIf { it.isNotBlank() }
        if (host != null) {
            // All three URL layers, in order — the same two calls the undebounced address-bar
            // check makes on its own, so the fast path and the full scan can never disagree
            // about what a URL means.
            checkUrl(host, userKeywords, siteKeywords)?.let { return it }
            // **The adult site and search lists are address lists**, and this is where they
            // belong. `adult_domains.txt` is nothing but hostnames; `adult_keywords.txt` says so
            // in its own header ("if any appears in a URL or search text") and is matched as a
            // plain substring precisely so glued forms like `freeporntube` still hit. Both answer
            // *"which site is this?"* — and page text cannot answer that question, it answers
            // "what does this screen mention?". Run against a page they blocked a news article
            // about porn, a recovery video, a page that merely links to a listed site, and the
            // owner's browser start page. Same lesson as v1.105's "mentions us was never the
            // right signal", and the same one the word pack learnt from the other side when it
            // dropped "pornography"/"porno" for being what people *say about* porn.
            checkUrlAdult(host, adultPack, blockAdult)?.let { return it }
        } else {
            for (k in userKeywords) {
                // Whole-word like the pack below: a bare keyword ("instagram") must not fire on
                // loose UI text that merely contains it ("instagrammer", icon labels).
                val kw = k.trim().lowercase()
                if (kw.isNotEmpty() && containsWord(lower, kw)) {
                    return Hit(
                        words.get(R.string.block_word_title),
                        words.get(R.string.block_word_message, kw), kw,
                    )
                }
            }
            // Site keywords are deliberately skipped with no URL: "block the website, not the
            // word", so a page that merely mentions a blocked app's name never blocks.
        }
        // The word pack is the one adult layer that reads the **page**, and the only one built
        // for it: ~100%-porn vocabulary, curated against exactly this kind of over-block, matched
        // whole-word. An adult page that never names itself in its address is still caught here.
        if (adultPack && packWords.isNotEmpty()) {
            // Pack words match whole-word only (a short entry like "anal" or Arabic "كس" must
            // not fire inside "analysis" or "كسر"), against Arabic-normalized text so spelling
            // variants (alef forms, diacritics, tatweel) still match.
            val norm = normalizeArabic(lower)
            for (w in packWords) {
                if (containsWord(norm, w)) {
                    return Hit(
                        words.get(R.string.block_adult_title),
                        words.get(R.string.block_adult_word_message, w), w, adult = true,
                    )
                }
            }
        }
        // No address could be read at all — the toolbar is hidden behind a fullscreen video, or
        // this is a browser none of the tiers recognise. The address lists fall back to the page
        // text exactly as they always have: a failed measurement is not permission (invariant 4),
        // and scrolling a toolbar away must never become the way out.
        if (host == null && blockAdult) {
            for (d in adultDomains) {
                if (lower.contains(d)) return adultSiteHit(d)
            }
            for (k in adultKeywords) {
                if (lower.contains(k)) return adultSearchHit(k)
            }
        }

        // The danger zone's wider net. Everything above is what runs on an ordinary day; this
        // runs only in the hour after three different adult words inside thirty minutes.
        //
        // **It matters in apps rather than browsers.** Every browser is already shut outright for
        // that hour (decideBlock), so the point of widening is everything else — and the watcher
        // scans every app while the zone is armed, whether or not "check every app" is switched
        // on the rest of the time.
        if (wideList) {
            // **[dangerWords] ONLY, and adult_keywords.txt deliberately NOT.** That list is
            // matched as a plain substring, and its own header says every entry must be
            // unambiguous inside innocent text — a promise made about a URL, where "porn" only
            // ever appears because somebody went somewhere. In PAGE TEXT the same entry matches
            // an anti-porn app's own description, a news article, a forum thread about quitting.
            //
            // Widening it here for the hour was exactly the mistake adult_words_pack.txt was
            // trimmed three times to fix, and its header states the rule this broke: *the words
            // used to TALK about porn are not porn*. Worse, it contradicted danger_words.txt's
            // own promise that help-seeking material is never blocked — in the one hour someone
            // is most likely to go looking for it. The owner spotted it: "why porn is a blocked
            // word its weird it can be everywhere and doesnt say anything."
            //
            // The widening the zone needs is danger_words.txt, which is curated FOR page text and
            // matched whole-word. Reaching for a second list was reaching for the wrong tool.
            for (w in dangerWords) {
                if (containsWord(lower, w)) return dangerHit(w)
            }
        }
        return null
    }

    /** Names the word like every other layer does, and says plainly that this one is blocked
     *  only because of the hour — so an ordinary word being blocked can be understood rather
     *  than read as the app breaking. */
    private fun dangerHit(word: String) = Hit(
        words.get(R.string.block_danger_title),
        words.get(R.string.block_danger_word_message, word),
        word,
        adult = true,
    )

    /** Both adult-list messages name what they found, the way the pack's already does.
     *
     *  "That search or page looks like adult content" named nothing, so when it fired on the
     *  owner's start page neither of us could say *which* word had done it — the block screen
     *  could not be argued with, and the report could not be acted on. The word stays on the
     *  device: [com.appblocker.data.BlockLog] and the bug report still carry no subjects. */
    private fun adultSiteHit(domain: String) =
        Hit(
            words.get(R.string.block_adult_site_title),
            words.get(R.string.block_adult_site_message, domain), domain, adult = true,
        )

    private fun adultSearchHit(word: String) =
        Hit(
            words.get(R.string.block_adult_title),
            words.get(R.string.block_adult_search_message, word), word, adult = true,
        )

    /** A site the phone learned by catching it in two different browsers. Says so, because a
     *  block the user cannot account for is one they argue with — and this is the only list in
     *  the app that the app wrote itself. */
    private fun learnedSiteHit(domain: String) = Hit(
        words.get(R.string.block_learned_site_title),
        words.get(R.string.block_learned_site_message, domain),
        domain,
        adult = true,
    )

    /**
     * The two layers that need nothing but the address bar: the user's own words, then the
     * websites of the apps they blocked. Both whole-word against [url].
     *
     * Split out of [check] so the watcher can run it the moment the omnibox changes, without
     * waiting for the debounce and the page-text walk [check] needs for the adult layers. That
     * wait was worth seconds of a blocked site on every visit — long enough that leaving and
     * coming straight back always paid out, which is what made a dismissed block worth
     * retrying. Nothing here reads the page, so being early costs nothing.
     */
    fun checkUrl(url: String, userKeywords: List<String>, siteKeywords: List<String>): Hit? {
        val host = url.lowercase().takeIf { it.isNotBlank() } ?: return null
        for (k in userKeywords) {
            // '.' and '/' are word boundaries, so a bare "instagram" still matches inside
            // "instagram.com/reels" while "instagrammer" doesn't.
            val kw = k.trim().lowercase()
            if (kw.isNotEmpty() && containsWord(host, kw)) {
                return Hit(
                        words.get(R.string.block_word_title),
                        words.get(R.string.block_word_message, kw), kw,
                    )
            }
        }
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
        return null
    }

    /**
     * The adult layers — pack words, then the adult site list, then the adult search list —
     * matched against [url] alone, in the order [check] runs them.
     *
     * Kept separate from [checkUrl] rather than folded into it because in [check] these three
     * match the **page text**, not the address: an adult page that never names itself in its URL
     * must still be caught. Making [check] delegate them to a URL matcher would quietly narrow
     * what it blocks. So this is purely additive — the address-bar check gets the adult pack at
     * the same speed as everything else, and [check] keeps reading the page exactly as before.
     */
    fun checkUrlAdult(
        url: String,
        adultPack: Boolean,
        blockAdult: Boolean,
        /** Hosts this phone worked out for itself — see [com.appblocker.data.DangerZone] and
         *  `learnedDomains`. Treated exactly like the shipped adult domains: they are evidence
         *  from two different browsers, not a guess. */
        learnedDomains: Set<String> = emptySet(),
    ): Hit? {
        val lower = url.lowercase().takeIf { it.isNotBlank() } ?: return null
        // Before the switchable layers: a site caught in two different browsers was established
        // by this phone rather than shipped by me, and turning the adult lists off should not
        // quietly discard what it learned.
        for (d in learnedDomains) {
            if (lower.contains(d)) return learnedSiteHit(d)
        }
        if (adultPack && packWords.isNotEmpty()) {
            val norm = normalizeArabic(lower)
            for (w in packWords) {
                if (containsWord(norm, w)) {
                    return Hit(
                        words.get(R.string.block_adult_title),
                        words.get(R.string.block_adult_word_message, w), w, adult = true,
                    )
                }
            }
        }
        if (blockAdult) {
            for (d in adultDomains) {
                if (lower.contains(d)) return adultSiteHit(d)
            }
            for (k in adultKeywords) {
                if (lower.contains(k)) return adultSearchHit(k)
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
                    // A partial load is retried, but not on every single scan. Refusing to cache
                    // it at all meant that while an asset was unreadable, every content event in
                    // every browser re-opened four files and re-parsed them — the word pack and
                    // the 288-entry danger list included — on the way to deciding whether to
                    // block. The retry is what matters, not its frequency: the condition this
                    // recovers from is an update swapping the assets under a live process, which
                    // resolves in seconds and is not made to resolve faster by asking constantly.
                    val now = SystemClock.elapsedRealtime()
                    partial?.let { if (now - partialAt < PARTIAL_RETRY_MS) return@run it }
                    val domains = readLines(context, "adult_domains.txt")
                    val keywords = readLines(context, "adult_keywords.txt")
                    val pack = readLines(context, "adult_words_pack.txt")?.map(::normalizeArabic)
                    val danger = readLines(context, "danger_words.txt")?.map(::normalizeArabic)
                    val loaded = domains != null && keywords != null && pack != null &&
                        danger != null
                    WebContentFilter(
                        domains.orEmpty(), keywords.orEmpty(), pack.orEmpty(), danger.orEmpty(),
                        words = Words.of(context),
                    ).also {
                        if (loaded) {
                            INSTANCE = it
                            partial = null
                        } else {
                            partial = it
                            partialAt = now
                        }
                    }
                }
            }
        }

        /** The last incomplete load, and when it happened. Held only until [PARTIAL_RETRY_MS]
         *  is up, so a broken read costs one parse an interval rather than one per scan. */
        @Volatile private var partial: WebContentFilter? = null
        @Volatile private var partialAt = 0L
        private const val PARTIAL_RETRY_MS = 5_000L

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
