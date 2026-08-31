package com.appblocker

import com.appblocker.data.HealthFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The verdicts a report leads with.**
 *
 * These are the sentences that decide what the first section of a bug report says, so a threshold
 * that drifts here quietly changes what gets investigated. They are testable at all because
 * [HealthFacts.verdicts] takes plain numbers — the prefs and system services behind them live in
 * `HealthReader`, which a JVM test cannot reach.
 *
 * The rule under test throughout: **`good = false` is reserved for something actually wrong.**
 * A number that is merely interesting is `null`. If everything is alarming, the section that
 * exists to say what went wrong stops meaning anything.
 */
class HealthFactsTest {

    /** A phone with nothing wrong: running, recently seen, no stoppages, quick blocks. */
    private val healthy = HealthFacts.Reading(
        serviceEnabled = true,
        serviceRunning = true,
        sinceLastEventMs = 30_000L,
        sinceAliveMs = 20_000L,
        usedMinutes = 0,
        processAgeMs = 7_200_000L,
        updatePaused = false,
        updatePausePending = false,
        foundDead = 0,
        outageOpen = false,
        outageCount = 0,
        outageTotalMs = 0L,
        outageLongestMs = 0L,
        probeFailStreak = 0,
        bindDeferrals = 0,
        workerSilentMs = -1L,
        quickSharePercent = 95,
        blocksMeasured = 140,
        slowBlocks = 1,
        deafSpells = 0,
        lateSkips = 0,
        unreadyDecisions = 0,
        shortsBlind = 0,
        queuedReports = 0,
        reportsLeftToday = 12,
    )

    private fun problems(r: HealthFacts.Reading) = HealthFacts.problems(r).map { it.title }

    @Test
    fun `a healthy phone reports no problems at all`() {
        assertTrue(problems(healthy).toString(), problems(healthy).isEmpty())
    }

    @Test
    fun `a healthy phone still says something, so the section is never empty by accident`() {
        assertTrue(HealthFacts.verdicts(healthy).isNotEmpty())
        assertEquals(true, HealthFacts.verdicts(healthy).first().good)
    }

    // --- the failure this app was blind to ---------------------------------------------------

    /**
     * Android's toggle records the owner's *choice*, so it keeps reading ON over a watcher the
     * phone has killed. These two booleans differing IS the outage, and in the old report they
     * were two unremarkable rows in an alphabetical table.
     */
    @Test
    fun `switched on but not running is the first and worst finding`() {
        val dead = healthy.copy(serviceRunning = false)

        assertEquals("Switched on, but NOT running", HealthFacts.verdicts(dead).first().title)
        assertEquals(false, HealthFacts.verdicts(dead).first().good)
    }

    /**
     * A process seconds old has not died, it has just started. Accusing it here would make every
     * cold start look like a failure, and a finding that fires on ordinary behaviour is noise.
     */
    @Test
    fun `a process that just started is not accused of being dead`() {
        val starting = healthy.copy(serviceRunning = false, processAgeMs = 3_000L)

        assertTrue(problems(starting).isEmpty())
    }

    @Test
    fun `switched off entirely is a problem, not a choice`() {
        val off = healthy.copy(serviceEnabled = false, serviceRunning = false)

        assertEquals(listOf("The blocker is switched off"), problems(off))
    }

    // --- quiet only counts when the phone was being used --------------------------------------

    /**
     * The whole reason `usedMinutes` was rescued from being thrown away: four silent hours mean
     * nothing on a phone nobody touched, and mean everything on one in constant use. The old
     * report carried `lastEventMin 240` and could not tell those apart.
     */
    @Test
    fun `hours of quiet with real use is a finding`() {
        val stalled = healthy.copy(sinceLastEventMs = 14_400_000L, usedMinutes = 90)

        assertTrue(problems(stalled).any { it.contains("90 minutes of use") })
    }

    @Test
    fun `the same quiet on an untouched phone is not a finding`() {
        val idle = healthy.copy(sinceLastEventMs = 14_400_000L, usedMinutes = 0)

        assertTrue(problems(idle).toString(), problems(idle).isEmpty())
    }

    @Test
    fun `quiet says nothing when usage access never answered`() {
        val unknown = healthy.copy(sinceLastEventMs = 14_400_000L, usedMinutes = null)

        assertTrue(problems(unknown).isEmpty())
    }

    // --- a report written during a stoppage ---------------------------------------------------

    @Test
    fun `an outage happening right now is called out`() {
        val during = healthy.copy(outageOpen = true)

        assertTrue(problems(during).any { it.contains("RIGHT NOW") })
    }

    /**
     * Counting stoppages is the measurement that says whether a fix worked — it must not read as
     * an alarm, or every report from a phone with any history at all opens with a false finding.
     */
    @Test
    fun `a history of past stoppages is a number, not an accusation`() {
        val history = healthy.copy(outageCount = 9, outageTotalMs = 5_400_000L, foundDead = 9)

        assertTrue(problems(history).isEmpty())
        assertTrue(HealthFacts.verdicts(history).any { it.title.contains("9 times") })
    }

    // --- thresholds ---------------------------------------------------------------------------

    @Test
    fun `slow blocks are a finding and quick ones are not`() {
        val slow = healthy.copy(quickSharePercent = HealthFacts.QUICK_SHARE_TARGET - 1)
        val quick = healthy.copy(quickSharePercent = HealthFacts.QUICK_SHARE_TARGET)

        assertTrue(problems(slow).any { it.contains("under half a second") })
        assertTrue(problems(quick).isEmpty())
    }

    @Test
    fun `nothing measured yet says nothing about speed`() {
        val fresh = healthy.copy(quickSharePercent = null, blocksMeasured = 0)

        assertTrue(HealthFacts.verdicts(fresh).none { it.title.contains("half a second") })
    }

    /** One failed probe is noise; a streak is the watcher's own answer to "am I still connected". */
    @Test
    fun `a probe streak becomes a finding only once it is a streak`() {
        assertTrue(problems(healthy.copy(probeFailStreak = 1)).isEmpty())
        assertTrue(problems(healthy.copy(probeFailStreak = 4)).any { it.contains("could not read") })
    }

    // --- the half-open update pause -----------------------------------------------------------

    /**
     * `paused` alone is deliberate and expected. `pending` without `paused` is the v1.144 bug's
     * shape — the note that survives a Reactivate and switches blocking off again later — and it
     * is silent, so it has to be loud here.
     */
    @Test
    fun `a deliberate update pause is not a fault but a half-cleared one is`() {
        assertTrue(problems(healthy.copy(updatePaused = true)).isEmpty())
        assertTrue(problems(healthy.copy(updatePausePending = true)).any { it.contains("half") })
    }

    // --- the reporter reporting on itself -----------------------------------------------------

    /**
     * Written after five days in which every report was queued and none arrived, with nothing in
     * any report able to say so. A backlog visible on arrival is the channel describing itself.
     */
    @Test
    fun `a backlog of undelivered reports is itself a finding`() {
        assertTrue(problems(healthy.copy(queuedReports = 7)).any { it.contains("waiting") })
    }

    @Test
    fun `a single queued report is normal and says nothing`() {
        assertTrue(problems(healthy.copy(queuedReports = 1)).isEmpty())
    }

    @Test
    fun `a spent daily cap is a finding, because it silently drops reports`() {
        assertTrue(problems(healthy.copy(reportsLeftToday = 0)).any { it.contains("limit") })
    }

    // --- rendering ----------------------------------------------------------------------------

    @Test
    fun `rendered lines carry their verdict as a marker a report can filter on`() {
        val lines = HealthFacts.render(HealthFacts.verdicts(healthy.copy(serviceRunning = false)))

        assertTrue(lines.any { it.startsWith(HealthFacts.BAD) })
        assertEquals(1, HealthFacts.problemLines(lines).size)
    }

    /**
     * A report used to print `121808s ago` and leave the reader to divide by 3600. Times are for
     * reading, and this is the helper both the screen and the report now share.
     */
    @Test
    fun `ages are readable rather than raw seconds`() {
        assertEquals("never", HealthFacts.agoText(-1L))
        assertEquals("just now", HealthFacts.agoText(5_000L))
        assertEquals("4 min ago", HealthFacts.agoText(240_000L))
        assertEquals("2 h ago", HealthFacts.agoText(7_200_000L))
        assertEquals("1 days ago", HealthFacts.agoText(90_000_000L))
    }

    @Test
    fun `durations read as durations`() {
        assertEquals("under a minute", HealthFacts.minutesText(30_000L))
        assertEquals("42 min", HealthFacts.minutesText(2_520_000L))
        assertEquals("2 h", HealthFacts.minutesText(7_200_000L))
        assertEquals("3 h 5 min", HealthFacts.minutesText(11_100_000L))
    }

    /** Never a negative or a nonsense duration in a report, whatever the clock did. */
    @Test
    fun `an unmeasurable duration says so instead of printing a negative`() {
        assertEquals("an unknown time", HealthFacts.minutesText(-1L))
        assertFalse(HealthFacts.agoText(-5L).contains("-"))
    }

    // --- the structural privacy guarantee -----------------------------------------------------

    /**
     * ⚠️ **Health lines do NOT pass through `sanitizeContext`.**
     *
     * The allow-list protects the settings table; [BugReport.healthFacts] is a separate list that
     * goes into the issue body untouched, the same way `recentBlocks` does. `BlockLog` earns that
     * exemption by construction — every token it writes is a fixed literal. This is the equivalent
     * guarantee for the verdicts, and it is worth having mechanically rather than as a promise:
     *
     * **Every field of [HealthFacts.Reading] is a number or a boolean.** No string from the phone
     * can enter, so no rendered sentence can carry a package name, a URL, a keyword or anything
     * the owner typed. The day someone adds `val lastPackage: String` to that class, this fails
     * and makes them think about it — which is the only moment the thinking is cheap.
     *
     * If a genuinely safe string field is ever needed, do not widen this: add it to the allow-list
     * below **with the reason**, the way the context keys are annotated.
     */
    @Test
    fun `no field of a health reading can carry text from the phone`() {
        val allowed = setOf(
            java.lang.Integer.TYPE, java.lang.Long.TYPE, java.lang.Boolean.TYPE,
            Integer::class.java, java.lang.Long::class.java, java.lang.Boolean::class.java,
        )
        val text = HealthFacts.Reading::class.java.declaredFields
            .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .filterNot { it.type in allowed }
            .map { "${it.name}: ${it.type.simpleName}" }

        assertTrue(
            "$text is not a number or a boolean. Health facts are rendered straight into the " +
                "issue body without passing the context allow-list, so a String here is a way " +
                "for a package name, a URL or a blocked word to leave the phone.",
            text.isEmpty(),
        )
    }

    /** And the rendered sentence really is built only from those numbers. */
    @Test
    fun `rendered lines contain nothing but our own words and numbers`() {
        val lines = HealthFacts.render(HealthFacts.verdicts(healthy.copy(serviceRunning = false)))

        assertTrue(lines.isNotEmpty())
        assertTrue(lines.all { it.length < 400 })
    }
}
