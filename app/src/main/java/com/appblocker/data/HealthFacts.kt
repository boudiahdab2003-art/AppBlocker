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
        /**
         * Foreground minutes since the **background scheduler** last ran — null when it cannot be
         * told (no usage access, or the scheduler has never been seen to run).
         *
         * ⚠️ **This is not [usedMinutes] and must not be replaced by it.** [usedMinutes] is
         * measured over `[lastEventAt, now]`, which is a different window and usually a much
         * shorter one — on a report filed the moment the app is opened it spans seconds. Reading
         * it against a scheduler that has been silent for three quarters of an hour would pair two
         * counters that count different things (invariant 57) and would answer "idle" for a phone
         * that had been in use the whole time, turning a real fault permanently grey. Same reader
         * (`UsageTracker.totalMinutesInRange`), different range.
         */
        val usedSinceWorkerMin: Int? = null,
        /** Millis this process has been alive. */
        val processAgeMs: Long,
        val updatePaused: Boolean,
        val updatePausePending: Boolean,
        /**
         * How long [updatePausePending] has been raised, or 0 when it is not.
         *
         * The pause resolves asynchronously (it reads the Strict row out of Room first), so a
         * pending that is milliseconds old is the state machine mid-step, not a fault -- and a
         * report written on the same launch as the update always caught it there.
         *
         * ⚠️ **Defaults to -1, "no stamp", which counts as STUCK rather than as new** --
         * the same sentinel `HealthReader.since` returns for a stamp that was never written. A
         * flag raised without one was raised by a build that predates it, so it has survived an
         * install; defaulting to 0 would have read that as "raised at the epoch"... and worse,
         * would have made every caller that omits the argument silently healthy.
         */
        val updatePausePendingMs: Long = -1L,
        val foundDead: Int,
        val outageOpen: Boolean,
        val outageCount: Int,
        val outageTotalMs: Long,
        /** The share of [outageTotalMs] the watcher timed itself, and over how many stoppages.
         *  Defaults to nothing measured, which is what every phone's history looks like before
         *  v1.157 — and the report has to say that rather than imply the total is exact. */
        val outageTimedMs: Long = 0L,
        val outageTimedCount: Int = 0,
        /** ⚠️ **Minutes of real phone use lost across every stoppage, and how many stoppages that
         *  covers.** The cost, as opposed to the length — see [com.appblocker.data.OutageLog.Episode.usedDuringMin].
         *  A count of zero means nothing has been measured yet and must not read as "no use lost". */
        val outageUsedMin: Int = 0,
        val outageUsedCount: Int = 0,
        val outageLongestMs: Long,
        val probeFailStreak: Int,
        val bindDeferrals: Int,
        /** ⚠️ **How long after the last restart the app's own start-up ran**, or
         *  [BootAudit.MISSED] / [BootAudit.NEVER]. A health fact rather than only a report field,
         *  because a restart that WORKS produces no stoppage report — so the number built to
         *  answer "did we start after a reboot" was invisible in exactly the case it answers. */
        val bootHeardMs: Long = BootAudit.NEVER,
        val bootsMissed: Int = 0,
        /** Millis since the background scheduler last ran, or [ProtectionPulse.UNKNOWN]. */
        val workerSilentMs: Long,
        /** Share of covers that appeared in under half a second, or null when none measured.
         *  ⚠️ **Every path blended together — what the owner waited for, not a verdict.** See
         *  [instantSharePercent]. */
        val quickSharePercent: Int?,
        val blocksMeasured: Int,
        val slowBlocks: Int,
        /**
         * The same share over [BlockLatency.Path.INSTANT] covers only — null until that path has
         * recorded any.
         *
         * ⚠️ **The only share a speed verdict may be taken from.** [quickSharePercent] mixes the
         * instant paths with the debounced page scan, which waits 250–950ms on purpose before it
         * does anything, so a settled cover cannot reach the fastest two buckets however quick the
         * code is. The blended figure therefore falls when the owner browses more and rises when
         * he opens blocked apps more, with nothing in the app having changed — and that movement
         * was read as blocking getting slower and reported to him as such on 6 Sep 2026.
         */
        val instantSharePercent: Int? = null,
        /** How many [BlockLatency.Path.INSTANT] covers that share is over — a percentage taken
         *  over a handful of them is noise, see [BlockLatency.MIN_FOR_VERDICT]. */
        val instantMeasured: Int = 0,
        /** The same share over [BlockLatency.Path.SETTLED] covers, reported beside the verdict so
         *  the wait is visible rather than hidden inside it. Null until any are recorded. */
        val settledSharePercent: Int? = null,
        val settledMeasured: Int = 0,
        val deafSpells: Int,
        /** ⚠️ **How many of [deafSpells] happened TODAY.** The lifetime count alone kept a fault at
         *  the top of "what looks wrong" forever: one spell, from before v1.153 closed the cause,
         *  and the row could never clear again however long it went without recurring. A warning
         *  that outlives what caused it is the shape this whole week has been about. */
        val deafSpellsToday: Int = 0,
        val lateSkips: Int,
        val unreadyDecisions: Int,
        /** The half of [unreadyDecisions] that had no snapshot to answer from. Defaults to 0 so a
         *  reading taken by an older caller cannot invent a fault. */
        val unreadyBlind: Int = 0,
        val shortsBlind: Int,
        /** Reports written but not yet delivered, and how many more may be sent today. */
        val queuedReports: Int,
        val reportsLeftToday: Int,
        /** Whether reporting is configured in this build at all. */
        val reportingOn: Boolean = true,
        /**
         * What came back from the last delivery attempt — an HTTP status as text, an exception
         * class name, or null when nothing has ever been tried. **Our own literal either way**;
         * never a response body.
         */
        val lastSendResult: String? = null,
        /** Millis since that attempt, or -1 when there has never been one. */
        val sinceLastSendMs: Long = -1L,
    )

    /**
     * **Measured minutes of use before quiet counts against the watcher.**
     *
     * ⚠️ **This must equal `ProtectionState.STALE_MIN_USED_MINUTES`, and for a long time it only
     * did by luck** — two literal `15`s in two packages with a comment asserting they matched and
     * nothing checking. `HealthFactsTest` compares them now, which is the only version of "these
     * agree" worth writing: invariant 54 was exactly this, one release after the comment claiming
     * agreement had stopped being true.
     *
     * ⚠️ **The KDoc here used to claim it matched the watchdog's stale WINDOW too, and that was
     * simply false.** The watchdog needs `STALE_AFTER_MS` — two hours — before quiet means
     * anything; this section reports at [QUIET_MIN_MS], fifteen minutes. So a report could call
     * blocking broken in a state the watchdog calls OK, at one eighth of its threshold, under a
     * comment saying that must never happen.
     *
     * The divergence is kept, deliberately, and the reason is the week that found it: **describing
     * is not concluding.** The watchdog's two hours is the bar for ACTING — opening an outage,
     * raising an alert — and it is set high because a false alarm there is expensive. Quiet paired
     * with real use is the one signal that separated a stoppage from a phone on a table, and
     * suppressing it for two hours would hide the exact thing this month was spent learning to
     * see. [QUIET_ONLY_DESCRIBES] pins that this is a deliberate difference rather than another
     * pair of numbers drifting apart.
     */
    const val QUIET_WITH_USE_MIN = 15

    /** How long quiet has to run before this section says anything at all. Deliberately shorter
     *  than the watchdog's `STALE_AFTER_MS` — see [QUIET_WITH_USE_MIN]. */
    const val QUIET_MIN_MS = 600_000L

    /** Marks [QUIET_MIN_MS] being under the watchdog's stale window as intended, so a test can
     *  assert the difference on purpose rather than a future reader "fixing" one of them. */
    const val QUIET_ONLY_DESCRIBES = true

    /** Under this share of covers appearing in half a second, blocking stops feeling like an answer. */
    const val QUICK_SHARE_TARGET = 80

    /**
     * How long the update pause may sit `pending` before that is a finding.
     *
     * The resolve is a coroutine that reads the Strict session out of Room, and the profile report
     * is filed from the same launch that raised the flag -- so the honest answer is "milliseconds",
     * and a minute is generous by three orders of magnitude on purpose. What it must not do is
     * stretch far enough to hide a `pending` that never resolves, because that one really does
     * switch blocking off again on the next service connect.
     */
    const val PAUSE_RESOLVE_GRACE_MS = 60_000L

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
        // A `pending` still inside the resolve window is not a finding, the same rule `bindPending`
        // already applies to STALLED: "we have not finished deciding yet" is not "something is
        // wrong". Without this, every manual update filed a fault at the top of its own report --
        // guaranteed, because `MainActivity` raises the flag in onCreate and files the profile
        // report from onResume while the resolver is still on its way to Room. The genuinely stuck
        // case is the one worth seeing, and it was wearing the same words.
        //
        // A pending with NO stamp counts as stuck, not as young: the stamp is written by the same
        // call that raises the flag, so its absence means the flag was raised by a build that did
        // not have it -- it has survived an install, which is exactly the case worth reporting.
        // Reading "unknown" as "fine" would have quietly retired the check for the one phone that
        // already had the fault.
        val pauseStuck = r.updatePausePending &&
            (r.updatePausePendingMs < 0L || r.updatePausePendingMs > PAUSE_RESOLVE_GRACE_MS)
        if (r.updatePaused || pauseStuck) {
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
        // ⚠️ A long lag here is NORMAL and must not read as a fault. Android delivers
        // BOOT_COMPLETED only after the first unlock on a file-based-encryption phone, so a
        // restart at night is heard in the morning — and nothing is lost, because a locked phone
        // cannot open anything that needs blocking.
        if (r.bootHeardMs >= 0L) {
            add(
                Fact(
                    "The blocker started itself after the last restart",
                    "It was running ${r.bootHeardMs / 1000}s after the phone came back — or " +
                        "after you first unlocked it, which is when Android hands out that " +
                        "signal. A long wait here is normal and costs nothing: a locked phone " +
                        "cannot open anything that needs blocking.",
                    good = true,
                ),
            )
        } else if (r.bootHeardMs == BootAudit.MISSED) {
            add(
                Fact(
                    "The blocker's own start-up did not run after the last restart",
                    "Nothing re-armed the background checks until the app was opened, so a " +
                        "stoppage in that time would have gone unnoticed" +
                        if (r.bootsMissed > 1) " — ${r.bootsMissed} restarts so far." else ".",
                    good = false,
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
        schedulerFact(r)?.let { add(it) }
        speedFact(r)?.let { add(it) }
        silenceFacts(r).forEach { add(it) }
        queueFact(r)?.let { add(it) }
        deliveryFacts(r).forEach { add(it) }
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
        if (r.sinceLastEventMs < QUIET_MIN_MS) return null
        val quiet = agoText(r.sinceLastEventMs)
        return if (used >= QUIET_WITH_USE_MIN) {
            Fact(
                "The blocker has seen nothing for $quiet, through $used minutes of use",
                "The phone was in use and the watcher was told about none of it. This is the " +
                    "measurement that separates a stoppage from an idle phone. It is reported " +
                    "sooner than the app acts on it: the watchdog waits two hours before calling " +
                    "blocking stopped, because acting on a false alarm is expensive and saying " +
                    "what was seen is not.",
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

    /**
     * The background scheduler, judged the way [quietFact] judges silence: **only against time the
     * phone was actually being used.**
     *
     * The threshold itself is not in question and is not a second opinion — it is
     * [ProtectionPulse.SILENT_AFTER_MS], the same constant the alarm acts on, and the prose is
     * generated from it so the two cannot drift (that mismatch was the v1.160 fix). What was wrong
     * is what the app *concluded* from it. The reading is taken when the report is filed, which is
     * the moment the owner opens the app — usually straight after the phone has been asleep, and
     * Doze is exactly when a periodic job does not run. So on his phone the row was red on
     * essentially every report, and a fault that is always present is one the reader learns to
     * skip past. That is invariant 50's standing question asked of a row nobody had asked it of.
     *
     * A scheduler that has not run while nobody was touching the phone has cost nothing: the
     * checks it carries exist to notice that blocking stopped, and a phone in a pocket has nothing
     * to block. Silence *through real use* is the case that costs protection, and that stays ❌.
     *
     * ⚠️ **Unknown use is not idle.** When [Reading.usedSinceWorkerMin] is null the app cannot
     * tell the two apart (no usage access — his second device reports exactly this), and the fact
     * must then be a plain reading rather than either verdict. Answering "not a fault" there would
     * be the null-means-no mistake invariant 47 was written for; dropping the row entirely would
     * hide the scheduler from the phones least able to report anything else.
     */
    private fun schedulerFact(r: Reading): Fact? {
        if (r.workerSilentMs <= 0L) return null
        val silent = r.workerSilentMs >= ProtectionPulse.SILENT_AFTER_MS
        val threshold = ProtectionPulse.SILENT_AFTER_MS / 60_000
        val what = "Every background check in this app is one of its jobs, so when it stops, the " +
            "checks that would notice a problem stop with it. It is meant to run every quarter " +
            "of an hour, and the app treats $threshold minutes of quiet as it having stopped."
        val title = "The background scheduler last ran ${agoText(r.workerSilentMs)}"
        if (!silent) return Fact(title, what, good = true)
        val used = r.usedSinceWorkerMin
        return when {
            used == null -> Fact(
                title,
                "$what This phone cannot measure how much it was used in that time, so whether " +
                    "anything was lost is unknown — it is reported rather than judged.",
                good = null,
            )
            used >= QUIET_WITH_USE_MIN -> Fact(
                "$title, through $used minutes of use",
                "$what The phone was in use for $used of those minutes, so the checks that " +
                    "notice a stoppage were not running at a time when a stoppage would have " +
                    "cost you something.",
                good = false,
            )
            else -> Fact(
                title,
                "$what Only $used minutes of the phone being used in that time, so the job was " +
                    "most likely held back while the phone slept. A check that does not run " +
                    "while nothing can be opened has cost nothing.",
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
                // ⚠️ One number, two quantities, and it took until 5 Sep 2026 to notice. Invariant
                // 44 put the measured/upper-bound distinction on every line of the episode list
                // and then left the summary — the sentence he actually reads — as a flat total.
                val head = "Unprotected for ${minutesText(r.outageTotalMs)} in total; the worst " +
                    "single one was ${minutesText(r.outageLongestMs)}."
                // ⚠️ **The cost comes first, because the length on its own misled us for a week.**
                // On 6 Sep 2026 every stoppage on record turned out to have happened on a phone
                // nobody was touching, and "unprotected for 26 hours" had been read — and quoted
                // back to him — as if it were exposure. A blocker that is down while he is asleep
                // costs him nothing. Length is the size of the fault; this is what it took from
                // him, and only one of the two is worth being alarmed by.
                val cost = when {
                    r.outageUsedCount <= 0 ->
                        " How much of that happened while you were actually using the phone was " +
                            "never recorded. From this version every stoppage measures it."
                    r.outageUsedMin <= 0 ->
                        " **None of it happened while you were using the phone.** All " +
                            "${r.outageUsedCount} measured so far were on a phone that was not " +
                            "being touched, and a blocker that is off while the phone is idle " +
                            "costs you nothing — there is nothing to block."
                    else ->
                        " Of that, ${r.outageUsedMin} minute(s) were while you were actually " +
                            "using the phone, across ${r.outageUsedCount} measured stoppages. " +
                            "That is the part that cost you something."
                }
                val timing = when {
                    r.outageTimedCount >= r.outageCount -> ""
                    r.outageTimedCount > 0 ->
                        " Of the length, ${minutesText(r.outageTimedMs)} over " +
                            "${r.outageTimedCount} of them was timed by the blocker itself; the " +
                            "rest is a maximum that includes however long it took something to " +
                            "notice blocking was back."
                    else ->
                        " Treat the length as a maximum — none of it was timed by the blocker " +
                            "itself, so each one includes however long it took something to " +
                            "notice blocking was back."
                }
                head + cost + timing
            } else {
                "No length was ever measured for these, so only the count is known."
            },
            // A count that is climbing is the measurement, not an alarm on its own — it is how we
            // find out whether a fix worked. The alarm is `outageOpen`, above.
            good = null,
        )
    }

    /**
     * How fast blocking is — judged on the **instant** paths only, with the settled path reported
     * beside it rather than folded into it.
     *
     * ⚠️ **The blended figure was never a measure of speed and must not be the verdict again.**
     * Blocks arrive by three pipelines. An app block and an address-bar block are decided in the
     * same turn of the main thread as the event that caused them. The page scan deliberately waits
     * for the page to settle first — 250ms, and up to ~950ms across a burst — and the stopwatch
     * starts at the event, so that wait is inside the number. A settled cover therefore cannot
     * land in the fastest two buckets no matter how quick the code is
     * ([BlockLatency.Path] carries the arithmetic). Blending them produces a percentage that
     * tracks **how the owner used his phone**, and that is what slid from 82% to 77% while nothing
     * about blocking changed. Invariant 50 examined this number and kept the lifetime verdict on
     * sound reasoning, but it did not know the population was a mixture.
     *
     * ⚠️ **A share is not a verdict until there is enough of it.** Under
     * [BlockLatency.MIN_FOR_VERDICT] covers the fact is still printed — the owner should see it —
     * but `good` is null, because at n≈18 one cover moves the figure by five points.
     */
    private fun speedFact(r: Reading): Fact? {
        val quick = r.quickSharePercent ?: return null
        val settled = r.settledSharePercent?.let {
            " Website blocks wait for the page to stop changing before they read it, so their " +
                "own figure is kept apart: $it% of ${r.settledMeasured}, and most of that is the " +
                "wait rather than the work."
        } ?: ""
        val enough = r.instantMeasured >= BlockLatency.MIN_FOR_VERDICT
        val instant = r.instantSharePercent
        val headline = if (instant != null) {
            "$instant% of instant blocks appear in under half a second"
        } else {
            "$quick% of blocks appear in under half a second"
        }
        val counted = if (instant != null) {
            "Measured over ${r.instantMeasured} covers that had nothing to wait for — an app " +
                "opening, or an address read straight from the bar."
        } else {
            "Measured over ${r.blocksMeasured} covers, ${r.slowBlocks} of which took more than " +
                "two seconds."
        }
        val young = if (instant != null && !enough) {
            " Too few to call yet: under ${BlockLatency.MIN_FOR_VERDICT} covers one slow block " +
                "moves this by several points, so it is reported rather than judged."
        } else {
            ""
        }
        return Fact(
            headline,
            "$counted Half a second is roughly where a block stops feeling like an answer to what " +
                "you did and starts feeling like something that happened later.$settled$young",
            good = if (instant == null || !enough) null else instant >= QUICK_SHARE_TARGET,
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
                    if (r.deafSpellsToday > 0) {
                        "Each is a spell where a cover was dismissed and the blocker then stopped " +
                            "watching instead of looking again. ${r.deafSpellsToday} today."
                    } else {
                        "Each is a spell where a cover was dismissed and the blocker then stopped " +
                            "watching instead of looking again. None today — v1.153 made a " +
                            "declined cover book its own return, and this is the count from " +
                            "before that."
                    },
                    // Only today's is a fault. The lifetime count is history, and history that
                    // can never clear teaches the reader to skip the section it leads.
                    good = if (r.deafSpellsToday > 0) false else null,
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
        // ⚠️ Two facts, because the counter had been saying the wrong one. Entering the window
        // is ordinary — a phone that rebinds thirty times a day enters it constantly and the
        // saved copy of the rules answers every time — but it was reported as a failure, and on
        // 5 Sep 2026 it sat at the top of "what looks wrong here" on a phone with nothing wrong
        // with it. Only the blind half is a fault, and it is the one nobody could see.
        if (r.unreadyBlind > 0) {
            add(
                Fact(
                    "Decisions made with no rules and no saved copy: ${r.unreadyBlind}",
                    "The blocker restarted, was asked about an app before the rules arrived, and " +
                        "had no saved copy to fall back on. Those are the moments blocking is " +
                        "genuinely off.",
                    good = false,
                    group = Group.SILENCE,
                ),
            )
        }
        if (r.unreadyDecisions > 0) {
            add(
                Fact(
                    "Decisions made before the block list had loaded: ${r.unreadyDecisions}",
                    if (r.unreadyBlind > 0) {
                        "Every restart has a moment before the rules arrive. The saved copy of " +
                            "your rules covers it — except for the ones counted above."
                    } else {
                        "Every restart has a moment before the rules arrive. The saved copy of " +
                            "your rules covered every one of them, so nothing was let through."
                    },
                    good = null,
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
    /**
     * **Did the last delivery attempt succeed?** `null` when nothing has ever been tried.
     *
     * One rule, because there were two. [queueFact] tested `lastSendResult == null` and treated
     * *any* recorded result as a failure — so on 5 Sep 2026 a report said "✅ The last report was
     * delivered, 10 h ago" and "❌ 2 reports … could not be delivered" about the same working
     * channel, and the red one led the worst-first section. `lastSendResult` being non-null means
     * an attempt HAPPENED, not that it failed; [deliveryFacts] forty lines below already knew
     * that. Invariant 47's shape again: the correct sibling sitting beside the incorrect one.
     */
    internal fun lastSendSucceeded(result: String?): Boolean? =
        result?.let { it.toIntOrNull()?.let { code -> code in 200..299 } ?: false }

    private fun queueFact(r: Reading): Fact? {
        if (r.queuedReports <= 1 && r.reportsLeftToday > 0) return null
        return Fact(
            if (r.reportsLeftToday <= 0) "Today's report limit is used up" else
                "${r.queuedReports} reports are waiting to be sent",
            if (r.reportsLeftToday <= 0) {
                "Anything else recorded today stays on the phone until tomorrow."
            } else when (lastSendSucceeded(r.lastSendResult)) {
                null ->
                    "They are written and waiting. Nothing has been attempted yet, so this is " +
                        "not a failure — opening the app is what starts a delivery."
                true ->
                    "They are waiting their turn. The last one went through, so the route is " +
                        "working — anything over today's limit goes out tomorrow."
                false ->
                    "They were written and could not be delivered. If this report arrived, the " +
                        "ones behind it should be arriving too — if they are not, the route is " +
                        "broken rather than the phone."
            },
            // ⚠️ A backlog is only a *fault* once a delivery has been tried and failed. Saying
            // "could not be delivered" about reports nothing has attempted yet is a claim the app
            // cannot support — and a finding that fires on ordinary behaviour teaches the reader
            // to skip the section, which is how the real failure stayed invisible for six days.
            // A backlog is a fault only when a delivery was tried and FAILED. Nothing tried yet
            // is not one, a successful send with reports still queued behind today's limit is not
            // one either, and calling ordinary throttling a failure is how a section meant for
            // real findings teaches its reader to skip it.
            // A spent daily cap stays a finding: it is the one state where evidence is being held
            // back, and I need to know I am not seeing everything. Untouched here on purpose.
            // What changed is only the case this was wrong about — a backlog waiting behind a
            // send that WORKED, which is ordinary throttling and was printing "could not be
            // delivered" in red under "the last report was delivered".
            good = if (r.reportsLeftToday > 0 && lastSendSucceeded(r.lastSendResult) != false) {
                null
            } else {
                false
            },
            group = Group.REPORTING,
        )
    }

    /**
     * **Can the app actually reach its own reporting server?**
     *
     * Written after six days in which it could not, and nothing said so. AppBlocker's own family
     * DNS filter blocks dynamic-DNS domains as a category — a correct thing for a content filter
     * to do — and the app's reporting host was on one. So every send died at name resolution,
     * the queue grew, and the phone, the screen and the tracker all showed exactly the same thing
     * as a quiet week.
     *
     * The three states below are three different diagnoses and must never render alike:
     * **never tried**, **tried and was refused**, and **could not find the server**.
     */
    private fun deliveryFacts(r: Reading): List<Fact> = buildList {
        if (!r.reportingOn) {
            add(
                Fact(
                    "Reporting is switched off in this build",
                    "Nothing is being sent anywhere, by design. This is normal for a version " +
                        "built on a computer rather than published.",
                    good = null,
                    group = Group.REPORTING,
                ),
            )
            return@buildList
        }
        val result = r.lastSendResult
        if (result == null) {
            // Only interesting once something is actually waiting: a phone with an empty queue has
            // simply had nothing to say, which is the healthy case and not worth a row.
            if (r.queuedReports > 0) {
                add(
                    Fact(
                        "Nothing has ever been sent from this phone",
                        "${r.queuedReports} report(s) are waiting and no delivery has been " +
                            "attempted yet. Opening the app is what triggers one.",
                        // Not a fault: on a fresh install this is simply "it has not happened
                        // yet". A delivery that was actually TRIED and failed is the finding, and
                        // that case carries a result to say so.
                        good = null,
                        group = Group.REPORTING,
                    ),
                )
            }
            return@buildList
        }
        val ago = agoText(r.sinceLastSendMs)
        val code = result.toIntOrNull()
        add(
            when {
                lastSendSucceeded(result) == true -> Fact(
                    "The last report was delivered, $ago",
                    "The route from this phone to the developer is working.",
                    good = true,
                    group = Group.REPORTING,
                )
                // No HTTP status at all: the request never reached a server. On this phone that
                // almost always means the name could not be resolved, and the most likely reason
                // is the app's own network filter — see NetworkFilter.
                code == null -> Fact(
                    "The app cannot reach its own reporting server ($ago)",
                    "The attempt failed before any reply came back — \"$result\". This is usually " +
                        "the phone being unable to look up the server's address. If the family " +
                        "DNS filter is on, check that it is not blocking the app's own address: " +
                        "filters block whole categories, and the app's server can fall inside one.",
                    good = false,
                    group = Group.REPORTING,
                )
                code in 401..403 -> Fact(
                    "The reporting server refused this phone ($ago)",
                    "It answered $code. The phone reached the server, and the server would not " +
                        "accept it — the shared password in this build does not match the one on " +
                        "the server. A new release fixes that; nothing on this phone can.",
                    good = false,
                    group = Group.REPORTING,
                )
                else -> Fact(
                    "The last report was not delivered ($ago)",
                    "The server answered $result. The reports are kept and tried again.",
                    good = false,
                    group = Group.REPORTING,
                )
            },
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
