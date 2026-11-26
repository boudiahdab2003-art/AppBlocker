package com.appblocker

import com.appblocker.data.AppRule
import com.appblocker.data.BlockMode
import com.appblocker.service.BlockInputs
import com.appblocker.service.BlockWhy
import com.appblocker.service.InstagramBan
import com.appblocker.service.WebContentFilter
import com.appblocker.service.decideBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **Invariant 112 — Instagram is blocked completely, with no switch.** 26 Nov 2026, his picks
 * "Block it completely" and "Never from the phone". Nothing the phone can change may lift it.
 */
class InstagramBanTest {

    private fun inputs(
        pkg: String,
        updatePaused: Boolean = false,
        quickEnforcing: Boolean = true,
        rule: AppRule? = null,
        allowlistMode: Boolean = false,
        isEssential: Boolean = false,
    ) = BlockInputs(
        pkg = pkg,
        updatePaused = updatePaused,
        lockoutRemainingMs = 0L,
        lockoutWord = null,
        strict = false,
        dangerZoneRemainingMs = 0L,
        isRealBrowser = false,
        quickEnforcing = quickEnforcing,
        allowlistEnforcing = quickEnforcing,
        rule = rule,
        allowlistMode = allowlistMode,
        isEssential = { isEssential },
        schedules = emptyList(),
        isUnsupportedBrowser = false,
        unsupportedBrowserBlockingActive = { false },
        usedMinutesToday = { 0 },
        opensToday = { 0 },
        scheduleConditionMet = { false },
        scheduleLabel = { "" },
        hourMinuteLabel = { "" },
        words = EnglishStrings,
    )

    private fun allowed(pkg: String) = AppRule(
        packageName = pkg, appLabel = "Instagram", isBlocked = false, mode = BlockMode.HARD,
        isAllowed = true,
    )

    // ---- the apps ------------------------------------------------------------------------------

    @Test
    fun `instagram and every app under its name are banned, other meta apps are not`() {
        for (p in listOf("com.instagram.android", "com.instagram.lite", "com.instagram.barcelona", "com.instagram.something.new")) {
            assertTrue(p, InstagramBan.isBannedApp(p))
        }
        for (p in listOf("com.facebook.katana", "com.facebook.orca", "com.whatsapp", "com.android.chrome", "com.instagramviewer.fake")) {
            assertFalse(p, InstagramBan.isBannedApp(p))
        }
    }

    @Test
    fun `instagram is blocked with nothing on the block list`() {
        val r = decideBlock(inputs("com.instagram.android"))
        assertEquals(BlockWhy.INSTAGRAM, r?.why)
        assertEquals("Instagram is blocked", r?.title)
    }

    @Test
    fun `nothing the phone can switch lifts it`() {
        val p = "com.instagram.android"
        assertEquals(BlockWhy.INSTAGRAM, decideBlock(inputs(p, updatePaused = true))?.why)
        assertEquals(BlockWhy.INSTAGRAM, decideBlock(inputs(p, quickEnforcing = false))?.why)
        assertEquals(BlockWhy.INSTAGRAM, decideBlock(inputs(p, allowlistMode = true, rule = allowed(p)))?.why)
        assertEquals(BlockWhy.INSTAGRAM, decideBlock(inputs(p, allowlistMode = true, isEssential = true))?.why)
        assertEquals(BlockWhy.INSTAGRAM, decideBlock(inputs(p, rule = allowed(p).copy(isBlocked = false)))?.why)
    }

    @Test
    fun `an ordinary app with no rule is still allowed`() {
        assertNull(decideBlock(inputs("com.example.notes")))
    }

    // ---- the websites ----------------------------------------------------------------------------

    private val filter = WebContentFilter(emptyList(), emptyList(), emptyList(), emptyList(), EnglishStrings)

    @Test
    fun `instagram, threads and the viewer sites are blocked in the browser`() {
        for (url in listOf(
            "instagram.com", "www.instagram.com/reels/abc", "m.instagram.com", "instagr.am/p/x",
            "threads.net/@someone", "www.threads.com", "picuki.com/profile/x",
            "google.com/search?q=picuki.com",
        )) {
            val hit = filter.checkUrl(url, emptyList(), emptyList())
            assertTrue(url, hit?.site == true)
            assertEquals(url, "Instagram is blocked", hit?.title)
        }
    }

    @Test
    fun `a site that merely shares letters is left alone`() {
        for (url in listOf("notinstagram.com", "instagrammer.com", "wikipedia.org/wiki/Instagram", "big.me")) {
            assertNull(url, InstagramBan.bannedSite(url))
        }
    }

    // ---- shape -----------------------------------------------------------------------------------

    @Test
    fun `there is no switch for it anywhere`() {
        val store = source("data/SettingsStore.kt").readText().lowercase()
        assertFalse("a setting for the Instagram ban would be a switch", "instagram" in store)
        val ban = source("service/InstagramBan.kt").readText()
        assertFalse("the ban must not read settings", "SettingsStore" in ban || "Context" in ban)
    }

    @Test
    fun `the ban is asked before every other layer`() {
        val text = source("service/BlockDecision.kt").readText()
        val body = text.substringAfter("internal fun decideBlock(i: BlockInputs): BlockReason? {")
        val first = body.lines().map { it.trim() }.first { it.startsWith("if (") }
        assertTrue("decideBlock must open with the Instagram ban: $first", "InstagramBan.isBannedApp(i.pkg)" in first)
        val url = source("service/WebContentFilter.kt").readText()
            .substringAfter("fun checkUrl(url: String,").substringBefore("for (k in userKeywords)")
        assertTrue("checkUrl must ask the ban before the keywords", "InstagramBan.bannedSite(" in url)
    }

    private fun source(path: String): File =
        listOf(File("src/main/java/com/appblocker/$path"), File("app/src/main/java/com/appblocker/$path"))
            .firstOrNull { it.isFile }
            ?: error("cannot find $path from ${File(".").absolutePath}")
}
