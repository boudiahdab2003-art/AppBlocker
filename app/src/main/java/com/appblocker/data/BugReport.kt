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
) {

    /**
     * Groups reports that are "the same bug" so a crash loop opens one issue rather than hundreds.
     *
     * Deliberately excludes [note] and every varying field: two crashes at the same place with the
     * same exception are one bug even though their timestamps differ. Hand-written reports get a
     * unique key from their note so the owner can always file the same complaint twice — if he
     * bothered to type it, he means it.
     */
    fun dedupeKey(): String = when {
        // One profile per phone per build. Not per launch: the point is one report per *thing we
        // might have got wrong*, and that only changes when the phone or our guesses do.
        isProfile -> "profile:$device|$appVersion"
        // One per episode, keyed on when it started. Deliberately NOT collapsed the way faults
        // are: two outages are two outages even when they look identical, and the rate is the
        // measurement being taken. The start stamp is what makes them distinguishable at all.
        isOutage -> "outage:${context["outageAt"].orEmpty()}"
        note != null -> "note:${note.hashCode()}"
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
        isOutage -> "[$appVersion] STOPPED for ${context["outageMin"] ?: "?"} min" +
            " — after ${context["outagePreceded"] ?: "?"}" +
            if (context["outageDeaf"] == "true") ", still running" else ", process died"
        note != null -> "[$appVersion] " + note.lineSequence().first().take(60).trim()
        else -> "[$appVersion] $errorClass in $where"
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
    fun body(): String = when {
        isProfile -> profileBody()
        isOutage -> outageBody()
        else -> faultBody()
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
        if (note != null) {
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
            "healthErrors",
            "lastErrorWhere",
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
            // --- The outage that just ended (see [OutageLog]) ---
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
        )

        /**
         * A report about an outage that has **ended** — see [OutageLog].
         *
         * Keeps the recent blocks, unlike a profile: the entries either side of the gap are the
         * closest thing there is to a witness. No frames, because nothing threw — that is the
         * whole difficulty this shape exists to work around.
         */
        fun fromOutage(
            appVersion: String,
            flavor: String,
            androidSdk: Int,
            device: String,
            context: Map<String, String>,
            recentBlocks: List<String>,
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
