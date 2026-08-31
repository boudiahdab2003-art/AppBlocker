package com.appblocker.data

/**
 * **What the blocker's own numbers mean**, in one place, for every reader that needs a verdict.
 *
 * ## Why this exists
 *
 * The diagnostics screen already turned these readings into plain English with a healthy/unhealthy
 * verdict on each — and the bug report, read by the one person who can actually fix anything,
 * printed the same values as a flat alphabetical table where `serviceRunning=false` looked exactly
 * like `theme=ink`. The owner had the interpretation on his phone and the developer had the raw
 * numbers, which is the wrong way round. The workflow that fell out of it is written down in
 * `CLAUDE.md`: read the report, then ask him for a screenshot of the screen that explains it.
 *
 * So the verdicts move here and both render the same list. This is the second time this repo has
 * had to do that — [DeviceProfile] exists because "the diagnostics screen, the probe and the
 * reporter must never be able to disagree about a phone", and the health facts had exactly the
 * same three readers and no such guarantee.
 *
 * ## The split, and why it is worth the extra type
 *
 * [verdicts] is **pure**: it takes a [Reading] of plain numbers and returns sentences. The prefs
 * and system services it would otherwise touch cannot be reached from a JVM test, and a threshold
 * nobody can test is a threshold that drifts — the same split `OutageLog.protectionState` and
 * `DeviceProfile` already use, for the same reason. [read] is the only part that needs a phone.
 *
 * ## The rule about `good`
 *
 * `null` is not "unknown", it is **"a genuine choice, not a fault"**. A number that is merely
 * interesting must never be `false`, because `false` is what puts a line under "what looks wrong
 * here" in a report and at the top of the screen. If everything is alarming, nothing is.
 */
object HealthFacts {

    /**
     * Which part of the picture a fact belongs to.
     *
     * Exists so the diagnostics screen can take **exactly** the verdicts it used to compute for
     * itself and leave the rest to the sections it already has, without a second copy of the
     * thresholds. A report ignores this and prints them all.
     */
    enum class Group { PROTECTION, SPEED, SILENCE, REPORTING }

    /** One plain-language fact, and whether it is the healthy answer. `null` = a choice, not a fault. */
    data class Fact(
        val title: String,
        val detail: String,
        val good: Boolean?,
        val group: Group = Group.PROTECTION,
    )

    /**
     * Every number a verdict is drawn from, already read off the phone.
     *
     * Deliberately primitives: this is what makes [verdicts] testable, and it is also what stops a
     * verdict quietly reading something new without it appearing here first.
     */
    data class Reading(
        val serviceEnabled: Boolean,
        val serviceRunning: Boolean,
        /** Millis since the watcher last saw anything, or -1 when it never has. */
        val sinceLastEventMs: Long,
        /** Millis since the watcher last proved itself alive, or -1 when it never has. */
        val sinceAliveMs: Long,
        /** Foreground minutes since the last event — null when it cannot be told. */
        val usedMinutes: Int?,
        /** Millis this process has been alive. */
        val processAgeMs: Long,
        val updatePaused: Boolean,
        val updatePausePending: Boolean,
        val foundDead: Int,
        val outageOpen: Boolean,
        val outageCount: Int,
        val outageTotalMs: Long,
        val outageLongestMs: Long,
        val probeFailStreak: Int,
        val bindDeferrals: Int,
        /** Millis since the background scheduler last ran, or [ProtectionPulse.UNKNOWN]. */
        val workerSilentMs: Long,
        /** Share of covers that appeared in under half a second, or null when none measured. */
        val quickSharePercent: Int?,
        val blocksMeasured: Int,
        val slowBlocks: Int,
        val deafSpells: Int,
        val lateSkips: Int,
        val unreadyDecisions: Int,
        val shortsBlind: Int,
        /** Reports written but not yet delivered, and how many more may be sent today. */
        val queuedReports: Int,
        val reportsLeftToday: Int,
    )

    /**
     * How long the watcher may be quiet, with real use happening, before that is a finding.
     *
     * Matches `ProtectionState`'s own stale window rather than inventing a second opinion — if the
     * watchdog would not call this stalled, a report must not call it broken.
     */
    const val QUIET_WITH_USE_MIN = 15

    /** Under this share of covers appearing in half a second, blocking stops feeling like an answer. */
    const val QUICK_SHARE_TARGET = 80

    /**
     * The verdicts, most serious first.
     *
     * Order is load-bearing: a reader who stops after the first line should have read the worst
     * thing. A watcher that is not running makes every line below it irrelevant, so it goes first
     * whatever else is wrong.
     */
    fun verdicts(r: Reading): List<Fact> = buildList {
        add(runningFact(r))
        if (r.outageOpen) {
            add(
                Fact(
                    "Blocking is stopped RIGHT NOW",
                    "This report was written during a stoppage, not after one — every " +
                        "`outage` figure below describes the previous episode, not this one.",
                    good = false,
                ),
            )
        }
        // The pull side of liveness: the watcher itself saying it could not read a lit screen.
        // One failure is noise; a streak is the watcher's own answer to "am I still connected".
        if (r.probeFailStreak > 0) {
            add(
                Fact(
                    "The blocker could not read the screen: ${r.probeFailStreak} checks in a row",
                    "It asked whether it could still see what is in front of you and could not. " +
                        "This is the fast detector — a streak here means a stoppage in progress.",
                    good = r.probeFailStreak < 3,
                ),
            )
        }
        quietFact(r)?.let { add(it) }
        // `paused` and `pending` are two halves of one state machine, and the half-open case is a
        // real, silent, blocking-is-off condition that neither flag describes alone.
        if (r.updatePaused || r.updatePausePending) {
            add(
                Fact(
                    if (r.updatePaused) "Blocking is paused after an update" else
                        "An update pause is half-cleared",
                    if (r.updatePaused) {
                        "Deliberate: it waits for Reactivate to be tapped after the app updates " +
                            "itself. Nothing is being blocked until then."
                    } else {
                        "The pause was lifted but its second note was not torn up. Blocking can " +
                            "switch itself back off next time the watcher reconnects."
                    },
                    good = if (r.updatePaused) null else false,
                ),
            )
        }
        if (r.bindDeferrals > 0) {
            add(
                Fact(
                    "The watcher was re-checked and still missing: ${r.bindDeferrals} times",
                    "Each one means the phone had not brought the blocker back within the grace " +
                        "period — it is being killed faster than the re-check can wait for it.",
                    good = false,
                ),
            )
        }
        outageFact(r)?.let { add(it) }
        if (r.workerSilentMs > 0L) {
            add(
                Fact(
                    "The background scheduler last ran ${agoText(r.workerSilentMs)}",
                    "Every background check in this app is one of its jobs, so when it stops, the " +
                        "checks that would notice a problem stop with it.",
                    good = r.workerSilentMs < 3_600_000L,
                ),
            )
        }
        speedFact(r)?.let { add(it) }
        silenceFacts(r).forEach { add(it) }
        queueFact(r)?.let { add(it) }
    }

    /** Only the facts that are actually wrong — what a report leads with. */
    fun problems(r: Reading): List<Fact> = verdicts(r).filter { it.good == false }

    // --- the individual verdicts -----------------------------------------------------------

    /**
     * Switched on and actually running are different questions, and the gap between them is the
     * failure this app was blind to for months: Android's toggle records the owner's *choice*, so
     * it keeps reading ON over a watcher the phone has killed.
     */
    private fun runningFact(r: Reading): Fact = when {
        !r.serviceEnabled -> Fact(
            "The blocker is switched off",
            "Accessibility is off for AppBlocker, so nothing is being blocked.",
            good = false,
        )
        r.serviceRunning -> Fact(
            "The blocker is running",
            "Switched on and actually watching. Last thing it saw: " +
                agoText(r.sinceLastEventMs) + ". Last heartbeat: " + agoText(r.sinceAliveMs) + ".",
            good = true,
        )
        // A process seconds old has not failed, it has just started — accusing it here would make
        // every cold start look like a death and the finding would stop meaning anything.
        r.processAgeMs in 1 until 30_000L -> Fact(
            "The blocker is still starting up",
            "The app has only been running for ${r.processAgeMs / 1000}s, which is too soon to " +
                "expect the watcher to have reconnected.",
            good = null,
        )
        else -> Fact(
            "Switched on, but NOT running",
            "Android says it is on, but the watcher is not there — the phone shut it down and " +
                "left the switch reading ON. Nothing is being blocked.",
            good = false,
        )
    }

    /**
     * Quiet is only evidence when something was happening.
     *
     * `sinceLastEventMs` alone cannot tell four unprotected hours from a phone on a table, which
     * is why the watchdog's own stale rule needs measured foreground minutes too. Reporting the
     * pair together is the whole point — see [Reading.usedMinutes].
     */
    private fun quietFact(r: Reading): Fact? {
        if (!r.serviceEnabled || r.sinceLastEventMs < 0L) return null
        val used = r.usedMinutes ?: return null
        if (r.sinceLastEventMs < 600_000L) return null
        val quiet = agoText(r.sinceLastEventMs)
        return if (used >= QUIET_WITH_USE_MIN) {
            Fact(
                "The blocker has seen nothing for $quiet, through $used minutes of use",
                "The phone was in use and the watcher was told about none of it. This is the " +
                    "measurement that separates a stoppage from an idle phone.",
                good = false,
            )
        } else {
            Fact(
                "The blocker has seen nothing for $quiet",
                "Only $used minutes of the phone being used in that time, so the quiet is most " +
                    "likely the phone being down rather than the blocker being deaf.",
                good = null,
            )
        }
    }

    private fun outageFact(r: Reading): Fact? {
        if (r.outageCount == 0 && r.foundDead == 0) return null
        // foundDead can move without a finished episode (and vice versa across an update), so both
        // are named rather than assuming one implies the other.
        return Fact(
            "Blocking has stopped ${r.outageCount} times, and been found dead ${r.foundDead} times",
            if (r.outageTotalMs > 0L) {
                "Unprotected for ${minutesText(r.outageTotalMs)} in total; the worst single one " +
                    "was ${minutesText(r.outageLongestMs)}."
            } else {
                "No length was ever measured for these, so only the count is known."
            },
            // A count that is climbing is the measurement, not an alarm on its own — it is how we
            // find out whether a fix worked. The alarm is `outageOpen`, above.
            good = null,
        )
    }

    private fun speedFact(r: Reading): Fact? {
        val quick = r.quickSharePercent ?: return null
        return Fact(
            "$quick% of blocks appear in under half a second",
            "Measured over ${r.blocksMeasured} covers, ${r.slowBlocks} of which took more than " +
                "two seconds. Half a second is roughly where a block stops feeling like an answer " +
                "to what you did and starts feeling like something that happened later.",
            good = quick >= QUICK_SHARE_TARGET,
            group = Group.SPEED,
        )
    }

    /**
     * What the blocker declined to do. The only readings here whose **zero is the interesting
     * value** — see [SilenceLog]: every instrument in this app recorded its successes, the
     * declines logged nothing, and a relapse was the only way anyone found out.
     */
    private fun silenceFacts(r: Reading): List<Fact> = buildList {
        if (r.deafSpells > 0) {
            add(
                Fact(
                    "Times it went quiet after a block was dismissed: ${r.deafSpells}",
                    "Each is a spell where a cover was dismissed and the blocker then stopped " +
                        "watching instead of looking again.",
                    good = false,
                    group = Group.SILENCE,
                ),
            )
        }
        if (r.lateSkips > 0) {
            add(
                Fact(
                    "Checks skipped during those quiet spells: ${r.lateSkips}",
                    "How many times it looked away while a spell was running.",
                    good = null,
                    group = Group.SILENCE,
                ),
            )
        }
        if (r.unreadyDecisions > 0) {
            add(
                Fact(
                    "Decisions made before the block list had loaded: ${r.unreadyDecisions}",
                    "Every restart has a moment before the rules arrive. A decision taken in it " +
                        "is a decision taken without knowing what to block.",
                    good = false,
                    group = Group.SILENCE,
                ),
            )
        }
        if (r.shortsBlind > 0) {
            add(
                Fact(
                    "Shorts closed without confirmation: ${r.shortsBlind}",
                    "The player could not be confirmed shut before leaving, so nothing was " +
                        "pressed — deliberately, because pressing Home is what left a Short " +
                        "floating over the screen.",
                    good = null,
                    group = Group.SILENCE,
                ),
            )
        }
    }

    /**
     * **The reporter reporting on itself.**
     *
     * Written after five days in which every report the phone produced was queued and none arrived,
     * and nothing in a report could have said so. A backlog on arrival means the reports before
     * this one did not get through, which is a fact about the channel that only the channel can
     * tell us.
     */
    private fun queueFact(r: Reading): Fact? {
        if (r.queuedReports <= 1 && r.reportsLeftToday > 0) return null
        return Fact(
            if (r.reportsLeftToday <= 0) "Today's report limit is used up" else
                "${r.queuedReports} reports are waiting to be sent",
            if (r.reportsLeftToday <= 0) {
                "Anything else recorded today stays on the phone until tomorrow."
            } else {
                "They were written and could not be delivered. If this report arrived, the ones " +
                    "behind it should be arriving too — if they are not, the route is broken " +
                    "rather than the phone."
            },
            good = false,
            group = Group.REPORTING,
        )
    }

    // --- rendering for a report ------------------------------------------------------------

    /** Marks a fact that is wrong. A report leads with these and nothing else. */
    const val BAD = "❌"

    /** Marks the healthy answer. */
    const val OK = "✅"

    /** Marks a number that is worth knowing and is nobody's fault — the `good = null` case. */
    const val PLAIN = "•"

    /**
     * One markdown line per fact, carrying its verdict as a leading marker.
     *
     * The marker is a prefix rather than a separate field because these lines are **stored** and
     * read back by a later build of the app ([BugReportQueue]): a string survives that, a shape
     * has to be migrated. Filtering on the prefix is what lets a report print the bad ones first
     * without re-running thresholds that may have moved since the report was written.
     */
    fun render(facts: List<Fact>): List<String> = facts.map {
        val mark = when (it.good) {
            false -> BAD
            true -> OK
            null -> PLAIN
        }
        "$mark **${it.title}** — ${it.detail}"
    }

    /** The stored lines that represent something wrong, for a report's opening section. */
    fun problemLines(lines: List<String>): List<String> = lines.filter { it.startsWith(BAD) }

    // --- shared wording --------------------------------------------------------------------

    /**
     * "just now" / "4 min ago" / "2 h ago". A report used to print raw seconds, so reading one
     * meant dividing `121808s ago` by 3600 to find out it meant yesterday.
     */
    fun agoText(millis: Long): String = when {
        millis < 0L -> "never"
        millis < 60_000L -> "just now"
        millis < 3_600_000L -> "${millis / 60_000L} min ago"
        millis < 86_400_000L -> "${millis / 3_600_000L} h ago"
        else -> "${millis / 86_400_000L} days ago"
    }

    /** A duration, not an age: "42 min" / "3 h 5 min". */
    fun minutesText(millis: Long): String {
        if (millis < 0L) return "an unknown time"
        val mins = millis / 60_000L
        return when {
            mins < 1L -> "under a minute"
            mins < 60L -> "$mins min"
            mins % 60L == 0L -> "${mins / 60L} h"
            else -> "${mins / 60L} h ${mins % 60L} min"
        }
    }
}
