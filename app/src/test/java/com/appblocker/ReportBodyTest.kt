package com.appblocker

import com.appblocker.data.BugReport
import com.appblocker.data.HealthFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **What a report actually reads like**, which until now nothing asserted.
 *
 * The outage body had no test at all, no test pinned the order of sections, and the one thing a
 * reader needs — the answer near the top — was not a property anything could check. That mattered
 * because the report is read by one person, once, usually while something is broken, and a section
 * that quietly moves to the bottom is a section that stops being read.
 *
 * The load-bearing case here is [a blank report says everything a typed one says]: the owner's
 * standard for this whole rewrite was that his comment is optional, and the only honest way to
 * hold that is to render both and compare.
 */
class ReportBodyTest {

    private val health = HealthFacts.render(
        listOf(
            HealthFacts.Fact("Switched on, but NOT running", "Nothing is being blocked.", false),
            HealthFacts.Fact("Blocking has stopped 9 times", "Unprotected for 2 h in total.", null),
            HealthFacts.Fact("95% of blocks appear quickly", "Measured over 140 covers.", true),
        ),
    )

    private fun report(note: String, kind: String? = null) = BugReport.fromNote(
        note = note,
        appVersion = "1.146",
        flavor = "github",
        androidSdk = 36,
        device = "Xiaomi 25080RABDG",
        context = buildMap {
            put("noteAt", "1756000000")
            put("serviceOn", "true")
            kind?.let { put("reportKind", it) }
        },
        recentBlocks = listOf("2h ago     app  why=quick  window=other  ownUi=false  counted=true"),
        recentOutages = listOf("at=Sun 21:40  down=42min  noticedAfter=15min  deaf=true"),
        healthFacts = health,
    )

    // --- the owner's requirement, as a test --------------------------------------------------

    /**
     * "The report should be so telling and detailed that it doesn't need my comment." Everything
     * the report knows is gathered by the app, so removing his words must remove **only** his
     * words — if any section is thinner without them, the report was leaning on him.
     */
    @Test
    fun `a blank report says everything a typed one says`() {
        val typed = report("it stopped working again").body()
        val blank = report("").body()

        // Strip the blockquote AND the blank line after it — what remains must match
        // character for character, or some section was quietly leaning on his words.
        assertEquals(typed.replace("> it stopped working again\n\n", "").trim(), blank.trim())
    }

    /** And it still gets a real title, rather than an issue list of bare version numbers. */
    @Test
    fun `a blank report is titled by what the app found, not by silence`() {
        // "Sent from the phone" is the provenance half: it separates a report a person decided
        // to send from the ones the app files by itself, which is worth knowing before opening it.
        assertEquals(
            "[1.146] Sent from the phone — Switched on, but NOT running",
            report("").title(),
        )
    }

    @Test
    fun `a tapped chip titles the report when nothing was typed`() {
        assertEquals(
            "[1.146] did NOT block something — Switched on, but NOT running",
            report("", kind = "did-not-block").title(),
        )
    }

    /** His own words still win: nothing summarises a complaint better than the complaint. */
    @Test
    fun `typed words still title the report`() {
        assertEquals("[1.146] it stopped working again", report("it stopped working again").title())
    }

    // --- order, which is the whole point ------------------------------------------------------

    /**
     * The finding comes before the evidence. A report used to open on a 24-row alphabetical table
     * in which the alarming value looked exactly like the colour scheme.
     */
    @Test
    fun `the finding is the first thing in the body`() {
        val body = report("").body()

        assertTrue(body.trimStart().startsWith("### What looks wrong here"))
        assertTrue(
            body.indexOf("### What looks wrong here") < body.indexOf("### State"),
        )
    }

    @Test
    fun `only the things that are wrong appear in the opening section`() {
        val opening = report("").body().substringBefore("### State")

        assertTrue(opening.contains("Switched on, but NOT running"))
        // A count is a measurement, not an accusation — it belongs below, not in the verdict.
        assertFalse(opening.contains("Blocking has stopped 9 times"))
        assertFalse(opening.contains("95% of blocks"))
    }

    /** The healthy lines still travel, further down — they are what makes silence meaningful. */
    @Test
    fun `the full picture including healthy lines is still carried`() {
        val body = report("").body()

        assertTrue(body.contains("### What the blocker knows about itself"))
        assertTrue(body.contains("95% of blocks appear quickly"))
        assertTrue(body.contains("Blocking has stopped 9 times"))
    }

    /**
     * A clean phone and a build that collected nothing must not render identically — the two
     * nothings rule that `docs/BLOCKING_INVARIANTS.md` says already cost a round trip once.
     */
    @Test
    fun `a healthy phone says so instead of showing an empty section`() {
        val clean = BugReport.fromNote(
            note = "", appVersion = "1.146", flavor = "github", androidSdk = 36, device = "d",
            healthFacts = HealthFacts.render(
                listOf(HealthFacts.Fact("The blocker is running", "Watching now.", true)),
            ),
        )

        assertTrue(clean.body().contains("Nothing in the blocker's own numbers is wrong"))
    }

    @Test
    fun `an older report that carried no health facts renders no section at all`() {
        val old = BugReport.fromNote(
            note = "old one", appVersion = "1.145", flavor = "github", androidSdk = 36, device = "d",
        )

        assertFalse(old.body().contains("What looks wrong here"))
        assertFalse(old.body().contains("knows about itself"))
    }

    // --- the outage body, which nothing tested before ------------------------------------------

    @Test
    fun `an outage body leads with the failure and carries the history`() {
        val outage = BugReport.fromOutage(
            appVersion = "1.146", flavor = "github", androidSdk = 36, device = "d",
            context = mapOf(
                "outageAt" to "1756000000", "outageMin" to "42", "outageDeaf" to "true",
                "outagePreceded" to "nothing", "outageEnded" to "recovered",
            ),
            recentBlocks = emptyList(),
            recentOutages = listOf("at=Sun 21:40  down=42min  deaf=true"),
        )

        assertTrue(outage.title().contains("STOPPED for 42 min"))
        assertTrue(outage.body().contains("Which failure this was"))
        assertTrue(outage.body().contains("alive the whole time"))
        assertTrue(outage.body().contains("### Every stoppage on this install"))
        assertTrue(outage.body().contains("at=Sun 21:40"))
    }

    // --- privacy still holds after everything added -------------------------------------------

    /**
     * Every section added in this pass is another way for content to escape. The allow-list is the
     * guarantee, and it has to be re-proved once the report has more doors.
     */
    @Test
    fun `the new sections cannot carry content`() {
        val sneaky = BugReport.fromNote(
            note = "", appVersion = "1.146", flavor = "github", androidSdk = 36, device = "d",
            context = mapOf(
                "noteAt" to "1756000000",
                "lastBrowserHost" to "example.com",
                "keyword" to "someprivateword",
            ),
        )

        assertFalse(sneaky.body().contains("example.com"))
        assertFalse(sneaky.body().contains("someprivateword"))
    }
}
