package com.appblocker

import com.appblocker.service.WebContentFilter
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
}
