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
 * package names, the owner's name, location, anything from the coach conversation.
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
        note != null -> "note:${note.hashCode()}"
        else -> "$where|$errorClass|${frames.firstOrNull().orEmpty()}"
    }

    /** The issue title. Short, and safe by construction — no field here can hold user text. */
    fun title(): String = when {
        note != null -> "Report: " + note.lineSequence().first().take(60).trim()
        else -> "$errorClass in $where"
    }

    /** The issue body, in GitHub markdown. */
    fun body(): String = buildString {
        if (note != null) {
            appendLine("**What happened, in the owner's words:**")
            appendLine()
            appendLine(note)
            appendLine()
        }
        appendLine("| | |")
        appendLine("|---|---|")
        appendLine("| Where | `$where` |")
        if (errorClass != null) appendLine("| Error | `$errorClass` |")
        appendLine("| Version | $appVersion ($flavor) |")
        appendLine("| Android | SDK $androidSdk |")
        appendLine("| Device | $device |")
        context.toSortedMap().forEach { (k, v) -> appendLine("| $k | $v |") }
        if (recentBlocks.isNotEmpty()) {
            appendLine()
            appendLine("**Recent blocks** (newest first — `ownUi=true` or `rootOk=false` means a")
            appendLine("cover landed somewhere it should not have):")
            appendLine()
            appendLine("```")
            recentBlocks.forEach { appendLine(it) }
            appendLine("```")
        }
        if (frames.isNotEmpty()) {
            appendLine()
            appendLine("```")
            frames.forEach { appendLine(it) }
            appendLine("```")
        }
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
            "layout",
            "theme",
            "serviceOn",
            "protection",
            "guard",
            "blocksToday",
            "adultPack",
            "scanEverywhere",
            "overlayPermission",
            "usageAccess",
        )

        /** Values are short by nature (an id, a boolean, a count); anything long is a sign
         *  something unintended got in, so it is truncated as well as key-filtered. */
        private const val MAX_CONTEXT_VALUE = 24

        /**
         * Drops every key not on [ALLOWED_CONTEXT_KEYS] and truncates what remains. The one
         * function standing between "a helpful diagnostic" and "an accidental leak".
         */
        fun sanitizeContext(raw: Map<String, String>): Map<String, String> = raw
            .filterKeys { it in ALLOWED_CONTEXT_KEYS }
            .mapValues { (_, v) -> v.replace('\n', ' ').trim().take(MAX_CONTEXT_VALUE) }

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
