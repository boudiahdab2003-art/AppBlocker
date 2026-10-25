package com.appblocker

import com.appblocker.data.BugReport
import com.appblocker.data.OngoingReport
import com.appblocker.data.StoppageHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Invariant 91: the report filed while a stoppage or a pause is still going on. */
class OngoingReportTest {

    private fun ongoing(
        kind: String,
        atSec: Long,
        min: Int = 45,
        used: Int = 30,
        extra: Map<String, String> = emptyMap(),
    ) = BugReport.fromOngoing(
        appVersion = "1.177",
        flavor = "github",
        androidSdk = 36,
        device = "Xiaomi 25080RABDG",
        context = mapOf(
            "ongoingKind" to kind,
            "ongoingAt" to "$atSec",
            "ongoingMin" to "$min",
            "ongoingUsedMin" to "$used",
        ) + extra,
        recentBlocks = emptyList(),
    )

    @Test
    fun `its keys survive the sanitiser`() {
        val r = ongoing(OngoingReport.Kind.PAUSED, 1_792_000_000L)
        assertEquals("paused", r.context["ongoingKind"])
        assertEquals("1792000000", r.context["ongoingAt"])
        assertEquals("45", r.context["ongoingMin"])
        assertEquals("30", r.context["ongoingUsedMin"])
        assertTrue(r.isOngoing)
    }

    @Test
    fun `one report per episode and kind, however many checks see it`() {
        val a = ongoing(OngoingReport.Kind.STOPPED, 100L, min = 30)
        val sameLater = ongoing(OngoingReport.Kind.STOPPED, 100L, min = 90)
        val next = ongoing(OngoingReport.Kind.STOPPED, 200L)
        val pause = ongoing(OngoingReport.Kind.PAUSED, 100L)
        assertEquals(a.dedupeKey(), sameLater.dedupeKey())
        assertNotEquals(a.dedupeKey(), next.dedupeKey())
        assertNotEquals(a.dedupeKey(), pause.dedupeKey())
        assertEquals(OngoingReport.key("stopped", "100"), a.dedupeKey())
    }

    @Test
    fun `the title says it is happening now`() {
        val paused = ongoing(OngoingReport.Kind.PAUSED, 1L, min = 600, used = 150).title()
        assertTrue(paused, paused.startsWith("[1.177] STILL PAUSED after an update for 600 min"))
        assertTrue(paused, "150 min of use" in paused)
        val stopped = ongoing(OngoingReport.Kind.STOPPED, 1L).title()
        assertTrue(stopped, stopped.startsWith("[1.177] STILL STOPPED for 45 min"))
    }

    @Test
    fun `its body is its own, not an outage that has ended`() {
        val body = ongoing(OngoingReport.Kind.PAUSED, 1L).body()
        assertTrue(body, "nobody has tapped Reactivate" in body)
        assertTrue(body, "has now come back" !in body)
    }

    // ---- invariant 93: which space, when, and what it cost -------------------------------------

    @Test
    fun `the title says which space wrote it`() {
        val second = ongoing(OngoingReport.Kind.STOPPED, 1L, extra = mapOf("space" to "10")).title()
        assertTrue(second, second.startsWith("[1.177 · Second Space] STILL STOPPED"))
        val main = ongoing(OngoingReport.Kind.STOPPED, 1L, extra = mapOf("space" to "0")).title()
        assertTrue(main, main.startsWith("[1.177 · main space] "))
        // An old queued report without the field keeps its old title.
        assertTrue(ongoing(OngoingReport.Kind.STOPPED, 1L).title().startsWith("[1.177] "))
    }

    @Test
    fun `minutes in blocked apps go in the title only when known`() {
        val known = ongoing(OngoingReport.Kind.STOPPED, 1L, extra = mapOf("ongoingBlockedMin" to "120")).title()
        assertTrue(known, "120 min in blocked apps" in known)
        val unknown = ongoing(OngoingReport.Kind.STOPPED, 1L, extra = mapOf("ongoingBlockedMin" to "?")).title()
        assertTrue(unknown, "blocked apps" !in unknown)
    }

    @Test
    fun `the body opens with when the phone wrote it`() {
        val body = ongoing(
            OngoingReport.Kind.STOPPED, 1L,
            extra = mapOf("localDate" to "Sat 2026-10-24", "localTime" to "23:40", "space" to "10"),
        ).body()
        assertTrue(body, body.startsWith("_Written on the phone on **Sat 2026-10-24 at 23:40**"))
        assertTrue(body, "in the Second Space" in body)
        assertTrue(ongoing(OngoingReport.Kind.STOPPED, 1L).body(), !ongoing(OngoingReport.Kind.STOPPED, 1L).body().startsWith("_Written"))
    }

    @Test
    fun `stoppage lines carry the day of the month`() {
        val at = java.util.Calendar.getInstance().apply { set(2026, java.util.Calendar.OCTOBER, 15, 18, 41) }.timeInMillis
        assertEquals("Thu 15 Oct 18:41", StoppageHistory.label(at))
        assertEquals("?", StoppageHistory.label(0L))
    }
}
