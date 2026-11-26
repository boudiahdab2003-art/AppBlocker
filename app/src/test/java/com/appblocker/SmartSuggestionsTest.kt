package com.appblocker

import com.appblocker.data.AiSuggestions
import com.appblocker.data.AppCategory
import com.appblocker.data.CoachActions
import com.appblocker.data.SmartSuggestions
import com.appblocker.data.SmartSuggestions.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Invariant 113 — smart suggestions. His picks: Smart mix, no undo, top of the Blocking tab. */
class SmartSuggestionsTest {

    private val all: (String) -> Boolean = { true }

    @Test
    fun `the most used apps come first, at most three`() {
        val today = mapOf("yt" to 130, "chrome" to 40, "tiktok" to 90, "maps" to 20, "notes" to 16)
        val s = SmartSuggestions.suggest(today, emptyMap(), hour = 14, eligible = all)
        assertEquals(listOf("yt", "tiktok", "chrome"), s.map { it.pkg })
        assertTrue(s.all { it.action == Action.LOCK_HOUR })
    }

    @Test
    fun `late at night it offers the lock until morning`() {
        val s = SmartSuggestions.suggest(mapOf("tiktok" to 30), emptyMap(), hour = 23, eligible = all)
        assertEquals(Action.LOCK_TONIGHT, s.single().action)
        assertEquals(Action.LOCK_TONIGHT, SmartSuggestions.suggest(mapOf("tiktok" to 30), emptyMap(), 2, all).single().action)
        assertEquals(Action.LOCK_HOUR, SmartSuggestions.suggest(mapOf("tiktok" to 30), emptyMap(), 21, all).single().action)
    }

    @Test
    fun `a heavy week earns a daily limit of half the average`() {
        val s = SmartSuggestions.suggest(mapOf("chrome" to 5), mapOf("chrome" to 7 * 100), hour = 10, eligible = all)
        assertEquals(Action.DAILY_LIMIT, s.single().action)
        assertEquals(45, s.single().limitMinutes) // 100/2 = 50, down to a quarter hour
        assertEquals(15, SmartSuggestions.limitFor(45))
        assertEquals(60, SmartSuggestions.limitFor(400))
    }

    @Test
    fun `an app already limited is offered a lock instead`() {
        val s = SmartSuggestions.suggest(
            mapOf("chrome" to 50), mapOf("chrome" to 7 * 100), hour = 10, eligible = all, limits = mapOf("chrome" to 60),
        )
        assertEquals(Action.LOCK_HOUR, s.single().action)
    }

    /** Bug found 26 Nov: an app already blocked for the day by its limit still got a button. */
    @Test
    fun `an app that has used up its limit today is not suggested`() {
        val s = SmartSuggestions.suggest(
            mapOf("chrome" to 70, "yt" to 20), emptyMap(), hour = 10, eligible = all, limits = mapOf("chrome" to 60),
        )
        assertEquals(listOf("yt"), s.map { it.pkg })
    }

    @Test
    fun `light use and ineligible apps are never suggested`() {
        assertTrue(SmartSuggestions.suggest(mapOf("notes" to 10), mapOf("notes" to 70), 10, all).isEmpty())
        val s = SmartSuggestions.suggest(
            mapOf("launcher" to 300, "yt" to 60), emptyMap(), 10, eligible = { it != "launcher" },
        )
        assertEquals(listOf("yt"), s.map { it.pkg })
    }

    @Test
    fun `tonight's lock runs to six in the morning`() {
        assertEquals(7 * 60 * 60_000L, SmartSuggestions.msUntilMorning(23, 0))
        assertEquals(4 * 60 * 60_000L + 30 * 60_000L, SmartSuggestions.msUntilMorning(1, 30))
        assertEquals(24 * 60 * 60_000L, SmartSuggestions.msUntilMorning(6, 0))
    }

    /** Bugs found 26 Nov in the first version of the card. */
    @Test
    fun `the card reads this week, spares messages, and saves a tap even if the tab closes`() {
        val card = cardCode()
        assertTrue("\"this week\" must be the past seven days", "UsageTracker.pastWeekAppMinutes(" in card)
        // lastWeekAppMinutes is the week BEFORE this one — used only for the trend (v2).
        assertFalse("val week = UsageTracker.lastWeekAppMinutes" in card)
        assertTrue("val weekBefore = UsageTracker.lastWeekAppMinutes(context)" in card)
        assertTrue("the messaging app must never be suggested", "LowBatteryLock.smsPackage(context)" in card)
        assertFalse("a tap must not ride the screen's scope", "rememberCoroutineScope" in card)
        assertTrue("applyScope.launch" in card)
    }

    private fun cardCode(): String =
        listOf(File("src/main/java/com/appblocker/ui/SuggestionsCard.kt"), File("app/src/main/java/com/appblocker/ui/SuggestionsCard.kt"))
            .first { it.isFile }.readLines()
            .filterNot { val t = it.trim(); t.startsWith("*") || t.startsWith("//") || t.startsWith("/**") }
            .joinToString(" ")

    /** No undo (his pick): the card only ever adds locks and lowers limits. */
    @Test
    fun `the card has no way to undo`() {
        val card = listOf(File("src/main/java/com/appblocker/ui/SuggestionsCard.kt"), File("app/src/main/java/com/appblocker/ui/SuggestionsCard.kt"))
            .first { it.isFile }.readLines()
            .filterNot { val t = it.trim(); t.startsWith("*") || t.startsWith("//") || t.startsWith("/**") }
            .joinToString("\n")
        for (bad in listOf("delete(", "unlock", "Undo", "suggest_undo", "AlertDialog", "isBlocked = false", "enabled = false")) {
            assertFalse("the suggestions card must not contain '$bad'", bad in card)
        }
        // The taps go through the coach's own strengthen-only actions (invariants 114/115), where a
        // lock only lengthens and a limit only ever goes down.
        assertTrue("CoachActions.apply(" in card)
        assertEquals(30, com.appblocker.data.QuickActions.lowerLimit(45, 30))
        assertEquals(30, com.appblocker.data.QuickActions.lowerLimit(30, 45))
        assertEquals(45, com.appblocker.data.QuickActions.lowerLimit(null, 45))
    }

    // ---- v2 (invariant 115): "very very dumb" --------------------------------------------------------

    @Test
    fun `only distracting apps are ever suggested, never a messenger`() {
        assertTrue(SmartSuggestions.distracting("com.google.android.youtube", AppCategory.ENTERTAINMENT, false))
        assertTrue(SmartSuggestions.distracting("com.zhiliaoapp.musically", AppCategory.SOCIAL, false))
        assertTrue(SmartSuggestions.distracting("com.some.game", AppCategory.GAMES, false))
        assertTrue("a browser counts", SmartSuggestions.distracting("com.android.chrome", AppCategory.PRODUCTIVITY, true))
        // Filed under Social media by the category map, but needed.
        assertFalse(SmartSuggestions.distracting("com.whatsapp", AppCategory.SOCIAL, false))
        assertFalse(SmartSuggestions.distracting("org.telegram.messenger", AppCategory.SOCIAL, false))
        assertFalse(SmartSuggestions.distracting("com.google.android.apps.maps", AppCategory.TRAVEL, false))
        assertFalse(SmartSuggestions.distracting("com.bank.app", AppCategory.OTHER, false))
        assertFalse(SmartSuggestions.distracting("com.school.app", AppCategory.EDUCATION, false))
    }

    @Test
    fun `not always one hour`() {
        assertEquals(60, SmartSuggestions.lockMinutesFor(20))
        assertEquals(120, SmartSuggestions.lockMinutesFor(75))
        assertEquals(180, SmartSuggestions.lockMinutesFor(200))
        val s = SmartSuggestions.suggest(mapOf("yt" to 200, "tt" to 70), emptyMap(), hour = 14, eligible = all)
        assertEquals(listOf(180, 120), s.map { it.lockMinutes })
    }

    @Test
    fun `browsers are only offered at night`() {
        val today = mapOf("chrome" to 90, "yt" to 30)
        assertEquals(listOf("yt"), SmartSuggestions.suggest(today, emptyMap(), 14, all, browsers = setOf("chrome")).map { it.pkg })
        assertEquals(listOf("chrome", "yt"), SmartSuggestions.suggest(today, emptyMap(), 23, all, browsers = setOf("chrome")).map { it.pkg })
    }

    @Test
    fun `the coach's picks are checked before they are shown`() {
        val apps = listOf(
            CoachActions.App("com.google.android.youtube", "YouTube"),
            CoachActions.App("com.whatsapp", "WhatsApp"),
        )
        val raw = """{"suggestions":[
            {"emoji":"🌙","title":"Close YouTube for tonight","reason":"2h 40m today, most after 22:00","action":{"type":"lock_until_morning","app":"YouTube"}},
            {"emoji":"🔒","title":"Pause WhatsApp","reason":"x","action":{"type":"lock_app","app":"WhatsApp","minutes":60}},
            {"emoji":"🛡","title":"Strict until midnight","reason":"Two relapses after 23:00 this month","action":{"type":"strict","minutes":90}},
            {"emoji":"🔓","title":"Unlock everything","reason":"x","action":{"type":"unlock_all"}}
        ]}"""
        val picks = AiSuggestions.parse(raw, apps) { it == "com.google.android.youtube" }
        assertEquals(listOf(CoachActions.Type.LOCK_TONIGHT, CoachActions.Type.STRICT), picks.map { it.action.type })
        assertEquals("Close YouTube for tonight", picks[0].title)
        assertTrue(AiSuggestions.parse("not json", apps) { true }.isEmpty())
    }

    /** Measured 26 Nov 2026: the model offered "15 min/day" for an app averaging 0 minutes. */
    @Test
    fun `a limit needs a real average to cut, and must cut it`() {
        fun cand(avg: Int) = AiSuggestions.Candidate("p", "X", 10, avg, null, 1, false)
        val limit30 = CoachActions.Action(CoachActions.Type.DAILY_LIMIT, "p", "X", 30)
        assertFalse(AiSuggestions.worthIt(limit30, cand(0)))
        assertFalse(AiSuggestions.worthIt(limit30, cand(29)))
        assertFalse("a limit above the average cuts nothing", AiSuggestions.worthIt(limit30, cand(30)))
        assertTrue(AiSuggestions.worthIt(limit30, cand(90)))
        assertTrue(AiSuggestions.worthIt(CoachActions.Action(CoachActions.Type.STRICT, minutes = 60), null))
    }

    /** Measured 26 Nov 2026: at 14:10 the model offered to lock Chrome until 06:00. */
    @Test
    fun `no browser lock and no lock until morning in the middle of the day`() {
        val chrome = CoachActions.Action(CoachActions.Type.LOCK_APP, "chrome", "Chrome", 120)
        val tonight = CoachActions.Action(CoachActions.Type.LOCK_TONIGHT, "yt", "YouTube")
        val tiktok = CoachActions.Action(CoachActions.Type.LOCK_APP, "tt", "TikTok", 180)
        val browsers = setOf("chrome")
        assertFalse(AiSuggestions.suitableNow(chrome, 14, browsers))
        assertFalse(AiSuggestions.suitableNow(tonight, 14, browsers))
        assertTrue(AiSuggestions.suitableNow(tiktok, 14, browsers))
        assertTrue(AiSuggestions.suitableNow(chrome, 21, browsers))
        assertTrue(AiSuggestions.suitableNow(tonight, 23, browsers))
        assertTrue(AiSuggestions.suitableNow(tonight, 1, browsers))
    }

    @Test
    fun `the coach is told to target social media and to be specific`() {
        val prompt = AiSuggestions.prompt(
            name = "the user", now = 1_800_000_000_000L,
            candidates = listOf(AiSuggestions.Candidate("p", "TikTok", 95, 70, 40, 31, false)),
            recovery = "", profile = "", setup = "",
        )
        assertTrue("social media" in prompt)
        assertTrue("never three \"lock for 1 hour\"" in prompt)
        assertTrue("TikTok: 95 min today, 70 min/day this week, up 40% on last week, opened 31x today" in prompt)
        assertTrue("only use the apps listed under CANDIDATES" in prompt)
    }
}
