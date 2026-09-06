package com.appblocker

import com.appblocker.data.BootAudit
import com.appblocker.data.HealthFacts
import com.appblocker.data.ProtectionPulse
import com.appblocker.service.STALE_AFTER_MS
import com.appblocker.service.STALE_MIN_USED_MINUTES
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

    // --- the report and the watchdog, on the same question ------------------------------------

    /**
     * **The use half is one rule, and it was two literals agreeing by luck.**
     *
     * `QUIET_WITH_USE_MIN` carried a comment saying it matched `ProtectionState`'s rule; nothing
     * checked, and invariant 54 was that exact situation one release after the comment stopped
     * being true. Compared, not repeated — a test with its own copy of the number is a third
     * opinion.
     */
    @Test
    fun `the report and the watchdog need the same minutes of use`() {
        assertEquals(STALE_MIN_USED_MINUTES, HealthFacts.QUIET_WITH_USE_MIN)
    }

    /**
     * **And the window is deliberately shorter, which is the part the old comment got wrong.**
     *
     * It claimed the report would never call broken what the watchdog calls OK, and then reported
     * at fifteen minutes against the watchdog's two hours. The difference is kept on purpose —
     * describing is not concluding, and quiet paired with real use is the one signal that
     * separates a stoppage from a phone on a table. Pinned so nobody "fixes" it in either
     * direction without meeting this.
     */
    @Test
    fun `the report describes quiet sooner than the watchdog concludes it`() {
        assertTrue(HealthFacts.QUIET_ONLY_DESCRIBES)
        assertTrue(
            "the reporting window must stay under the watchdog's stale window, or the signal " +
                "that separates a stoppage from an idle phone is suppressed for two hours",
            HealthFacts.QUIET_MIN_MS < STALE_AFTER_MS,
        )
    }

    // --- the scheduler, judged by the app's own definition ------------------------------------

    /**
     * **The report called the scheduler healthy in a state the app had already counted as broken.**
     *
     * `ProtectionAlarmReceiver` treats 25 minutes of quiet as the worker having stopped and counts
     * a silent spell. This verdict used an hour of its own, so "✅ The background scheduler last
     * ran 51 min ago" appeared on a phone whose silent-spell count was 185 and climbing. One rule,
     * two copies, disagreeing — and the number they disagree about is the one that decides how
     * fast a stoppage gets noticed.
     */
    @Test
    fun `a scheduler quiet past the app's own silence threshold is a problem`() {
        val r = healthy.copy(workerSilentMs = 51 * 60_000L)
        assertTrue(problems(r).any { "background scheduler" in it })
        assertEquals(
            false,
            HealthFacts.verdicts(r).first { "background scheduler" in it.title }.good,
        )
    }

    @Test
    fun `a scheduler running on time is healthy`() {
        val r = healthy.copy(workerSilentMs = 8 * 60_000L)
        assertTrue(problems(r).toString(), problems(r).isEmpty())
        assertEquals(
            true,
            HealthFacts.verdicts(r).first { "background scheduler" in it.title }.good,
        )
    }

    /** The boundary is the app's constant, not a number retyped here: if the threshold moves, this
     *  moves with it, which is the whole point of there being one of them. */
    @Test
    fun `the verdict turns exactly where the app says silence begins`() {
        val justUnder = healthy.copy(workerSilentMs = ProtectionPulse.SILENT_AFTER_MS - 1)
        val exactly = healthy.copy(workerSilentMs = ProtectionPulse.SILENT_AFTER_MS)
        assertEquals(true, HealthFacts.verdicts(justUnder).first { "scheduler" in it.title }.good)
        assertEquals(false, HealthFacts.verdicts(exactly).first { "scheduler" in it.title }.good)
    }

    // --- did our own start-up run after the restart -------------------------------------------

    /**
     * **A restart that WORKS produces no stoppage report**, so the number built to answer "did we
     * start after a reboot" was invisible in exactly the case it answers. It reached the report as
     * a context field only, and a profile report — the one filed on every app open — carries none
     * of those. He restarted his phone on 6 Sep 2026, it kept working, and nothing could say so.
     */
    @Test
    fun `a start-up that ran after the restart is reported as healthy`() {
        val r = healthy.copy(bootHeardMs = 14_000L)
        val fact = HealthFacts.verdicts(r).first { "after the last restart" in it.title }
        assertEquals(true, fact.good)
        assertTrue(fact.detail, "14s" in fact.detail)
    }

    /**
     * ⚠️ And a long wait is NOT a fault. Android delivers BOOT_COMPLETED only after the first
     * unlock on a file-based-encryption phone, so a restart at night is heard in the morning —
     * and nothing is lost, because a locked phone cannot open anything that needs blocking. The
     * wording has to say so, or the next report teaches him to distrust a healthy row.
     */
    @Test
    fun `a long wait after a night-time restart is not a fault`() {
        val r = healthy.copy(bootHeardMs = 6 * 3_600_000L)
        val fact = HealthFacts.verdicts(r).first { "after the last restart" in it.title }
        assertEquals(true, fact.good)
        assertTrue(fact.detail, "normal" in fact.detail)
        assertTrue(problems(r).toString(), problems(r).isEmpty())
    }

    @Test
    fun `a start-up that never ran is a fault`() {
        val r = healthy.copy(bootHeardMs = BootAudit.MISSED, bootsMissed = 3)
        assertTrue(problems(r).any { "did not run after the last restart" in it })
        val fact = HealthFacts.verdicts(r).first { "did not run" in it.title }
        assertEquals(false, fact.good)
        assertTrue(fact.detail, "3 restarts" in fact.detail)
    }

    /** Nothing recorded yet says nothing at all — a fresh install has not seen a restart, and a
     *  row claiming either answer would be inventing one. */
    @Test
    fun `no boot record yet produces no row`() {
        val r = healthy.copy(bootHeardMs = BootAudit.NEVER)
        assertTrue(HealthFacts.verdicts(r).none { "last restart" in it.title })
    }

    // --- a fault that can never clear is not a fault ------------------------------------------

    /**
     * **One deaf spell, ever, kept the top of "what looks wrong here" occupied forever.**
     *
     * The counter is a lifetime total and the cause was closed in v1.153 — a declined cover now
     * books its own return — so the row was red in every report since, describing something that
     * had stopped happening. The section is ordered worst-first, so it was also displacing things
     * that were actually wrong. Same family as the stale "could not be delivered" and the stale
     * restart-window fault: a warning that outlives its cause.
     */
    @Test
    fun `a deaf spell that has not happened again is history, not a fault`() {
        val r = healthy.copy(deafSpells = 1, deafSpellsToday = 0)
        assertTrue(problems(r).toString(), problems(r).isEmpty())
        val fact = HealthFacts.verdicts(r).first { "went quiet after a block" in it.title }
        assertEquals(null, fact.good)
        assertTrue(fact.detail, "None today" in fact.detail)
    }

    /** And one that happened today still is, because that is the state worth acting on. */
    @Test
    fun `a deaf spell today is still a fault`() {
        val r = healthy.copy(deafSpells = 4, deafSpellsToday = 2)
        assertTrue(problems(r).any { "went quiet after a block" in it })
        val fact = HealthFacts.verdicts(r).first { "went quiet after a block" in it.title }
        assertEquals(false, fact.good)
        assertTrue(fact.detail, "2 today" in fact.detail)
    }

    // --- the cost of a stoppage, as opposed to its length -------------------------------------

    /**
     * **The finding that reframed a week of work.** On 6 Sep 2026 every stoppage on record turned
     * out to have happened on a phone nobody was touching — 61, 191, 351 and 1181 minutes, all
     * with zero measured use. "Unprotected for 26 hours" had been read, and quoted back to him, as
     * if it were exposure. A blocker that is off while he is asleep costs him nothing.
     */
    @Test
    fun `a total with no use behind it says so plainly`() {
        val r = healthy.copy(
            outageCount = 30, foundDead = 42,
            outageTotalMs = 95_220_000L, outageLongestMs = 21_060_000L,
            outageUsedMin = 0, outageUsedCount = 4,
        )
        val detail = HealthFacts.verdicts(r).first { "Blocking has stopped" in it.title }.detail
        assertTrue(detail, "None of it happened while you were using the phone" in detail)
        assertTrue(detail, "4 measured" in detail)
    }

    /** Nothing measured yet is a different sentence from nothing lost, and rendering them alike
     *  would announce a clean bill of health the app has not earned. */
    @Test
    fun `no use measured yet never reads as no use lost`() {
        val r = healthy.copy(
            outageCount = 30, foundDead = 42,
            outageTotalMs = 95_220_000L, outageLongestMs = 21_060_000L,
            outageUsedMin = 0, outageUsedCount = 0,
        )
        val detail = HealthFacts.verdicts(r).first { "Blocking has stopped" in it.title }.detail
        assertTrue(detail, "never recorded" in detail)
        assertFalse(detail, "None of it happened while you were using the phone" in detail)
    }

    @Test
    fun `use that was actually lost is named as the part that cost something`() {
        val r = healthy.copy(
            outageCount = 30, foundDead = 42,
            outageTotalMs = 95_220_000L, outageLongestMs = 21_060_000L,
            outageUsedMin = 12, outageUsedCount = 4,
        )
        val detail = HealthFacts.verdicts(r).first { "Blocking has stopped" in it.title }.detail
        assertTrue(detail, "12 minute(s) were while you were actually using" in detail)
        assertTrue(detail, "cost you something" in detail)
    }

    // --- a backlog behind a working channel is not a delivery failure -------------------------

    /**
     * Report #99 said "✅ The last report was delivered, 10 h ago" and "❌ 2 reports … could not be
     * delivered" about the same channel, and the red one led the worst-first section.
     * `lastSendResult` being non-null means an attempt happened, not that it failed — which the
     * reader forty lines below already knew.
     */
    @Test
    fun `reports queued behind a successful send are not a failure`() {
        val r = healthy.copy(queuedReports = 2, reportsLeftToday = 3, lastSendResult = "201")
        val fact = HealthFacts.verdicts(r).first { "waiting to be sent" in it.title }
        assertEquals(null, fact.good)
        assertFalse(fact.detail, "could not be delivered" in fact.detail)
        assertTrue(fact.detail, "the route is working" in fact.detail)
    }

    @Test
    fun `reports queued behind a failed send still are a failure`() {
        val r = healthy.copy(queuedReports = 2, reportsLeftToday = 3, lastSendResult = "500")
        val fact = HealthFacts.verdicts(r).first { "waiting to be sent" in it.title }
        assertEquals(false, fact.good)
        assertTrue(fact.detail, "could not be delivered" in fact.detail)
    }

    @Test
    fun `the two readers of a send result agree`() {
        assertEquals(null, HealthFacts.lastSendSucceeded(null))
        assertEquals(true, HealthFacts.lastSendSucceeded("200"))
        assertEquals(true, HealthFacts.lastSendSucceeded("201"))
        assertEquals(false, HealthFacts.lastSendSucceeded("403"))
        assertEquals(false, HealthFacts.lastSendSucceeded("UnknownHostException"))
    }

    // --- the total he actually reads ----------------------------------------------------------

    /**
     * **"Unprotected for 19 h 34 min" was two different quantities added together.**
     *
     * Invariant 44 put the measured / upper-bound distinction on every line of the episode list and
     * then left the summary sentence — the one number he has been shown for weeks, and the one I
     * have been quoting back at him — as a flat total with no note on it. A stoppage closed by a
     * throttled background job contains the stoppage *plus* the wait for something to look.
     */
    @Test
    fun `a total with nothing timed by the app says so`() {
        val r = healthy.copy(
            outageCount = 30, foundDead = 42,
            outageTotalMs = 70_440_000L, outageLongestMs = 17_760_000L,
            outageTimedMs = 0L, outageTimedCount = 0,
        )
        val fact = HealthFacts.verdicts(r).first { "Blocking has stopped" in it.title }
        assertTrue(fact.detail, "Treat the length as a maximum" in fact.detail)
        assertEquals(null, fact.good)
    }

    @Test
    fun `a partly timed total names the share that is a measurement`() {
        val r = healthy.copy(
            outageCount = 30, foundDead = 42,
            outageTotalMs = 70_440_000L, outageLongestMs = 17_760_000L,
            outageTimedMs = 2_160_000L, outageTimedCount = 2,
        )
        val detail = HealthFacts.verdicts(r).first { "Blocking has stopped" in it.title }.detail
        assertTrue(detail, "36 min" in detail && "2 of them" in detail)
        assertTrue(detail, "the rest is a maximum" in detail.lowercase())
        assertTrue(detail, "Of the length" in detail)
    }

    /** Once every stoppage is self-timed the caveat is no longer true, and a warning that outlives
     *  its reason is the thing this whole pass is about. */
    @Test
    fun `a fully timed total carries no caveat`() {
        val r = healthy.copy(
            outageCount = 2, foundDead = 2,
            outageTotalMs = 2_160_000L, outageLongestMs = 1_200_000L,
            outageTimedMs = 2_160_000L, outageTimedCount = 2,
        )
        val detail = HealthFacts.verdicts(r).first { "Blocking has stopped" in it.title }.detail
        assertFalse(detail, "maximum" in detail)
    }

    // --- the restart window: covered is not the same as blind ---------------------------------

    /**
     * **Entering the unready window is not a fault, and reporting it as one buried the real ones.**
     *
     * Every restart has a moment before Room answers, and this phone restarts around thirty times
     * a day, so the count is never zero. Four snapshots answer inside that window. Until 5 Sep 2026
     * the report marked the count `good = false` regardless — so "Decisions made before the block
     * list had loaded: 13" led the *worst first* section of a report whose own conclusion was that
     * the phone was healthy. The Diagnostics screen had said the honest thing all along, forty
     * lines of a different file away, which is this project's most repeated bug shape.
     */
    @Test
    fun `a restart window the saved rules covered is not a problem`() {
        val covered = healthy.copy(unreadyDecisions = 13, unreadyBlind = 0)
        assertTrue(problems(covered).toString(), problems(covered).isEmpty())
        val fact = HealthFacts.verdicts(covered).first { "before the block list" in it.title }
        assertEquals(null, fact.good)
        assertTrue(fact.detail, "covered every one of them" in fact.detail)
    }

    /**
     * The other half, which nothing could see before: the window was entered with an EMPTY
     * snapshot, so "this app is not blocked" was a shrug. That one really is lost protection.
     */
    @Test
    fun `a restart window with no saved rules to fall back on is a problem`() {
        val blind = healthy.copy(unreadyDecisions = 13, unreadyBlind = 2)
        assertTrue(problems(blind).any { "no rules and no saved copy" in it })
        assertEquals(false, HealthFacts.verdicts(blind).first { "no saved copy" in it.title }.good)
    }

    /** The covered row must not claim a clean sweep while the blind row contradicts it two lines
     *  above — the pair is read together or not at all. */
    @Test
    fun `the covered wording backs off when some of the window was blind`() {
        val blind = healthy.copy(unreadyDecisions = 13, unreadyBlind = 2)
        val fact = HealthFacts.verdicts(blind).first { "before the block list" in it.title }
        assertFalse(fact.detail, "covered every one of them" in fact.detail)
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
    /**
     * A backlog is a finding **once a delivery has actually been tried and failed** — which is the
     * real shape of the Aug-Sep 2026 outage: nine reports written, every send refused at DNS.
     * Before an attempt it is just a queue, and saying otherwise is a claim the app cannot support.
     */
    @Test
    fun `a backlog that has failed to deliver is a finding`() {
        val stuck = healthy.copy(
            queuedReports = 7, lastSendResult = "UnknownHostException", sinceLastSendMs = 60_000L,
        )

        assertTrue(problems(stuck).any { it.contains("waiting") })
    }

    @Test
    fun `the same backlog before any attempt is not a finding`() {
        assertTrue(problems(healthy.copy(queuedReports = 7)).isEmpty())
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
        // The ONE documented exception, added the way the check's own message demands rather than
        // by widening it: `lastSendResult` is an HTTP status as text ("403") or an exception class
        // name ("UnknownHostException"), written only by `BugReportQueue.recordAttempt`, which is
        // called only with `BugReportSender.post`'s return value. **Never a response body** — a
        // failure body can echo what was submitted. If that ever stops being true, this exception
        // must go, not the test.
        val allowedText = setOf("lastSendResult")
        val text = HealthFacts.Reading::class.java.declaredFields
            .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .filterNot { it.type in allowed || it.name in allowedText }
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

    // --- can the app reach its own server? ----------------------------------------------------

    /**
     * **The failure that cost six days, as a test.**
     *
     * AppBlocker's own family DNS filter blocks dynamic-DNS domains as a category — correct
     * behaviour for a content filter — and the app's reporting host was on one. Every send died
     * at name resolution with an `UnknownHostException`, the queue grew, and the phone, the screen
     * and the tracker all looked exactly like a quiet week. Three different diagnoses rendered
     * identically, so these three cases must now be distinguishable by anyone reading the screen.
     */
    @Test
    fun `a name that cannot be resolved reads as cannot reach, not as refused`() {
        val dns = healthy.copy(
            lastSendResult = "UnknownHostException", sinceLastSendMs = 120_000L, queuedReports = 9,
        )

        val fact = HealthFacts.problems(dns).single { it.title.contains("cannot reach") }
        assertTrue(fact.detail, fact.detail.contains("look up the server"))
        // Names the app's own filter, because that is the likeliest cause on this phone and the
        // one the owner can actually act on.
        assertTrue(fact.detail, fact.detail.contains("DNS filter"))
    }

    @Test
    fun `being refused reads as refused, and blames the build rather than the phone`() {
        val refused = healthy.copy(lastSendResult = "403", sinceLastSendMs = 60_000L)

        val fact = HealthFacts.problems(refused).single { it.title.contains("refused") }
        assertTrue(fact.detail, fact.detail.contains("password"))
        assertTrue(fact.detail, fact.detail.contains("nothing on this phone can"))
    }

    @Test
    fun `a delivered report is the healthy answer and says the route works`() {
        val ok = healthy.copy(lastSendResult = "201", sinceLastSendMs = 5_000L)

        assertTrue(HealthFacts.problems(ok).isEmpty())
        assertTrue(HealthFacts.verdicts(ok).any { it.good == true && it.title.contains("delivered") })
    }

    /** Never attempted is not a fault — on a fresh install it just has not happened yet. */
    @Test
    fun `never having tried is a statement rather than an accusation`() {
        val fresh = healthy.copy(lastSendResult = null, queuedReports = 3)

        assertTrue(HealthFacts.problems(fresh).isEmpty())
        assertTrue(HealthFacts.verdicts(fresh).any { it.title.contains("ever been sent") })
    }

    /** A build with no reporting configured must not look like a broken one. */
    @Test
    fun `reporting switched off in the build is not a failure`() {
        val off = healthy.copy(reportingOn = false, lastSendResult = null)

        assertTrue(HealthFacts.problems(off).isEmpty())
        assertTrue(HealthFacts.verdicts(off).any { it.title.contains("switched off in this build") })
    }

    /**
     * These belong to the REPORTING group so the diagnostics screen can show them. They are the
     * one kind of fact that cannot travel inside a report: when sending is broken, a line in a
     * report is precisely the line nobody can read.
     */
    @Test
    fun `delivery facts are grouped so the phone can display them`() {
        val dns = healthy.copy(lastSendResult = "UnknownHostException", sinceLastSendMs = 1000L)

        assertTrue(
            HealthFacts.verdicts(dns)
                .filter { it.title.contains("cannot reach") }
                .all { it.group == HealthFacts.Group.REPORTING },
        )
    }

    // --- the update pause, which every update used to report as broken ------------------------

    /**
     * **A pause still being resolved is not a fault.**
     *
     * `UpdatePause.resolvePendingPause` clears `pending` from a coroutine that reads the Strict
     * session out of Room, and `MainActivity` raises the flag in `onCreate` and files the profile
     * report from `onResume` on the same launch. The report therefore won that race every time,
     * so **every manual update produced "An update pause is half-cleared" as the top line of its
     * own report** — for the state machine working correctly, on 4 Sep 2026 in issue #82.
     *
     * The cost is not the noise. It is that the genuinely stuck case — a `pending` nothing ever
     * clears, which really does switch blocking off again on the next service connect — printed
     * exactly the same sentence and could no longer be picked out.
     */
    @Test
    fun `a pause that is still resolving is not reported as a fault`() {
        val resolving = healthy.copy(updatePausePending = true, updatePausePendingMs = 40L)
        assertTrue(problems(resolving).toString(), problems(resolving).isEmpty())
    }

    /** Past the resolve window nothing is going to clear it, and that is worth saying. */
    @Test
    fun `a pause that never resolved is still reported`() {
        val stuck = healthy.copy(
            updatePausePending = true,
            updatePausePendingMs = HealthFacts.PAUSE_RESOLVE_GRACE_MS + 1,
        )
        assertTrue(problems(stuck).any { "half-cleared" in it })
    }

    /**
     * ⚠️ **No stamp means it survived an install, not that it is new.** The stamp is written by the
     * same call that raises the flag, so a raised flag without one was raised by a build that did
     * not have it. Reading "unknown" as "fine" would retire this check for exactly the phone that
     * already has the fault.
     */
    @Test
    fun `a pause with no stamp at all counts as stuck`() {
        val legacy = healthy.copy(updatePausePending = true, updatePausePendingMs = -1L)
        assertTrue(problems(legacy).any { "half-cleared" in it })
    }

    /** The deliberate pause is unchanged: it is a state, not a fault, and it still speaks up. */
    @Test
    fun `a real pause after an update is still announced and is not called a fault`() {
        val paused = healthy.copy(updatePaused = true, updatePausePendingMs = -1L)
        assertTrue(HealthFacts.verdicts(paused).any { it.title.contains("paused after an update") })
        assertTrue(problems(paused).toString(), problems(paused).isEmpty())
    }
}
