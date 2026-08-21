package com.appblocker

import com.appblocker.service.BrowserAddress
import com.appblocker.service.KNOWN_BROWSERS
import com.appblocker.service.KNOWN_READABLE_BROWSERS
import com.appblocker.service.WebContentFilter
import com.appblocker.service.isOmniboxId
import com.appblocker.service.isStartPageId
import com.appblocker.service.looksLikeHost
import com.appblocker.service.looseAddress
import com.appblocker.service.soleHost
import com.appblocker.service.typedPortion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The word/site matcher is the most-fixed code in the app — v1.70 (false-positive blocks:
 * "it blocks and really there is nothing"), v1.88 (a bare "porn" covering non-sexual apps),
 * v1.89 (social sites blocking pages that merely mention the app) and v1.133 (a block screen for
 * *opening Chrome*, off the owner's own history in the start page) were all matching fixes, each
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

    // The three answers the address bar can give, short enough to read at a glance in a call.
    private fun at(url: String) = BrowserAddress.At(url)
    private val blank = BrowserAddress.Blank
    private val unreadable = BrowserAddress.Unreadable

    // ---- user keywords: the URL, not a passing mention (v1.89) --------------------------

    @Test fun userKeywordMatchesTheUrl() {
        val hit = filter().check(
            text = "some page text", address = at("instagram.com/reels"),
            userKeywords = listOf("instagram"), siteKeywords = emptyList(),
            adultPack = false, blockAdult = false,
        )
        assertEquals("instagram", hit?.word)
    }

    @Test fun userKeywordIgnoresAPageThatMerelyMentionsIt() {
        val hit = filter().check(
            text = "an article about instagram and its effects", address = at("bbc.com/news/tech"),
            userKeywords = listOf("instagram"), siteKeywords = emptyList(),
            adultPack = false, blockAdult = false,
        )
        assertNull(hit)
    }

    /** No readable omnibox (fullscreen video, a non-browser app) must never become a bypass. */
    @Test fun withoutAUrlTheKeywordFallsBackToPageText() {
        val hit = filter().check(
            text = "instagram", address = unreadable,
            userKeywords = listOf("instagram"), siteKeywords = emptyList(),
            adultPack = false, blockAdult = false,
        )
        assertNotNull(hit)
    }

    // ---- blocked-app websites: URL only, never page text (v1.89) ------------------------

    @Test fun siteKeywordBlocksTheSiteItself() {
        val hit = filter().check(
            text = "feed", address = at("https://www.facebook.com/"),
            userKeywords = emptyList(), siteKeywords = listOf("facebook"),
            adultPack = false, blockAdult = false,
        )
        assertTrue(hit?.site == true)
    }

    @Test fun siteKeywordIsSkippedEntirelyWithoutAUrl() {
        val hit = filter().check(
            text = "i was reading about facebook today", address = unreadable,
            userKeywords = emptyList(), siteKeywords = listOf("facebook"),
            adultPack = false, blockAdult = false,
        )
        assertNull(hit)
    }

    @Test fun siteKeywordDoesNotFireOnAPageMentioningTheName() {
        val hit = filter().check(
            text = "facebook facebook facebook", address = at("news.ycombinator.com"),
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
                text = url, address = at(url), userKeywords = words, siteKeywords = sites,
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

    // ---- tier 4: reading a toolbar that is a label rather than a field ------------------------

    /**
     * **The rule that replaces editability**, and the reason tier 4 is allowed to exist.
     *
     * Tier 3 only trusts an *editable* node, on the argument that a page's links are not editable
     * and a browser's address bar is the only editable field in its chrome. Mi Browser's address
     * is a label in a bottom bar, so nothing matched and website blocking was silently off there —
     * the owner's screenshot of instagram.com open with Instagram blocked.
     *
     * Dropping editability lets a toolbar label through, and would equally let a suggestion list
     * or a bookmarks panel through — which would raise a cover over a page the user is not on.
     * A toolbar shows the current address once; those lists show several. So disagreement must
     * answer nothing, and that is what these pin.
     */
    @Test fun oneAddressInTheChromeIsTheAddress() {
        assertEquals("instagram.com", soleHost(listOf("instagram.com")))
    }

    @Test fun theSameAddressTwiceIsStillOneAddress() {
        // A toolbar label plus a chip/tab title showing the same site is one reading, not a
        // disagreement — grouping by host rather than by string is what keeps this readable.
        assertEquals("instagram.com", soleHost(listOf("instagram.com", "https://instagram.com/")))
    }

    /** The suggestion-list case. Two different sites on screen means we do not know which one the
     *  user is on, and guessing is the over-block this whole area exists to avoid. */
    @Test fun twoDifferentAddressesAnswerNothing() {
        assertNull(soleHost(listOf("instagram.com", "reddit.com")))
        assertNull(soleHost(listOf("instagram.com", "reddit.com", "news.ycombinator.com")))
    }

    @Test fun nothingHostShapedAnswersNothing() {
        assertNull(soleHost(emptyList()))
        assertNull(soleHost(listOf("Search or type URL", "Bookmarks", "")))
    }

    /** Non-address chrome text alongside one real address must not count as disagreement — it is
     *  filtered by shape first, or every browser with a "Home" button would answer null. */
    @Test fun labelsThatArentAddressesAreIgnoredRatherThanCountedAgainstIt() {
        assertEquals(
            "instagram.com",
            soleHost(listOf("Home", "instagram.com", "Tabs", "Search or type URL")),
        )
    }

    // ---- Chrome finishes the address for you (21 Aug 2026) ------------------------------

    /**
     * Reported as: *"the app is blocking my browsers even without opening any blocked link, and
     * saying the link is blocked because the app is blocked"* — while typing a search, in Chrome.
     *
     * Chrome inline-autocompletes the omnibox out of his own history: two letters in, the node's
     * text is the whole address with the completed half selected. The site layer read that as
     * where he was, and covered the screen for a page he never opened.
     */
    @Test fun chromesInlineAutocompleteIsNotSomethingTheUserTyped() {
        // "yo" typed, "utube.com" completed and selected.
        assertEquals("yo", typedPortion("youtube.com", 2, 11))
    }

    /** Tapping the bar on a loaded page selects the WHOLE address. That is the real address and
     *  must keep matching — a completion always has something typed in front of it. */
    @Test fun selectAllIsTheRealAddressNotACompletion() {
        assertEquals("youtube.com", typedPortion("youtube.com", 0, 11))
    }

    /** Every way of failing to read the selection keeps today's behaviour, which is the blocking
     *  direction: a failed measurement is never permission (invariant 4). */
    @Test fun anUnreadableSelectionChangesNothing() {
        assertEquals("youtube.com", typedPortion("youtube.com", -1, -1))   // not reported
        assertEquals("youtube.com", typedPortion("youtube.com", 5, 5))     // collapsed cursor
        assertEquals("youtube.com", typedPortion("youtube.com", 4, 2))     // backwards
        assertEquals("youtube.com", typedPortion("youtube.com", 2, 99))    // past the end
        assertEquals("youtube.com", typedPortion("youtube.com", -3, 7))    // negative start
    }

    /** He typed the site himself — the completion adds nothing, and it still blocks. */
    @Test fun typingTheWholeSiteIsStillHisOwnText() {
        assertEquals("instagram.com", typedPortion("instagram.com", 13, 13))
        assertEquals("instagram", typedPortion("instagram.com", 9, 13))
    }

    /**
     * The whole report, at the layer that raised the cover: the completed address is not a site
     * he is on, and the address he really goes to still is.
     */
    @Test fun aHalfTypedSearchDoesNotBlockButTheSiteStillDoes() {
        val f = filter()
        val words = listOf("youtube")
        assertNull(f.check("start page", at("yo"), emptyList(), words, false, false))
        assertTrue(f.check("feed", at("youtube.com"), emptyList(), words, false, false)?.site == true)
    }

    // ---- a bar we could NAME outranks one we only recognised by its shape ----------------

    /**
     * Invariant 20 said only the id-matched tiers may *answer* `Blank`. It did not say they
     * outrank the others, so the text tiers kept walking after tier 1 had found the bar empty and
     * a lone bookmark row was returned as the address — the start page back in front of the site
     * layer through a different door.
     */
    @Test fun aBlankBarRefusesTheTextShapedTiers() {
        assertNull(looseAddress(blankBar = true, startPage = false, editableHost = "youtube.com",
            chromeLabels = listOf("reddit.com")))
    }

    /** Chrome's new tab page carries no `url_bar` at all — the fakebox is the same statement. */
    @Test fun aStartPageSearchBoxRefusesThemToo() {
        assertNull(looseAddress(blankBar = false, startPage = true, editableHost = null,
            chromeLabels = listOf("youtube.com")))
    }

    /** With no bar found either way, the tiers answer exactly as they did before, in order. */
    @Test fun withNoBarFoundTheTextTiersStillAnswerInOrder() {
        assertEquals(
            "instagram.com",
            looseAddress(false, false, editableHost = "instagram.com",
                chromeLabels = listOf("reddit.com")),
        )
        assertEquals(
            "reddit.com",
            looseAddress(false, false, editableHost = null, chromeLabels = listOf("reddit.com")),
        )
        assertNull(looseAddress(false, false, null, listOf("a.com", "b.com")))
        assertNull(looseAddress(false, false, null, emptyList()))
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
            "com.sec.android.app.sbrowser.beta" to "location_bar_edit_text",
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

    /**
     * **The OEM browsers, named one at a time on purpose.**
     *
     * On a Huawei, Oppo/OnePlus or Vivo the built-in browser is usually the phone's default, which
     * puts it in the strict set with no list's help — and while it was in neither readable list it
     * was blanket-blocked with no way out, because a blocked browser sits under our own cover and
     * can never be read well enough to promote itself. The phone's own browser, permanently
     * blocked, on every brand except the two the owner happens to own.
     *
     * The general pairing tests above would still pass if this whole group were deleted, since
     * they only check the entries that *are* there. Naming them individually is what makes a
     * tidy-up fail loudly instead of quietly re-blocking those phones.
     */
    @Test fun theReadableSeedCoversTheOemBrowsers() {
        for (pkg in listOf(
            "com.huawei.browser", "com.hihonor.browser",
            "com.heytap.browser", "com.nearme.browser", "com.coloros.browser",
            "com.vivo.browser",
            "com.mi.globalbrowser", "com.miui.browser",
            "com.sec.android.app.sbrowser",
        )) {
            assertTrue("$pkg must be seeded readable or that phone's browser is blocked outright",
                pkg in KNOWN_READABLE_BROWSERS)
        }
    }

    /**
     * **A browser claimed readable must also be one the app can find.**
     *
     * `KNOWN_READABLE_BROWSERS` only ever *exempts* a package from the blanket block; it is
     * `KNOWN_BROWSERS` that gets a package detected in the first place. A name in the first and
     * not the second is a package the app has an opinion about and no way to notice — which is
     * how `com.mi.globalbrowser` sat in one list and not the other. Harmless today only because
     * the loose query happened to find it.
     */
    @Test fun everyReadableBrowserIsAlsoOneWeKnowToLookFor() {
        for (pkg in KNOWN_READABLE_BROWSERS) {
            assertTrue("$pkg is claimed readable but is not in KNOWN_BROWSERS", pkg in KNOWN_BROWSERS)
        }
    }

    // ---- whole-word matching: the v1.70 false-positive class ----------------------------

    @Test fun packWordDoesNotFireInsideALongerInnocentWord() {
        val f = filter(pack = listOf("anal"))
        assertNull(f.check("data analysis results", unreadable, emptyList(), emptyList(), true, false))
        assertNull(f.check("banal conversation", unreadable, emptyList(), emptyList(), true, false))
    }

    @Test fun packWordFiresAsAWholeWord() {
        val hit = filter(pack = listOf("anal"))
            .check("anal", unreadable, emptyList(), emptyList(), true, false)
        assertEquals("anal", hit?.word)
    }

    /** '.' and '/' are boundaries, so a bare domain word still matches inside a URL. */
    @Test fun dotsAndSlashesCountAsWordBoundaries() {
        val hit = filter().check(
            text = "reels feed", address = at("m.instagram.com/reels/xyz"),
            userKeywords = listOf("instagram"), siteKeywords = emptyList(),
            adultPack = false, blockAdult = false,
        )
        assertNotNull(hit)
    }

    @Test fun keywordDoesNotFireGluedToAnotherWord() {
        val hit = filter().check(
            text = "a blog", address = at("instagrammers.example.com"),
            userKeywords = listOf("instagram"), siteKeywords = emptyList(),
            adultPack = false, blockAdult = false,
        )
        assertNull(hit)
    }

    // ---- Arabic spelling variants fold to the one stored form ---------------------------

    @Test fun arabicAlefVariantsMatchTheStoredForm() {
        // stored "احلام"; the text uses the hamza form "أحلام"
        val hit = filter(pack = listOf("احلام"))
            .check("أحلام", unreadable, emptyList(), emptyList(), true, false)
        assertNotNull(hit)
    }

    @Test fun arabicDiacriticsAndTatweelAreIgnored() {
        val hit = filter(pack = listOf("كلمه"))
            .check("كــلمة", unreadable, emptyList(), emptyList(), true, false)
        assertNotNull(hit)
    }

    // ---- layers and switches -------------------------------------------------------------

    @Test fun blankTextNeverBlocks() {
        assertNull(filter(pack = listOf("anal")).check("   ", unreadable, listOf("x"), listOf("y"), true, true))
    }

    @Test fun packOnlyAppliesWhenTheSwitchIsOn() {
        val f = filter(pack = listOf("anal"))
        assertNull(f.check("anal", unreadable, emptyList(), emptyList(), false, false))
        assertNotNull(f.check("anal", unreadable, emptyList(), emptyList(), true, false))
    }

    @Test fun adultListsOnlyApplyWhenTheSwitchIsOn() {
        val f = filter(domains = listOf("example-adult.com"), adultKeywords = listOf("xxxsearch"))
        assertNull(f.check("example-adult.com", unreadable, emptyList(), emptyList(), false, false))
        assertNotNull(f.check("example-adult.com", unreadable, emptyList(), emptyList(), false, true))
        assertNotNull(f.check("xxxsearch", unreadable, emptyList(), emptyList(), false, true))
    }

    /** A user keyword wins over the pack, so the message names the word the user chose. */
    @Test fun userKeywordTakesPriorityOverThePack() {
        val hit = filter(pack = listOf("anal")).check(
            text = "anal reddit", address = unreadable,
            userKeywords = listOf("reddit"), siteKeywords = emptyList(),
            adultPack = true, blockAdult = false,
        )
        assertEquals("reddit", hit?.word)
    }

    // ---- the start-page search box, matched the way the address bar is ------------------

    /**
     * The second route to "there is no page here", for the moment Chrome's toolbar has no
     * address bar in the tree at all and the address has moved into the page as a fakebox.
     * Suffix-matched for the same reason [isOmniboxId] is: the id carries the browser's own
     * package, so the tail is what covers the whole Chromium fork family at once.
     */
    @Test fun everyChromiumForkStartPageBoxIsRecognised() {
        for (pkg in listOf("com.android.chrome", "com.brave.browser", "com.microsoft.emmx")) {
            assertTrue(pkg, isStartPageId("$pkg:id/search_box_text"))
        }
    }

    /** Same `endsWith` discipline as the omnibox ids — a neighbouring view must not qualify. */
    @Test fun viewsMerelyNamedAroundTheStartPageBoxAreNotIt() {
        assertFalse(isStartPageId("com.android.chrome:id/search_box_text_container"))
        assertFalse(isStartPageId("com.android.chrome:id/url_bar"))
        assertFalse(isStartPageId("com.android.chrome:id/tile_view_title"))
    }

    // ---- the start page is not a page (v1.133) -------------------------------------------
    //
    // Reported as: opened Chrome, had not typed anything, "it looks like a porn content". A blank
    // address bar was indistinguishable from an unreadable one, so the whole screen was matched —
    // and a browser start page is made of the user's own history: most-visited tiles, the
    // suggestion list, recently closed tabs, the feed.

    @Test fun aBlankAddressBarMeansTheAdultListsMatchNothing() {
        val f = filter(domains = listOf("pornhub.com"), adultKeywords = listOf("porn"))
        assertNull(f.check("pornhub.com free porn", blank, emptyList(), emptyList(), false, true))
    }

    @Test fun aBlankAddressBarMeansThePackMatchesNothingEither() {
        val f = filter(pack = listOf("xnxx"))
        assertNull(f.check("xnxx most visited", blank, emptyList(), emptyList(), true, false))
    }

    /** Same rule, same reason: a suggestion out of his history is not a site he is on. */
    @Test fun aBlankAddressBarMeansTheUsersOwnWordsMatchNothingEither() {
        val f = filter()
        assertNull(f.check("instagram.com recently closed", blank, listOf("instagram"), emptyList(), false, false))
    }

    /**
     * The safety argument for all three above, and the reason declining costs nothing: the moment
     * he types, the bar carries that text and the caller hands it over as an address.
     */
    @Test fun typingIntoTheEmptyBarIsStillCaught() {
        val f = filter(adultKeywords = listOf("porn"), pack = listOf("xnxx"))
        assertNotNull(f.check("porn videos", at("porn videos"), emptyList(), emptyList(), false, true))
        assertNotNull(f.check("xnxx", at("xnxx"), emptyList(), emptyList(), true, false))
        assertNotNull(
            f.check("instagram", at("instagram"), listOf("instagram"), emptyList(), false, false),
        )
    }

    // ---- an address list may only be matched against an address --------------------------

    @Test fun theAdultSearchListNoLongerFiresOnAPageThatMerelyMentionsIt() {
        val f = filter(adultKeywords = listOf("porn"))
        val hit = f.check(
            text = "a news article about porn addiction and how to quit",
            address = at("news.example.com/health"),
            userKeywords = emptyList(), siteKeywords = emptyList(),
            adultPack = false, blockAdult = true,
        )
        assertNull(hit)
    }

    @Test fun theAdultSiteListNoLongerFiresOnAPageThatOnlyLinksToOne() {
        val f = filter(domains = listOf("pornhub.com"))
        val hit = f.check(
            text = "sites we block for you: pornhub.com, xvideos.com",
            address = at("news.example.com/health"),
            userKeywords = emptyList(), siteKeywords = emptyList(),
            adultPack = false, blockAdult = true,
        )
        assertNull(hit)
    }

    @Test fun theAdultListsStillCatchTheAddressItself() {
        val f = filter(domains = listOf("pornhub.com"), adultKeywords = listOf("porn"))
        assertNotNull(f.check("whatever", at("pornhub.com/x"), emptyList(), emptyList(), false, true))
        assertNotNull(f.check("whatever", at("freeporntube.example"), emptyList(), emptyList(), false, true))
    }

    /**
     * **The no-bypass case.** No address bar could be read at all — a fullscreen video, a browser
     * none of the tiers recognise — so the address lists fall back to the page text exactly as
     * they always have. A failed measurement is not permission (invariant 4), and scrolling the
     * toolbar away must never become the way out.
     */
    @Test fun withNoAddressBarTheAdultListsStillReadThePage() {
        val f = filter(domains = listOf("pornhub.com"), adultKeywords = listOf("porn"))
        assertNotNull(f.check("watch porn now", unreadable, emptyList(), emptyList(), false, true))
        assertNotNull(f.check("pornhub.com player", unreadable, emptyList(), emptyList(), false, true))
    }

    /**
     * The word pack keeps reading the page, which is what makes narrowing the other two safe: an
     * adult page that never names itself in its address is still caught by its own vocabulary.
     */
    @Test fun theWordPackStillReadsThePageOfARealSite() {
        val f = filter(pack = listOf("anal"))
        val hit = f.check(
            text = "anal", address = at("some-random-host.example/watch"),
            userKeywords = emptyList(), siteKeywords = emptyList(),
            adultPack = true, blockAdult = false,
        )
        assertEquals("anal", hit?.word)
    }

    // ---- a block the owner can read, and a report that can be acted on -------------------

    /** "That search or page looks like adult content" named nothing, so when it fired on his
     *  start page neither of us could say which word had done it. */
    @Test fun theAdultListsNameWhatTheyFound() {
        val f = filter(domains = listOf("pornhub.com"), adultKeywords = listOf("porn"))
        val search = f.check("watch porn now", unreadable, emptyList(), emptyList(), false, true)
        assertEquals("porn", search?.word)
        assertTrue(search!!.message.contains("porn"))
        val site = f.check("pornhub.com player", unreadable, emptyList(), emptyList(), false, true)
        assertEquals("pornhub.com", site?.word)
        assertTrue(site!!.message.contains("pornhub.com"))
    }

    /** The log has to tell a word the owner chose from a list he never touched — the cover for
     *  opening Chrome recorded `why=word`, which points the next fix at the wrong layer. */
    @Test fun onlyTheBuiltInAdultLayersAreMarkedAdult() {
        val f = filter(domains = listOf("pornhub.com"), adultKeywords = listOf("porn"), pack = listOf("anal"))
        assertTrue(f.check("watch porn", unreadable, emptyList(), emptyList(), false, true)!!.adult)
        assertTrue(f.check("pornhub.com", unreadable, emptyList(), emptyList(), false, true)!!.adult)
        assertTrue(f.check("anal", unreadable, emptyList(), emptyList(), true, false)!!.adult)
        assertFalse(f.check("reddit", unreadable, listOf("reddit"), emptyList(), false, false)!!.adult)
        assertFalse(f.checkUrl("facebook.com", emptyList(), listOf("facebook"))!!.adult)
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
