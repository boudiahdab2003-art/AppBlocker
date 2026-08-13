package com.appblocker

import com.appblocker.service.KNOWN_READABLE_BROWSERS
import com.appblocker.service.WebContentFilter
import com.appblocker.service.isOmniboxId
import com.appblocker.service.looksLikeHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The word/site matcher is the most-fixed code in the app — v1.70 (false-positive blocks:
 * "it blocks and really there is nothing"), v1.88 (a bare "porn" covering non-sexual apps) and
 * v1.89 (social sites blocking pages that merely mention the app) were all matching fixes, each
 * found on the phone rather than in a test. These pin the resulting rules down.
 *
 * [WebContentFilter.check] is pure, so no Context or Robolectric is needed.
 */
class WebContentFilterTest {

    private fun filter(
        domains: List<String> = emptyList(),
        adultKeywords: List<String> = emptyList(),
        pack: List<String> = emptyList(),
    ) = WebContentFilter(domains, adultKeywords, pack)

    // ---- user keywords: the URL, not a passing mention (v1.89) --------------------------

    @Test fun userKeywordMatchesTheUrl() {
        val hit = filter().check(
            text = "some page text", url = "instagram.com/reels",
            userKeywords = listOf("instagram"), siteKeywords = emptyList(),
            adultPack = false, blockAdult = false,
        )
        assertEquals("instagram", hit?.word)
    }

    @Test fun userKeywordIgnoresAPageThatMerelyMentionsIt() {
        val hit = filter().check(
            text = "an article about instagram and its effects", url = "bbc.com/news/tech",
            userKeywords = listOf("instagram"), siteKeywords = emptyList(),
            adultPack = false, blockAdult = false,
        )
        assertNull(hit)
    }

    /** No readable omnibox (fullscreen video, a non-browser app) must never become a bypass. */
    @Test fun withoutAUrlTheKeywordFallsBackToPageText() {
        val hit = filter().check(
            text = "instagram", url = null,
            userKeywords = listOf("instagram"), siteKeywords = emptyList(),
            adultPack = false, blockAdult = false,
        )
        assertNotNull(hit)
    }

    // ---- blocked-app websites: URL only, never page text (v1.89) ------------------------

    @Test fun siteKeywordBlocksTheSiteItself() {
        val hit = filter().check(
            text = "feed", url = "https://www.facebook.com/",
            userKeywords = emptyList(), siteKeywords = listOf("facebook"),
            adultPack = false, blockAdult = false,
        )
        assertTrue(hit?.site == true)
    }

    @Test fun siteKeywordIsSkippedEntirelyWithoutAUrl() {
        val hit = filter().check(
            text = "i was reading about facebook today", url = null,
            userKeywords = emptyList(), siteKeywords = listOf("facebook"),
            adultPack = false, blockAdult = false,
        )
        assertNull(hit)
    }

    @Test fun siteKeywordDoesNotFireOnAPageMentioningTheName() {
        val hit = filter().check(
            text = "facebook facebook facebook", url = "news.ycombinator.com",
            userKeywords = emptyList(), siteKeywords = listOf("facebook"),
            adultPack = false, blockAdult = false,
        )
        assertNull(hit)
    }

    // ---- checkUrl: the undebounced address-bar path -------------------------------------

    /**
     * The fast path must reach the verdict the full scan would have reached a quarter-second
     * later — arriving sooner may not change what happens, or a site blocks one way and not the
     * other depending only on how quickly the page settled.
     *
     * Near-tautological while [WebContentFilter.check] delegates to checkUrl, and deliberately
     * kept anyway: it is the tripwire for the obvious future "fix" of re-implementing one of the
     * two in place. It cannot catch a bug the two share — the per-case tests below do that.
     */
    @Test fun checkUrlAgreesWithTheFullCheckOnEveryUrlCase() {
        val f = filter(pack = listOf("anal"))
        val words = listOf("instagram")
        val sites = listOf("facebook")
        for (url in listOf(
            "instagram.com/reels/xyz", "m.instagram.com/reels", "https://www.facebook.com/",
            "instagrammers.example.com", "news.ycombinator.com", "wikipedia.org/wiki/cat",
            "facebook.com/marketplace", "  ",
        )) {
            val fast = f.checkUrl(url, words, sites)
            val full = f.check(
                text = url, url = url, userKeywords = words, siteKeywords = sites,
                adultPack = false, blockAdult = false,
            )
            assertEquals(url, full?.title, fast?.title)
            assertEquals(url, full?.word, fast?.word)
            assertEquals(url, full?.site, fast?.site)
        }
    }

    @Test fun checkUrlBlocksABlockedAppsSite() {
        val hit = filter().checkUrl("instagram.com/reels/xyz", emptyList(), listOf("instagram"))
        assertTrue(hit?.site == true)
    }

    /** A typed word must still read as NOT a site, because that is what arms the app lockout —
     *  the fast path must not turn a word block into the gentler website block. */
    @Test fun checkUrlMarksAUserKeywordAsNotASite() {
        val hit = filter().checkUrl("instagram.com/reels/xyz", listOf("instagram"), emptyList())
        assertEquals("instagram", hit?.word)
        assertFalse(hit!!.site)
    }

    @Test fun checkUrlLeavesUnrelatedSitesAlone() {
        val f = filter()
        assertNull(f.checkUrl("wikipedia.org/wiki/cat", listOf("instagram"), listOf("facebook")))
        assertNull(f.checkUrl("instagrammers.example.com", emptyList(), listOf("instagram")))
        assertNull(f.checkUrl("   ", listOf("instagram"), listOf("facebook")))
    }

    // ---- checkUrlAdult: the adult layers on the fast path -------------------------------

    @Test fun checkUrlAdultCatchesAPackWordInTheAddress() {
        val hit = filter(pack = listOf("anal"))
            .checkUrlAdult("x.com/anal/1", adultPack = true, blockAdult = false)
        assertEquals("anal", hit?.word)
    }

    @Test fun checkUrlAdultCatchesAListedSite() {
        val hit = filter(domains = listOf("example-adult.com"))
            .checkUrlAdult("https://example-adult.com/x", adultPack = false, blockAdult = true)
        assertNotNull(hit)
    }

    /** Same whole-word rule as the page scan — the v1.70 false-positive class must not come
     *  back through a second door. */
    @Test fun checkUrlAdultKeepsWholeWordMatching() {
        val f = filter(pack = listOf("anal"))
        assertNull(f.checkUrlAdult("en.wikipedia.org/wiki/analysis", adultPack = true, blockAdult = false))
    }

    @Test fun checkUrlAdultRespectsBothSwitches() {
        val f = filter(domains = listOf("example-adult.com"), pack = listOf("anal"))
        assertNull(f.checkUrlAdult("example-adult.com/anal", adultPack = false, blockAdult = false))
        assertNull(f.checkUrlAdult("wikipedia.org", adultPack = true, blockAdult = true))
    }

    // ---- looksLikeHost: "he actually went there" vs "he is still typing" ----------------

    @Test fun aLoadedAddressLooksLikeAHost() {
        assertTrue(looksLikeHost("instagram.com"))
        assertTrue(looksLikeHost("https://www.instagram.com/reels/x"))
        assertTrue(looksLikeHost("m.instagram.com"))
    }

    /** The owner asked for the fast block to wait until he has really gone to the site, so
     *  half-typed text and searches must read as "not yet". Declining costs nothing: the page
     *  scan still catches a blocked word typed into the address bar. */
    @Test fun typingAndSearchingDoNotLookLikeAHost() {
        assertFalse(looksLikeHost("instagram"))              // mid-word, no dot yet
        assertFalse(looksLikeHost("instagram."))             // still typing the suffix
        assertFalse(looksLikeHost("how to quit instagram"))  // a search
        assertFalse(looksLikeHost(""))
        assertFalse(looksLikeHost("   "))
    }

    // ---- isOmniboxId: which browsers the address bar can be found in ---------------------

    /**
     * **Why this rule is a suffix.** Blocking a site (rather than a word) is matched against the
     * address bar only — never the page text — so if the address bar can't be found, website
     * blocking doesn't degrade, it goes *silent*. The lookup used to be the single literal
     * `"<pkg>:id/url_bar"`, which is Chrome's; every other browser therefore opened blocked
     * sites freely, with nothing on screen to say so. The owner found it in Brave.
     *
     * The id carries the browser's own package as its prefix, so matching the tail is what makes
     * one rule cover all of them.
     */
    @Test fun everyChromiumForkAddressBarIsRecognised() {
        for (pkg in listOf("com.android.chrome", "com.brave.browser", "com.microsoft.emmx",
            "com.opera.browser", "com.vivaldi.browser")) {
            assertTrue(pkg, isOmniboxId("$pkg:id/url_bar"))
        }
        assertTrue(isOmniboxId("com.sec.android.app.sbrowser:id/location_bar_edit_text"))
        assertTrue(isOmniboxId("org.mozilla.firefox:id/mozac_browser_toolbar_url_view"))
    }

    /** `endsWith`, not `contains` — the toolbar is full of views named around the url bar, and
     *  treating the scrim or the wrapper as the address would feed junk to the site matcher. */
    @Test fun viewsMerelyNamedAroundTheAddressBarAreNotIt() {
        assertFalse(isOmniboxId("com.brave.browser:id/url_bar_scrim"))
        assertFalse(isOmniboxId("com.brave.browser:id/url_bar_container"))
        assertFalse(isOmniboxId("com.android.chrome:id/search_box_text"))
        assertFalse(isOmniboxId(""))
    }

    /**
     * **The two lists that must agree.** `KNOWN_READABLE_BROWSERS` is the claim "we can read this
     * browser's address bar", and the only thing that makes the claim true is [isOmniboxId]
     * recognising that browser's toolbar id. Naming a browser in one and not the other is how the
     * pair goes wrong, and it fails in the silent direction: the browser stops being blanket-
     * blocked *and* cannot actually be filtered, so it becomes the one thing this whole area keeps
     * producing — a browser with no blocking and nothing on screen to say so.
     *
     * Chromium's `url_bar` covers all but two, which are asserted by name.
     */
    @Test fun everyBrowserClaimedReadableHasAnOmniboxIdWeRecognise() {
        val exceptions = mapOf(
            "com.sec.android.app.sbrowser" to "location_bar_edit_text",
            "org.mozilla.firefox" to "mozac_browser_toolbar_url_view",
            "org.mozilla.firefox_beta" to "mozac_browser_toolbar_url_view",
            "org.mozilla.fenix" to "mozac_browser_toolbar_url_view",
            "org.mozilla.focus" to "mozac_browser_toolbar_url_view",
        )
        for (pkg in KNOWN_READABLE_BROWSERS) {
            val id = "$pkg:id/${exceptions[pkg] ?: "url_bar"}"
            assertTrue("$pkg is claimed readable but $id is not recognised", isOmniboxId(id))
        }
    }

    /** A seed that has been emptied silently reintroduces the original bug: every browser
     *  "unsupported", so every browser blanket-blocked when that switch is on. */
    @Test fun theReadableSeedStillHasChromeAndBrave() {
        assertTrue("com.android.chrome" in KNOWN_READABLE_BROWSERS)
        assertTrue("com.brave.browser" in KNOWN_READABLE_BROWSERS)
    }

    // ---- whole-word matching: the v1.70 false-positive class ----------------------------

    @Test fun packWordDoesNotFireInsideALongerInnocentWord() {
        val f = filter(pack = listOf("anal"))
        assertNull(f.check("data analysis results", null, emptyList(), emptyList(), true, false))
        assertNull(f.check("banal conversation", null, emptyList(), emptyList(), true, false))
    }

    @Test fun packWordFiresAsAWholeWord() {
        val hit = filter(pack = listOf("anal"))
            .check("anal", null, emptyList(), emptyList(), true, false)
        assertEquals("anal", hit?.word)
    }

    /** '.' and '/' are boundaries, so a bare domain word still matches inside a URL. */
    @Test fun dotsAndSlashesCountAsWordBoundaries() {
        val hit = filter().check(
            text = "reels feed", url = "m.instagram.com/reels/xyz",
            userKeywords = listOf("instagram"), siteKeywords = emptyList(),
            adultPack = false, blockAdult = false,
        )
        assertNotNull(hit)
    }

    @Test fun keywordDoesNotFireGluedToAnotherWord() {
        val hit = filter().check(
            text = "a blog", url = "instagrammers.example.com",
            userKeywords = listOf("instagram"), siteKeywords = emptyList(),
            adultPack = false, blockAdult = false,
        )
        assertNull(hit)
    }

    // ---- Arabic spelling variants fold to the one stored form ---------------------------

    @Test fun arabicAlefVariantsMatchTheStoredForm() {
        // stored "احلام"; the text uses the hamza form "أحلام"
        val hit = filter(pack = listOf("احلام"))
            .check("أحلام", null, emptyList(), emptyList(), true, false)
        assertNotNull(hit)
    }

    @Test fun arabicDiacriticsAndTatweelAreIgnored() {
        val hit = filter(pack = listOf("كلمه"))
            .check("كــلمة", null, emptyList(), emptyList(), true, false)
        assertNotNull(hit)
    }

    // ---- layers and switches -------------------------------------------------------------

    @Test fun blankTextNeverBlocks() {
        assertNull(filter(pack = listOf("anal")).check("   ", null, listOf("x"), listOf("y"), true, true))
    }

    @Test fun packOnlyAppliesWhenTheSwitchIsOn() {
        val f = filter(pack = listOf("anal"))
        assertNull(f.check("anal", null, emptyList(), emptyList(), false, false))
        assertNotNull(f.check("anal", null, emptyList(), emptyList(), true, false))
    }

    @Test fun adultListsOnlyApplyWhenTheSwitchIsOn() {
        val f = filter(domains = listOf("example-adult.com"), adultKeywords = listOf("xxxsearch"))
        assertNull(f.check("example-adult.com", null, emptyList(), emptyList(), false, false))
        assertNotNull(f.check("example-adult.com", null, emptyList(), emptyList(), false, true))
        assertNotNull(f.check("xxxsearch", null, emptyList(), emptyList(), false, true))
    }

    /** A user keyword wins over the pack, so the message names the word the user chose. */
    @Test fun userKeywordTakesPriorityOverThePack() {
        val hit = filter(pack = listOf("anal")).check(
            text = "anal reddit", url = null,
            userKeywords = listOf("reddit"), siteKeywords = emptyList(),
            adultPack = true, blockAdult = false,
        )
        assertEquals("reddit", hit?.word)
    }

    // ---- the shipped word packs themselves -----------------------------------------------

    /**
     * The packs are hand-curated text files (see the header in adult_words_pack.txt). Sloppy
     * entries — stray spaces, capitals, duplicates — are exactly what produced the v1.70
     * false positives, and the loader lowercases/trims silently, so nothing else would notice.
     * Unit tests run from the module directory, so the assets are just files here.
     */
    @Test fun shippedPacksAreCleanlyCurated() {
        for (name in listOf("adult_words_pack.txt", "adult_keywords.txt", "adult_domains.txt")) {
            val file = File("src/main/assets/$name")
            assertTrue("Missing asset $name", file.exists())
            val entries = file.readLines()
                .map { it.substringBefore('#') }
                .filter { it.isNotBlank() }
            val untrimmed = entries.filter { it != it.trim() }
            assertEquals("$name: entries with stray whitespace", emptyList<String>(), untrimmed)
            val upper = entries.filter { it != it.lowercase() }
            assertEquals("$name: entries that aren't lowercase", emptyList<String>(), upper)
            val dupes = entries.map { it.trim() }.groupBy { it }.filterValues { it.size > 1 }.keys
            assertEquals("$name: duplicate entries", emptySet<String>(), dupes)
            assertFalse("$name is empty", entries.isEmpty())
        }
    }

    /**
     * **The words used to talk about porn are not porn.**
     *
     * The owner was blocked on YouTube and inside other blocker apps, because the pack carried the
     * general nouns and adjectives — "pornography", "porno", Arabic "اباحية" — and those are the
     * vocabulary of an anti-porn store listing, a news piece, a sermon, a recovery video. The pack
     * was blocking the material that argues against the thing it exists to block.
     *
     * Pinned as a test because this is the easiest kind of entry to add back "for coverage": each
     * of these looks like an obvious omission until you remember where it actually appears. The
     * compounds asserted below are what carries the coverage instead — nobody writes "free porn"
     * except to find it.
     */
    @Test fun thePackHasNoGeneralWordsForPornItself() {
        val entries = File("src/main/assets/adult_words_pack.txt").readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
        val general = listOf(
            "porn", "porno", "pornos", "pornography", "pornographic",
            "بورنو", "اباحي", "اباحية", "الاباحي", "الاباحية", "اباحيات",
        )
        assertEquals(
            "these describe porn rather than being it, and blocked people discussing it",
            emptyList<String>(),
            general.filter { it in entries },
        )
        // …and the specific side is still doing the work.
        for (kept in listOf("pornhub", "free porn", "porn video", "افلام اباحية", "سكس")) {
            assertTrue("$kept must stay in the pack", kept in entries)
        }
    }

    /**
     * **A brand name on its own is a thing people talk about, not a thing they're looking at.**
     *
     * Same failure as the general nouns above, one step along: the pack is matched against **page
     * text**, not only the address, so a bare "onlyfans" blocked any page that merely mentioned
     * it — a news article, a review, a thread about quitting, a recovery video. The word is how
     * the subject is discussed, and blocking discussion of the thing is not blocking the thing.
     *
     * Protection is not lost, and that is what makes the removal safe rather than a concession:
     * **`onlyfans.com` is still on the adult domain list**, so the site itself is blocked by
     * address, and the phrases people type when they are actually looking for the content are
     * still pack words. Only the bare mention stopped firing.
     */
    @Test fun bareBrandNamesAreNotPackWords() {
        val entries = File("src/main/assets/adult_words_pack.txt").readLines()
            .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
        assertFalse(
            "a bare brand name blocks every page that mentions it, including ones arguing " +
                "against it — the site is blocked by domain instead",
            "onlyfans" in entries,
        )
        // The searches somebody makes to FIND the content stay blocked…
        for (kept in listOf("onlyfans leak", "onlyfans leaks")) {
            assertTrue("$kept must stay in the pack", kept in entries)
        }
        // …and so does the site itself, which is the protection that actually matters.
        val domains = File("src/main/assets/adult_domains.txt").readLines()
            .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
        assertTrue("onlyfans.com must stay on the domain list", "onlyfans.com" in domains)
    }
}
