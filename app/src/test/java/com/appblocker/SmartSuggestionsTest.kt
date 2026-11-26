package com.appblocker

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
            mapOf("chrome" to 50), mapOf("chrome" to 7 * 100), hour = 10, eligible = all, limited = setOf("chrome"),
        )
        assertEquals(Action.LOCK_HOUR, s.single().action)
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
        assertTrue("ItemLocks.lockFor(" in card)
        assertTrue("a limit already set is only ever lowered", "minOf(existing?.limitMinutes" in card)
    }
}
