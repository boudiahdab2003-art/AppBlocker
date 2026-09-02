package com.appblocker.data

import org.json.JSONObject

/**
 * A bug report on its way off the device, and the rules about what may be in one.
 *
 * **Why this exists.** The watcher already records every error it swallowed
 * ([ServiceHealth.recordError]) and the Profile screen already says "tap to clear once you've
 * reported it" — but there was nowhere to report *to*, so the only channel was the owner noticing
 * a row and mentioning it. The failures worth catching are the ones he cannot notice:
 * `docs/BLOCKING_INVARIANTS.md` opens by saying under-blocking is invisible, because a block that
 * silently never happens looks exactly like a quiet day. Those reports have to send themselves.
 *
 * ## The privacy contract, which is the whole point of this file
 *
 * This app holds about the most sensitive data an app can: the blocked-keyword list is largely
 * adult words, and the watcher reads browser URLs and on-screen text as a matter of course. A
 * report leaving the device must therefore be built from an **allow-list** — fields named here,
 * one by one — and never by taking something and stripping the bad parts out. A block-list is a
 * bet that you thought of every leak; an allow-list is a bet that you thought of every *feature*,
 * and being wrong the second way costs a missing diagnostic rather than a published secret.
 *
 * Permitted, and this is the complete list:
 *  - app version, build flavour, Android SDK level, device manufacturer/model
 *  - the `where` tag the calling code chose (a literal like "watchdog" — never user data)
 *  - the exception's **class name**
 *  - stack frames from our own package only
 *  - whatever free text the owner typed into the report box himself
 *  - the app's own settings and counters, and **only** those named in [ALLOWED_CONTEXT_KEYS] —
 *    which layout, whether the service is running, how many blocks today. Every one is a choice
 *    the owner made or a number, never something he read, typed, visited or blocked.
 *
 * Forbidden, always: blocked keywords, quote text, URLs, on-screen text, blocked app names or
 * package names, the owner's name, location, anything from the coach conversation, **and
 * anything from Recovery** — no journal entry, and not the clean counter either. The counter
 * is only a number, but it is the number a report would be most tempting to attach and the
 * one the privacy policy promises never leaves the phone.
 *
 * **The exception message is deliberately dropped**, not sanitised. `Throwable.message` is
 * precisely where a value that caused a failure gets quoted — a regex error names the pattern, an
 * illegal-argument error names the argument — and for this app that pattern is a blocked word.
 * The class name plus our own stack frames locate a bug nearly as well and cannot carry a payload.
 * If a report is ever short of detail, add a new *named* field here; never reinstate the message.
 */
data class BugReport(
    /** Where in the app it happened — a literal chosen by the calling code. */
    val where: String,
    /** Exception class name only, or null for a report the owner typed himself. */
    val errorClass: String?,
    /** Our own stack frames, already filtered. Empty for a hand-written report. */
    val frames: List<String>,
    /** What the owner typed, if he typed anything. The one field that is user text by design. */
    val note: String?,
    val appVersion: String,
    val flavor: String,
    val androidSdk: Int,
    val device: String,
    /**
     * App *settings and counters* at the moment of the report — never content. Always run through
     * [sanitizeContext], which drops any key not on the allow-list, so this cannot become a
     * back door for the data the rest of this class is careful to exclude.
     */
    val context: Map<String, String> = emptyMap(),
    /**
     * The last few block screens raised, by shape only — see [BlockLog], which is where the
     * guarantee that these lines cannot carry content lives.
     */
    val recentBlocks: List<String> = emptyList(),
    /**
     * The stoppages this install has finished, newest first, as [OutageLog.Episode.render] lines —
     * numbers and our own literals only, never content.
     *
     * **One episode cannot show a pattern and the pattern is the whole question.** The context
     * carries the newest stoppage and the running total; those say what the last one cost and how
     * many there have been, and neither can say whether they cluster after an update, arrive at
     * the same hour, or follow every reboot. That is the shape a cause has, and it only appears
     * across episodes — so the history travels with every report, the way [recentBlocks] does.
     *
     * ⚠️ **Not the recovery journal, and never anything from it.** The clean counter and the
     * journal are the one part of this app that never leaves the phone; this is the blocker's own
     * failure log and nothing else.
     */
    val recentOutages: List<String> = emptyList(),
    /**
     * What the blocker's own numbers mean, already interpreted — see [HealthFacts].
     *
     * **The report has to answer "what went wrong" without him typing a word**, which is the
     * standard the owner set for it, and a table of raw keys never could: `serviceRunning=false`
     * rendered exactly like `theme=ink`, and the sentence explaining the difference lived on his
     * phone where the person who fixes things cannot read it.
     *
     * Carried as rendered lines rather than as [HealthFacts.Fact] objects because a report is
     * stored to disk and read back by a *later* version of the app (see [BugReportQueue]). Storing
     * the sentence means an old queued report still says what it meant, instead of being
     * re-interpreted by thresholds that have since moved.
     */
    val healthFacts: List<String> = emptyList(),
) {

    /**
     * Groups reports that are "the same bug" so a crash loop opens one issue rather than hundreds.
     *
     * Deliberately excludes [note] and every varying field: two crashes at the same place with the
     * same exception are one bug even though their timestamps differ. Hand-written reports are the
     * opposite — **every send he makes is its own report** — because he only sends one by deciding
     * to, and an act of deciding is never a duplicate of an earlier one.
     */
    fun dedupeKey(): String = when {
        // One profile per phone per build. Not per launch: the point is one report per *thing we
        // might have got wrong*, and that only changes when the phone or our guesses do.
        isProfile -> "profile:$device|$appVersion"
        // One per episode, keyed on when it started. Deliberately NOT collapsed the way faults
        // are: two outages are two outages even when they look identical, and the rate is the
        // measurement being taken. The start stamp is what makes them distinguishable at all.
        isOutage -> "outage:${context["outageAt"].orEmpty()}"
        // One per calendar week, which is the whole contract: the summary is filed on the first
        // app open of a new week and must not arrive again on the second.
        isWeekly -> "weekly:${context["weekOf"].orEmpty()}"
        // Keyed on **when he pressed Send**, not on what he wrote.
        //
        // It used to be `note.hashCode()`, which made the sentence above this method false in two
        // ways. Typing the same complaint twice produced one key and the queue dropped the second
        // — and once the box became optional, *every* empty send hashed to `0`, so the second
        // blank report he ever sent would have been discarded as a duplicate of the first. The
        // report is now meant to stand on its own without a word typed into it, which makes the
        // text exactly the wrong thing to identify it by.
        note != null -> "note:${context["noteAt"].orEmpty()}|${note.hashCode()}"
        else -> "$where|$errorClass|${frames.firstOrNull().orEmpty()}"
    }

    /**
     * A report about the phone rather than about a fault — see [DeviceProfile].
     *
     * Detected from [where] rather than carried as a field so the queue's stored format is
     * unchanged: a profile written by this version and read back by the next decodes as itself,
     * with no migration and no nullable flag to get wrong.
     */
    val isProfile: Boolean get() = where == PROFILE_WHERE && errorClass == null && note == null

    /** A report about an outage that has ended — see [OutageLog]. Detected the same way, and for
     *  the same reason, as [isProfile]: no new field, so the queue's stored format is unchanged. */
    val isOutage: Boolean get() = where == OUTAGE_WHERE && errorClass == null && note == null

    /** The weekly health summary. Detected from [where] like the two above, for the same reason. */
    val isWeekly: Boolean get() = where == WEEKLY_WHERE && errorClass == null && note == null

    /**
     * The issue title, carrying the version — the first question about any report is "which build
     * is this?", and an issue list where every row starts "Report:" is a list you have to open
     * one by one to read.
     */
    fun title(): String = when {
        // The verdict goes in the title because most profiles are healthy and the issue list has
        // to make the one that isn't findable without opening twenty that are.
        isProfile -> "[$appVersion] $device — " +
            if (profileIsClean) "profile OK" else "PROFILE: something is wrong here"
        // The length and the blame go in the title on purpose: the whole point of these reports is
        // the pattern across them, and a list where every row reads "outage" would have to be
        // opened issue by issue to see the very thing being measured.
        // Says the answer in the list, so a healthy week never needs opening.
        isWeekly -> "[$appVersion] Week of ${context["weekOf"] ?: "?"} — " +
            (weeklyVerdict() ?: "all healthy")
        isOutage -> "[$appVersion] STOPPED for ${context["outageMin"] ?: "?"} min" +
            " — after ${context["outagePreceded"] ?: "?"}" +
            if (context["outageDeaf"] == "true") ", still running" else ", process died"
        // His own words when there are any — nothing summarises a complaint better than the
        // complaint. When there are none, the title has to be earned rather than left blank: an
        // issue list of bare `[1.146]` rows would have to be opened one by one, which is exactly
        // the failure the profile and outage titles above were written to avoid.
        note != null -> "[$appVersion] " + noteTitle()
        else -> "[$appVersion] $errorClass in $where"
    }

    /**
     * What to call a report the owner sent without typing anything.
     *
     * Order of preference: what he wrote, then what he tapped, then what the app found wrong about
     * itself, then a plain statement that he sent it. The last is not a failure case — a report
     * from a phone with nothing wrong is a real and useful thing to receive, and it should say so
     * rather than pretending to a finding it does not have.
     */
    private fun noteTitle(): String {
        val typed = note?.lineSequence()?.firstOrNull()?.take(60)?.trim().orEmpty()
        if (typed.isNotEmpty()) return typed
        val chip = when (context["reportKind"]) {
            "blocked-wrongly" -> "blocked something wrongly"
            "did-not-block" -> "did NOT block something"
            "stopped-working" -> "it stopped working"
            else -> null
        }
        // The worst finding, with its markdown and marker stripped — a title is plain text.
        val worst = HealthFacts.problemLines(healthFacts).firstOrNull()
            ?.substringAfter("**")?.substringBefore("**")?.trim()
        return when {
            chip != null && worst != null -> "$chip — $worst"
            chip != null -> "Sent from the phone — $chip"
            worst != null -> "Sent from the phone — $worst"
            else -> "Sent from the phone — nothing looks wrong"
        }
    }

    /**
     * Whether this phone answered every question the way the app assumed it would.
     *
     * Only the two that break blocking count. A missing browser or an unproven readability claim
     * is a fact about the device, not a fault — marking those as wrong would make every honest
     * profile look broken and the flag would stop meaning anything within a week.
     */
    private val profileIsClean: Boolean
        get() = context["uninstallGuard"] != UninstallGuardVerdict.UNRECOGNISED.name &&
            context["keepAlive"] != "NONE RESOLVED"

    /**
     * The issue body, in GitHub markdown.
     *
     * Written to be read top-down and answer the obvious follow-ups without a reply: what he said,
     * what state the app was in, what it covered recently, and where in the code it happened. The
     * sections are always present — an absent section and an empty one look identical in an issue
     * and mean opposite things, which has already cost one round trip.
     */
    fun body(): String = whatLooksWrong() + when {
        isProfile -> profileBody()
        isOutage -> outageBody()
        isWeekly -> weeklyBody()
        else -> faultBody()
    } + selfKnowledge() + outageHistory()

    /**
     * The shortest verdict that fits in a title, or null when the week was clean.
     *
     * Reads the stored health lines rather than re-deciding anything: the summary is written by
     * the phone and read weeks later, and re-running today's thresholds over an old week would
     * quietly rewrite history.
     */
    private fun weeklyVerdict(): String? = HealthFacts.problemLines(healthFacts)
        .firstOrNull()?.substringAfter("**")?.substringBefore("**")?.trim()

    /**
     * The weekly summary — **the only report that is filed when nothing is wrong.**
     *
     * It exists to make silence mean something. Every other shape arrives only on trouble, so a
     * quiet tracker was indistinguishable from a broken delivery route, and in August 2026 that
     * ambiguity hid a dead route for five days while three releases of stoppage detection sent
     * nothing into it. A weekly heartbeat turns "nothing arrived" into a fact rather than a hope.
     *
     * Deliberately short. Nobody should have to read this one: the title carries the verdict, and
     * the sections that follow are the same ones every other report has.
     */
    private fun weeklyBody(): String = buildString {
        appendLine("### The week")
        appendLine()
        val skipped = context["weeksSkipped"]?.toIntOrNull() ?: 0
        if (skipped > 0) {
            // Said plainly rather than hidden: a summary is only filed when the app is opened, so
            // a gap means "he did not open it" OR "the route was down", and pretending otherwise
            // would recreate the exact ambiguity this report exists to remove.
            appendLine("⚠️ **$skipped week(s) produced no summary.** Either the app was not opened")
            appendLine("in them, or nothing sent was getting through. This one arriving says the")
            appendLine("route works now; it says nothing about which of the two it was.")
            appendLine()
        }
        appendLine("| | |")
        appendLine("|---|---|")
        appendLine("| Week of | ${context["weekOf"] ?: "?"} |")
        appendLine("| Device | $device |")
        appendLine("| Version | $appVersion ($flavor) |")
        appendLine()
        appendLine("Nothing here needs a reply. It is filed so that a week with **no** summary is")
        appendLine("a signal rather than a silence — which is the failure this whole shape exists")
        appendLine("to make visible. The verdict is in the title; the detail is below.")
    }

    /**
     * **The finding, before anything else.**
     *
     * A report used to open on a 24-row alphabetical table in which the one alarming value was
     * indistinguishable from the choice of colour scheme, and the reader's first job was to work
     * out which row mattered. The owner's standard for this rewrite was a report so telling that
     * his own comment is optional — which means the first thing on the page has to be the answer,
     * not the evidence.
     *
     * Renders nothing at all when there are no health facts (an old queued report, or a build that
     * did not collect them) — but when there ARE facts and none of them is bad, it says so out
     * loud. A missing section and a clean bill of health must not look alike; that ambiguity is
     * the one `docs/BLOCKING_INVARIANTS.md` says already cost a whole round trip.
     */
    private fun whatLooksWrong(): String {
        if (healthFacts.isEmpty()) return ""
        val problems = HealthFacts.problemLines(healthFacts)
        return buildString {
            appendLine("### What looks wrong here")
            appendLine()
            if (problems.isEmpty()) {
                appendLine("**Nothing in the blocker's own numbers is wrong.** Every check it can")
                appendLine("run on itself came back healthy, so whatever prompted this report is")
                appendLine("not visible to the app — read the block log and the settings below.")
            } else {
                appendLine("Worst first. Each line is the app's own reading of itself, not a guess.")
                appendLine()
                problems.forEach { appendLine("- $it") }
            }
            appendLine()
        }
    }

    /**
     * Everything the blocker can say about itself, healthy lines included.
     *
     * The healthy ones earn their place for the same reason a clean device profile is still filed:
     * "the blocker is running, last thing it saw just now" is what makes the *absence* of a
     * complaint meaningful. Without them a report can only ever be read as a list of accusations.
     */
    private fun selfKnowledge(): String {
        if (healthFacts.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine("### What the blocker knows about itself")
            appendLine()
            appendLine("${HealthFacts.BAD} wrong · ${HealthFacts.OK} healthy · ${HealthFacts.PLAIN} a number, nobody's fault.")
            appendLine("Written by the phone at the moment of the report, in the order that matters.")
            appendLine()
            healthFacts.forEach { appendLine("- $it") }
        }
    }

    /**
     * Every stoppage this install has finished, appended to **whatever kind of report this is**.
     *
     * On an outage report it is the episode's own history; on a fault or a typed note it is the
     * context the sentence "it stopped working again" has never come with. One episode says what
     * the last stoppage cost. Only the list says whether they cluster after updates, land at one
     * time of day, or follow reboots — and that is the shape a cause has.
     *
     * Empty renders nothing rather than an empty heading: on a phone that has never lost blocking
     * the absence is the point, and a "(none)" block in every report would train the eye to skip
     * the section on the one where it fills up.
     */
    private fun outageHistory(): String = if (recentOutages.isEmpty()) "" else buildString {
        appendLine()
        appendLine("### Every stoppage on this install")
        appendLine()
        appendLine("Newest first, one line each. `deaf=true` was alive the whole time and stopped")
        appendLine("being spoken to; `deaf=false` was killed and never rebound. `after=` is what")
        appendLine("happened just before, `by=` is which check noticed, and `build=` is the")
        appendLine("build that was running — a run of one build is a regression, a spread is not.")
        appendLine("`backBy=` is what was running when blocking came back: **background** means it")
        appendLine("recovered with nobody looking, **app-opened** means it waited for the app to be")
        appendLine("opened. That difference is the whole of the recovery question.")
        appendLine()
        appendLine("⚠️ `build=` is the build NUMBER, and it is one ahead of the 1.x version in")
        appendLine("this report's own heading: build 150 is version 1.149. They are not the same")
        appendLine("number, and reading them as the same names the wrong release.")
        appendLine()
        appendLine("```")
        recentOutages.forEach { appendLine(it) }
        appendLine("```")
    }

    /**
     * An outage's body: how long blocking was off, and the one field that says which of the three
     * suspected causes this was.
     *
     * Leads with the discriminator rather than the duration, because the duration is what the
     * owner feels and the discriminator is what can be acted on. The recent-blocks section stays —
     * unlike a profile, the entries either side of the gap are exactly what a reader wants.
     */
    private fun outageBody(): String = buildString {
        appendLine("**Blocking stopped and has now come back.** Nothing crashed — this is the")
        appendLine("failure that cannot report itself as a fault, so the app times it instead.")
        appendLine("The phone was unprotected for the period below.")
        appendLine()
        appendLine("### Which failure this was")
        appendLine()
        if (context["outageDeaf"] == "true") {
            appendLine("`outageDeaf: true` — **our process was alive the whole time.** It was")
            appendLine("bound and simply stopped receiving events, so a battery manager killing")
            appendLine("the process is NOT the explanation here. Look at the watcher being")
            appendLine("dropped for being slow, or the framework failing to deliver.")
        } else {
            appendLine("`outageDeaf: false` — **the process died and was never rebound.** Android")
            appendLine("kept the accessibility switch reading ON, which is why nothing looked")
            appendLine("wrong. Check `outagePreceded`: `update` means our own install did it.")
        }
        appendLine()
        appendLine("### This outage")
        appendLine()
        appendLine("| | |")
        appendLine("|---|---|")
        appendLine("| Device | $device |")
        appendLine("| Android | SDK $androidSdk |")
        appendLine("| Version | $appVersion ($flavor) |")
        context.toSortedMap().forEach { (k, v) -> appendLine("| $k | `$v` |") }
        appendLine()
        appendLine("`outageMin` is how long it was down; `outageDetectMin` is how much of that")
        appendLine("passed before the app noticed — the second is the protection actually lost,")
        appendLine("and shortening it does not need the cause to be known.")
        appendLine()
        appendLine("`outageEnded` says how it stopped. Only **recovered** means blocking came back:")
        appendLine("**switched-off** is him doing the repair by hand, **paused** is an update landing")
        appendLine("mid-outage. Counting either as a recovery would flatter the app in the one log")
        appendLine("written to judge it.")
        appendLine()
        appendLine("### Blocks either side of the gap")
        appendLine()
        appendLine("Newest first. The useful part is the **hole**: the last cover before blocking")
        appendLine("stopped and the first one after it came back. Same format as a fault report.")
        appendLine()
        appendLine("```")
        if (recentBlocks.isEmpty()) {
            appendLine("(none recorded)")
        } else {
            recentBlocks.forEach { appendLine(it) }
        }
        appendLine("```")
    }

    /**
     * A profile's body, which answers a different question from a fault's.
     *
     * A crash report asks "what went wrong"; this one asks "is what we assumed about this phone
     * true". So it leads with the verdict and the fix, and it does **not** carry the recent-blocks
     * or stack-frame sections — on a healthy phone those are empty, and an issue whose two biggest
     * sections say "(none)" reads as a broken report rather than a clean one.
     */
    private fun profileBody(): String = buildString {
        if (profileIsClean) {
            appendLine("Nothing is wrong here. This is a **healthy phone reporting in** — it is")
            appendLine("worth an issue because it is evidence: a brand nobody owns, confirming")
            appendLine("that what the app assumed about it is actually true. Close it once")
            appendLine("`docs/DEVICE_MATRIX.md` has the row.")
        } else {
            appendLine("**A guess about this phone is wrong, and blocking is weaker here because")
            appendLine("of it.** Nothing crashed and the owner of this phone cannot see it — that")
            appendLine("is what this report is for. The failing row is marked below.")
        }
        appendLine()
        appendLine("### This phone")
        appendLine()
        appendLine("| | |")
        appendLine("|---|---|")
        appendLine("| Device | $device |")
        appendLine("| Android | SDK $androidSdk |")
        appendLine("| Version | $appVersion ($flavor) |")
        context.toSortedMap().forEach { (k, v) ->
            val bad = (k == "uninstallGuard" && v == UninstallGuardVerdict.UNRECOGNISED.name) ||
                (k == "keepAlive" && v == "NONE RESOLVED")
            appendLine("| $k | " + (if (bad) "❌ `$v`" else "`$v`") + " |")
        }
        appendLine()
        appendLine("### What to do about each row")
        appendLine()
        appendLine("- `uninstallGuard` **UNRECOGNISED** — Strict Mode cannot stop an uninstall on")
        appendLine("  this phone. Add the `uninstallHandler` value to `GuardPackages.INSTALLERS`.")
        appendLine("- `keepAlive` **NONE RESOLVED** — the keep-alive button opens the app's own")
        appendLine("  settings page while its label promises this brand's battery screen. Add the")
        appendLine("  real activity to this brand's `DeviceVendor` entry, and its package to")
        appendLine("  `<queries>` in AndroidManifest.xml.")
        appendLine("- `browsersClaimUnproven` — browsers we *claim* we can read the address bar in")
        appendLine("  and never have on this phone. Not a fault by itself; it is where the Mi")
        appendLine("  Browser bug lived for months, so it is where to look when a site block on")
        appendLine("  this brand does nothing.")
    }

    private fun faultBody(): String = buildString {
        // `isNotBlank`, not `!= null`. The text box is optional now, so an empty note is the
        // ordinary case rather than an impossible one — and quoting it produced a bare "> " above
        // every report he sent without typing, which reads as though something failed to load.
        if (!note.isNullOrBlank()) {
            appendLine("> $note".replace("\n", "\n> "))
            appendLine()
        }
        appendLine("### State")
        appendLine()
        appendLine("| | |")
        appendLine("|---|---|")
        appendLine("| Where | `$where` |")
        if (errorClass != null) appendLine("| Error | `$errorClass` |")
        appendLine("| Version | $appVersion ($flavor) |")
        appendLine("| Android | SDK $androidSdk |")
        appendLine("| Device | $device |")
        // Sorted, so the same fact is always on the same line across two reports and they can be
        // read side by side.
        context.toSortedMap().forEach { (k, v) -> appendLine("| $k | $v |") }
        appendLine()
        appendLine("### Recent blocks")
        appendLine()
        appendLine("Newest first. `why=` is the rule that raised it; `window=` is what was really")
        appendLine("on screen: `match` correct, `other` **the cover landed on the wrong app**,")
        appendLine("`blind` the tree was unreadable (we block anyway — not a fault), `n/a` no app.")
        appendLine("`ownUi=true` means it covered AppBlocker's own screen. Entries from before an")
        appendLine("update show `why=?` and never `other`, because the old format couldn't tell")
        appendLine("`other` and `blind` apart — that ambiguity is what this format replaced.")
        appendLine()
        appendLine("```")
        if (recentBlocks.isEmpty()) {
            appendLine("(none recorded — either no block screen has appeared since install,")
            appendLine(" or the block log itself is not working)")
        } else {
            recentBlocks.forEach { appendLine(it) }
        }
        appendLine("```")
        appendLine()
        appendLine("### Where in the code")
        appendLine()
        appendLine("```")
        if (frames.isEmpty()) {
            appendLine(
                if (errorClass == null) "(nothing — the owner sent this himself, it isn't a crash)"
                else "(no frames from our own package — the throw came from framework code)",
            )
        } else {
            frames.forEach { appendLine(it) }
        }
        appendLine("```")
    }

    /** The GitHub "create an issue" payload. */
    fun toJson(): String = JSONObject()
        .put("title", title())
        .put("body", body())
        .toString()

    companion object {

        /** Frames outside our own code are dropped: they are framework noise, and an OEM frame
         *  can name things we would rather not send. Capped so one deep recursion can't post a
         *  megabyte. */
        private const val OUR_PACKAGE = "com.appblocker"
        private const val MAX_FRAMES = 12

        /** A typed note is capped rather than rejected — a long one is still a real report, but
         *  an issue body is not a place for an essay pasted by accident. */
        private const val MAX_NOTE = 2000

        /**
         * The **only** context keys that may ever leave the device.
         *
         * Every one is a setting the owner chose or a count of events — "which layout", "is the
         * service on", "how many blocks today". None of them can hold content: not a keyword, not
         * a URL, not an app name, not a package name.
         *
         * This exists as a list rather than a convention because the caller that assembles the
         * map lives in the service layer where a `Context` is available, and it would be very easy
         * for a later change to add `"keyword" to lastBlockedWord` there and for nobody to notice.
         * Anything not named here is dropped, so that mistake fails safe instead of publishing.
         */
        val ALLOWED_CONTEXT_KEYS = setOf(
            // What the blocker DECLINED to do (SilenceLog) — plain counts, no package, no host,
            // no word. The report already carries what it blocked; without these it can say
            // nothing at all about the half the owner cannot see for himself.
            // What this phone is set up to block — counts and named settings only.
            "updatePaused",
            "ruleCount",
            "lockouts",
            "browsersRead",
            "autoBlockNew",
            "pinSet",
            "quickSession",
            "dangerZone",
            "dangerStrikes",
            // The family DNS filter's STATE only — filtering / on-but-not-filtering / off /
            // can't-tell, and whether the guard is armed. Never the resolver's hostname and
            // never a thing it resolved: this layer sees every lookup the phone makes, so the
            // report says whether it is running and nothing whatsoever about what went through
            // it. (The key is "dnsFilter" and not something clearer on purpose - the allow-list
            // above rejects any key containing "name", which "privateDnsServerName" does.)
            "dnsFilter",
            "learnedCount",
            "deafSpells",
            "lateSkips",
            "unreadyDecisions",
            // How long blocks took to appear (BlockLatency) — a percentage, a total and a tail
            // count, and nothing about what was blocked. The report could describe every block
            // this phone raised and not one thing about how fast any of them arrived, which is
            // the only question the owner has actually asked twice.
            "blockSpeed",
            // Two counts of the app's own exit walk after a Shorts block: how often the reel was
            // confirmed shut before leaving, and how often it could not be. Says nothing about
            // what was watched — not a video id, not a channel, not a word. It exists because
            // whether BACK closes YouTube's reel is unknowable from here and has to be measured
            // on his phone.
            "shortsExit",
            // The watcher's own "can I still read the screen?" probe: a streak of consecutive
            // failures, and how many failures there have been in total. A count of a question the
            // app asked itself — it names nothing that was on the screen, only whether there was
            // one it could read. This is the number that says whether the fifteen-minute detector
            // is firing at all on his phone, which nothing else can answer.
            "probeStreak",
            // Whether `onDestroy` ever ran: an orderly unbind versus a process that vanished.
            // Two entirely different causes that look identical from outside, separated by one
            // boolean.
            "unbindSeen",
            // Which of the three detectors found the last outage — "unbound" / "probe" / "stale".
            // Without it a shorter detection gap cannot be attributed to anything.
            "outageDetectedBy",
            // How many times the alarm found WorkManager had stopped running. Every other
            // background check in this app is a WorkManager job, so this is the first number
            // anyone has had about whether that scheduler is reliable on his phone.
            "workerSilent",
            "layout",
            "theme",
            "serviceOn",
            "protection",
            "guard",
            "blocksToday",
            "adultPack",
            "scanEverywhere",
            // Whether "block unsupported browsers" is on. A setting, not content — and the fact
            // that decided a whole diagnosis: a screenshot of instagram.com unblocked in Mi
            // Browser looked like a regression I had just shipped, and the answer to this one
            // question reversed that. Asking the owner cost a round trip; the report should say.
            "blockUnsupported",
            "overlayPermission",
            "usageAccess",
            // How many of the fields above failed to read. Present only when non-zero, so a
            // half-empty report announces itself instead of looking like a healthy quiet one.
            "fieldErrors",
            // Why the coach last failed, as a [CoachError] name — QUOTA, OFFLINE, BAD_REPLY.
            // A category, like `protection`, never a message: Gemini's error bodies quote the
            // request back, and this app's requests carry usage figures and the conversation.
            "coachError",
            // --- Is the watcher alive, and what has it been through? ---
            // Minutes since the watcher last saw ANY accessibility event. The single most telling
            // number in a report about blocking not happening: "switched on" and "still working"
            // are different facts, and this is the one that separates them.
            "lastEventMin",
            // How many errors the watcher swallowed and kept going, and the `where` tag of the
            // most recent — a literal chosen by our own code ("webScan", "watchdog"), never text
            // from the failure itself. ServiceHealth's stored string includes the exception's
            // message and must NEVER be reported; only the tag travels.
            // Whether the watcher is bound and RUNNING, as opposed to merely switched on
            // (`serviceOn`). The two disagree exactly when the phone has killed it while
            // Android's toggle still says on — a Second Space switch, an OEM battery manager —
            // and that disagreement is the whole diagnosis of "it says it's on and blocks
            // nothing". `foundDead` counts how many times that has happened on this install.
            "serviceRunning",
            "foundDead",
            // "12/2" — heartbeat nudges that found the watcher silent for three minutes, and how
            // many of those nudges threw. The inside view of `outageDeaf`: a climbing first number
            // is a watcher that keeps going deaf while running, a flat zero alongside outages is
            // one that is being killed outright. Two of our own integers, nothing from the phone.
            "revives",
            // How many times Android called onInterrupt on the watcher. Our own integer.
            "interrupts",
            // Foreground minutes since the watcher last saw anything, or `?` when usage access
            // never answered. **The number `protection`'s verdict actually turns on**: quiet is
            // only evidence when the phone was being used, and this is what separates four
            // unprotected hours from a phone left on a table. `?` is a third answer, not a zero.
            "usedMinutes",
            // Whether that verdict came from the bind grace rather than from evidence.
            "bindPending",
            // Age of THIS PROCESS in minutes, against `uptimeMin`'s whole-phone figure — a missing
            // watcher three seconds after a cold start has not died.
            "processAgeMin",
            // Times the re-check found the watcher still gone: the phone killing our process
            // faster than the grace period can wait for it.
            "bindDeferrals",
            // Is blocking stopped **at this moment**. Every other outage key describes the last
            // finished episode, so a report written during a stoppage used to be unrecognisable.
            "outageNow",
            // The latency histogram uncollapsed, fastest bucket first ("3/8/40/12/2"). The summary
            // in `blockSpeed` can hold steady while the middle of the distribution drifts right.
            "speedBuckets",
            // Total minutes unprotected, and the worst single stoppage. The two headline numbers
            // about the owner's actual complaint, shown on his screen and never sent until now.
            "outageTotalMin",
            "outageWorstMin",
            // Minutes since the background scheduler last ran — the live gap, against
            // `workerSilent`'s lifetime count. `?` when it has never been seen to run at all.
            "workerSilentMin",
            // ⚠️ The reporter describing its own channel: reports written but not delivered, and
            // how many more may go today. A backlog visible on arrival is the only way a report
            // can say "the ones before me did not get through".
            "reportQueue",
            // --- the owner's configuration, as counts (never rows) ---
            // Gathered from Room, and ONLY for the report shapes that are not on the error path —
            // see BugReportSender.ruleCounts. `limits` is the one that earns its place: "it didn't
            // block X" is the commonest report and "the rule existed, the allowance had not run
            // out" is the commonest innocent answer, which no report could tell from a real fault.
            "ruleBlocked",
            "ruleAllowed",
            "limits",
            "schedules",
            // How many entries the text filter holds. Named for the count, not for what is
            // counted: `wordCount` tripped the allow-list's own tripwire for keys that sound like
            // content, and the right answer to that is a better name, never an exception.
            "filterEntries",
            "healthErrors",
            "lastErrorWhere",
            // Minutes since the last swallowed error, so a count stops meaning the same thing
            // whether it happened months ago or during the thing being reported. Our own integer.
            "lastErrorMin",
            // Minutes since the watcher was last known alive, against `lastEventMin`'s "last time
            // it heard anything". A quiet hour with a fresh heartbeat is a phone nobody is using;
            // a quiet hour with a stale one is the failure. Our own integer, or `never`.
            "aliveMin",
            // Android's persistent boot sequence number — how many times this phone has restarted,
            // not when or why. Reboots are invisible in every other field, and half the outage
            // hypotheses are about what a restart does. `-1` means unreadable (see [DeviceBoot]).
            "boots",
            // Minutes since the phone booted. Separates "the service never started after a
            // reboot" from "it died an hour ago", which look identical in every other field.
            "uptimeMin",
            // The phone's own clock, HH:mm. Half the schedule questions are really "what time was
            // it there?", and the issue's own timestamp is when the report was SENT — which for a
            // queued report can be hours later.
            "localTime",
            // Whether the phone is exempt from battery optimisation. The app asks for this
            // explicitly because OEM battery managers are the usual cause of a dead watcher.
            "batteryFree",
            // Enforcement state at the moment of the report.
            "quickPaused",
            "allowlist",
            // When he pressed Send, in whole seconds. **[dedupeKey] reads this** — it is what makes
            // one send different from the next now that the text box is optional and two blank
            // reports are otherwise identical. Load-bearing, not descriptive; see `outageAt`.
            "noteAt",
            // The calendar week a summary covers, e.g. "2026-W35". **[dedupeKey] reads this** —
            // it is what makes a week's summary arrive once. Load-bearing, like `outageAt`.
            "weekOf",
            // How many weeks in a row produced no summary. A gap means the app was not opened, or
            // nothing was getting through; the number says how big the gap was without claiming
            // which of the two it was.
            "weeksSkipped",
            // Which of the three chips he tapped in the report box, or absent. One of our own
            // literals, never anything he typed — the chips exist so a report can say which of
            // sixty logged covers to look at without him having to describe it.
            "reportKind",
            // --- The outage that just ended (see [OutageLog]) ---
            // When the episode began, in whole seconds. **[dedupeKey] reads this**, which makes it
            // the one key here that is load-bearing rather than descriptive: without it every
            // outage keys as `outage:`, the queue calls the second one a duplicate of the first,
            // and the app can report exactly one stoppage for the life of the install. It was
            // missing from this list for three releases, and no outage report has ever arrived to
            // contradict that. `two stoppages are two reports` is the test.
            "outageAt",
            // `foundDead` above counts outages; these describe one. How long blocking was down,
            // and how much of that passed before the app noticed — the second is the protection
            // actually lost, and the two are different questions.
            "outageMin",
            "outageDetectMin",
            // Whether our process outlived the last event it saw. THE discriminator: false means
            // the process was killed and never rebound, true means it was running the whole time
            // and Android stopped delivering to it. Different causes, different fixes.
            "outageDeaf",
            // What had just happened — `update`, `boot` or `nothing`. One of our own three
            // literals, never a package or a version string.
            "outagePreceded",
            // How it stopped — `recovered`, `switched-off` or `paused`. Only the first means
            // blocking came back; without this the other two read as recoveries and flatter the
            // app in the one log written to judge it. Again one of our own literals.
            "outageEnded",
            // How many outages this install has finished, so one report carries the rate as well
            // as the episode.
            "outageCount",
        )

        /**
         * The device-profile keys, allowed the same way but measured with a longer ruler.
         *
         * **Why these get their own set instead of joining the one above.** Everything in
         * [ALLOWED_CONTEXT_KEYS] is a setting or a count, so 24 characters is generous and a long
         * value there is genuine evidence that something unintended got in. These are package
         * names and joined lists — `com.sec.android.app.sbrowser` is 28 characters on its own — so
         * the same cap would silently shred the exact field the report was sent for.
         *
         * The longer cap is safe **for these keys specifically**, and the argument is about where
         * the values come from rather than how they look: every one is assembled in
         * [DeviceProfile] by joining this app's *own constants* — `KNOWN_BROWSERS`, `DeviceVendor`
         * components, enum names — or by resolving an intent built from our own package name.
         * None of them reads a keyword, a URL, an app the owner chose, or anything on screen. A
         * value here cannot be long *because of something the user did*, which is the property
         * the 24-character cap was standing in for.
         */
        /** The [where] tag that marks a report as a device profile rather than a fault. */
        const val PROFILE_WHERE = "device-profile"

        /**
         * The [where] tag that marks a report as a finished outage.
         *
         * A third shape for the same reason the profile needed a second one: blocking stopping is
         * not a fault Android can see. Nothing throws, so [fromThrowable] never fires; the owner
         * has to be looking to file a note. The failure that costs the most was the one least able
         * to report itself.
         */
        const val OUTAGE_WHERE = "outage"

        /**
         * The [where] tag for the weekly health summary.
         *
         * A fourth shape, and the only one that is filed when **nothing is wrong**. Its value is
         * not what it says on any given week — it is that its absence starts to mean something.
         * Every other report shape arrives only when there is trouble, so a quiet tracker and a
         * dead delivery route look exactly alike, which is the trap that cost five days in
         * August 2026: three releases of stoppage detection sent nothing, and nothing could say
         * whether that was good news or a broken pipe.
         */
        const val WEEKLY_WHERE = "weekly"

        val PROFILE_CONTEXT_KEYS = setOf(
            "brand",
            "uninstallHandler",
            "uninstallGuard",
            "keepAlive",
            // Which screen "Grant" actually lands on, on this phone. The setup guide's per-brand
            // menu paths were written from knowledge rather than measurement; this is how phones
            // we will never own report the truth back.
            "accessibilityScreen",
            "browsersKnown",
            "browsersClaimedReadable",
            "browsersClaimUnproven",
        )

        /** Values are short by nature (an id, a boolean, a count); anything long is a sign
         *  something unintended got in, so it is truncated as well as key-filtered. */
        private const val MAX_CONTEXT_VALUE = 24

        /** Long enough for a joined list of package names, and still a ceiling. */
        private const val MAX_PROFILE_VALUE = 240

        /**
         * Drops every key not on [ALLOWED_CONTEXT_KEYS] or [PROFILE_CONTEXT_KEYS] and truncates
         * what remains. The one function standing between "a helpful diagnostic" and "an
         * accidental leak".
         */
        fun sanitizeContext(raw: Map<String, String>): Map<String, String> = raw
            .filterKeys { it in ALLOWED_CONTEXT_KEYS || it in PROFILE_CONTEXT_KEYS }
            .mapValues { (k, v) ->
                val cap = if (k in PROFILE_CONTEXT_KEYS) MAX_PROFILE_VALUE else MAX_CONTEXT_VALUE
                v.replace('\n', ' ').trim().take(cap)
            }

        /**
         * Builds a report from a throwable, taking **only** the class name and our own frames.
         * [t]'s message is never read; see the class KDoc for why that is not an oversight.
         */
        fun fromThrowable(
            where: String,
            t: Throwable,
            appVersion: String,
            flavor: String,
            androidSdk: Int,
            device: String,
            context: Map<String, String> = emptyMap(),
            recentBlocks: List<String> = emptyList(),
            recentOutages: List<String> = emptyList(),
            healthFacts: List<String> = emptyList(),
        ) = BugReport(
            where = where,
            errorClass = t.javaClass.name.substringAfterLast('.'),
            frames = ourFrames(t),
            note = null,
            appVersion = appVersion,
            flavor = flavor,
            androidSdk = androidSdk,
            device = device,
            context = sanitizeContext(context),
            recentBlocks = recentBlocks,
            recentOutages = recentOutages,
            healthFacts = healthFacts,
        )

        /** Builds a report the owner typed himself. */
        fun fromNote(
            note: String,
            appVersion: String,
            flavor: String,
            androidSdk: Int,
            device: String,
            context: Map<String, String> = emptyMap(),
            recentBlocks: List<String> = emptyList(),
            recentOutages: List<String> = emptyList(),
            healthFacts: List<String> = emptyList(),
        ) = BugReport(
            where = "owner",
            errorClass = null,
            frames = emptyList(),
            note = note.trim().take(MAX_NOTE),
            appVersion = appVersion,
            flavor = flavor,
            androidSdk = androidSdk,
            device = device,
            context = sanitizeContext(context),
            recentBlocks = recentBlocks,
            recentOutages = recentOutages,
            healthFacts = healthFacts,
        )

        /**
         * A report about the phone itself, sent **whether or not anything is wrong**.
         *
         * No frames and no recent blocks by construction: there is no failure to locate, and a
         * profile is about the hardware rather than about anything the owner did. That also keeps
         * this the one report shape that carries nothing the user generated at all.
         */
        fun fromProfile(
            appVersion: String,
            flavor: String,
            androidSdk: Int,
            device: String,
            context: Map<String, String>,
            /**
             * The blocker's verdict on itself, as on every other shape.
             *
             * A profile is filed **once per phone per build, on the first open after an update**,
             * which makes it the earliest thing to arrive from a new version and often the only
             * thing to arrive at all when something is wrong. Leaving it as the one report with no
             * "what looks wrong here" made the shape we depend on most the least informative — and
             * it is where the delivery verdicts now live, which is exactly what explains a phone
             * that cannot reach its own server.
             */
            healthFacts: List<String> = emptyList(),
        ) = BugReport(
            where = PROFILE_WHERE,
            errorClass = null,
            frames = emptyList(),
            note = null,
            appVersion = appVersion,
            flavor = flavor,
            androidSdk = androidSdk,
            device = device,
            context = sanitizeContext(context),
            recentBlocks = emptyList(),
            healthFacts = healthFacts,
        )

        /**
         * A report about an outage that has **ended** — see [OutageLog].
         *
         * Keeps the recent blocks, unlike a profile: the entries either side of the gap are the
         * closest thing there is to a witness. No frames, because nothing threw — that is the
         * whole difficulty this shape exists to work around.
         */
        /**
         * The weekly health summary — see [WEEKLY_WHERE].
         *
         * Carries the health lines and the stoppage history like any other report, and no block
         * log: the week's covers are not what this is for, and sixty lines would bury the one
         * fact it exists to deliver.
         */
        fun fromWeekly(
            appVersion: String,
            flavor: String,
            androidSdk: Int,
            device: String,
            context: Map<String, String>,
            recentOutages: List<String> = emptyList(),
            healthFacts: List<String> = emptyList(),
        ) = BugReport(
            where = WEEKLY_WHERE,
            errorClass = null,
            frames = emptyList(),
            note = null,
            appVersion = appVersion,
            flavor = flavor,
            androidSdk = androidSdk,
            device = device,
            context = sanitizeContext(context),
            recentBlocks = emptyList(),
            recentOutages = recentOutages,
            healthFacts = healthFacts,
        )

        fun fromOutage(
            appVersion: String,
            flavor: String,
            androidSdk: Int,
            device: String,
            context: Map<String, String>,
            recentBlocks: List<String>,
            recentOutages: List<String> = emptyList(),
            healthFacts: List<String> = emptyList(),
        ) = BugReport(
            where = OUTAGE_WHERE,
            errorClass = null,
            frames = emptyList(),
            note = null,
            appVersion = appVersion,
            flavor = flavor,
            androidSdk = androidSdk,
            device = device,
            context = sanitizeContext(context),
            recentBlocks = recentBlocks,
            recentOutages = recentOutages,
            healthFacts = healthFacts,
        )

        /**
         * Our own stack frames, as `Class.method:line`.
         *
         * Walks the cause chain because the useful frame is often in the cause, but reads only
         * class/method/line — never a message, at any depth.
         */
        internal fun ourFrames(t: Throwable): List<String> {
            val out = mutableListOf<String>()
            var current: Throwable? = t
            var depth = 0
            while (current != null && depth < 5 && out.size < MAX_FRAMES) {
                current.stackTrace.forEach { f ->
                    if (out.size < MAX_FRAMES && f.className.startsWith(OUR_PACKAGE)) {
                        out += "${f.className.substringAfterLast('.')}.${f.methodName}:${f.lineNumber}"
                    }
                }
                current = current.cause?.takeIf { it !== current }
                depth++
            }
            return out
        }
    }
}
