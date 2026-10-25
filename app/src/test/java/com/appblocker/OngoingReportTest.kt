package com.appblocker

import com.appblocker.data.BugReport
import com.appblocker.data.OngoingReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Invariant 91: the report filed while a stoppage or a pause is still going on. */
class OngoingReportTest {

    private fun ongoing(kind: String, atSec: Long, min: Int = 45, used: Int = 30) = BugReport.fromOngoing(
        appVersion = "1.177",
        flavor = "github",
        androidSdk = 36,
        device = "Xiaomi 25080RABDG",
        context = mapOf(
            "ongoingKind" to kind,
            "ongoingAt" to "$atSec",
            "ongoingMin" to "$min",
            "ongoingUsedMin" to "$used",
        ),
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
}
