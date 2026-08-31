package com.appblocker.data

/**
 * The last few block screens the app raised, described by their **shape** rather than their
 * subject — so "why did it block?" can be answered in a bug report without sending what it
 * blocked.
 *
 * **Why this exists.** The owner reported block screens flashing over unblocked apps and over
 * AppBlocker's own UI. Reading the code found one cause (the off-switch guard) that could not
 * explain either symptom, and no way to find the other: the decisions happen in a service with no
 * test coverage, on a device neither of us can inspect, and they are gone in milliseconds. His
 * suggestion — put the recent blocks in the report — is the right instrument.
 *
 * **What it deliberately does not hold.** Not the app, not the package, not the keyword, not the
 * URL, not the cover's title or message. Every one of those is the *subject* of a block, and the
 * subject is exactly what [BugReport] exists to keep on the device. What is here is:
 *
 *  - [kind] — which code path raised the cover, from a fixed vocabulary
 *  - `ownUi` — was AppBlocker's own UI in front at that moment? A `true` here is the smoking gun
 *    for "it flashed inside the app": nothing should ever cover our own screens.
 *  - `rootOk` — did the window actually on screen match the app being blocked? A `false` means
 *    the cover was raised from a stale cache, which is the classic cause of a cover landing on the
 *    wrong app.
 *  - `counted` — did it record an attempt? Separates a real new block from a redraw.
 *
 * Those four answer "was this block legitimate?" without naming what it was about. A stale-cache
 * flash over an unblocked app looks like `app ownUi=false rootOk=false`, and a flash inside our
 * own UI looks like `ownUi=true` — neither of which requires knowing which app it was.
 */
object BlockLog {
    private const val PREFS = "appblocker_prefs"
    private const val KEY = "block_log"

    /** Short: this is a window into "what just happened", not an audit trail. */
    /** How many covers are kept. Raised from 15 on 26 Aug 2026 at the owner's request — fifteen
     *  entries is a couple of minutes on a busy evening, and the log is read *backwards from the
     *  complaint*, so the entries that explain a report are routinely the ones that had already
     *  fallen off the end. Each record is a handful of bytes and carries no subject, so length is
     *  nearly free; what it buys is the difference between "the block you mean isn't in here" and
     *  an answer. */
    private const val MAX = 60

    /**
     * The only values [kind] may take. A fixed vocabulary rather than free text, for the same
     * reason [BugReport.ALLOWED_CONTEXT_KEYS] is a list: the call site is deep in the watcher
     * where a package name is the nearest variable to hand, and anything unrecognised here is
     * recorded as "other" rather than passed through.
     */
    val KINDS = setOf("app", "guard", "shorts", "purchase", "word", "other")

    /**
     * What the window on screen was, at the moment the cover went up.
     *
     * **This replaces a boolean that meant two opposite things.** `rootOk=false` was documented as
     * "raised from a stale cache" — a bug — but it was computed as `root?.packageName == pkg`, so
     * it was equally false when the tree simply could not be read, which the watcher treats as a
     * legitimate reason to block (invariant 1) and which happens routinely mid-transition. Report
     * #5 turned on exactly that distinction and the log could not answer it: two `rootOk=false`
     * entries that might have been the bug, or might have been nothing at all.
     */
    object Window {
        /** The window on screen was the app being blocked. Correct. */
        const val MATCH = "match"

        /** A *different* app was on screen — the cover landed on the wrong thing. The bug. */
        const val OTHER = "other"

        /** The tree could not be read. Blocking anyway is deliberate; this is not a fault. */
        const val BLIND = "blind"

        /** No package to compare against (a word/guard cover isn't about one app). */
        const val NA = "n/a"

        val ALL = setOf(MATCH, OTHER, BLIND, NA)
    }

    data class Entry(
        val agoMs: Long,
        val kind: String,
        val ownUi: Boolean,
        val window: String,
        /** A `BlockWhy` code: which rule fired. "?" for entries written before this was recorded. */
        val why: String,
        val counted: Boolean,
    ) {
        /**
         * One line for the report body. Fixed tokens only — nothing here can carry content.
         *
         * The age used to be printed in raw seconds, so a real report carried `121808s ago` and
         * reading it meant dividing by 3600 to discover the entry was from yesterday — sixty times
         * per report, on the log whose whole purpose is finding the one block the owner means.
         * Padded to a fixed width so the columns still line up when the units differ.
         */
        fun render(): String =
            ago().padEnd(9) + " $kind  why=$why  window=$window  ownUi=$ownUi  counted=$counted"

        /** `4s`, `7m`, `2h`, `3d` — the largest unit that still says something useful. */
        private fun ago(): String {
            val s = agoMs / 1000
            return when {
                s < 60 -> "${s}s ago"
                s < 3_600 -> "${s / 60}m ago"
                s < 86_400 -> "${s / 3_600}h ago"
                else -> "${s / 86_400}d ago"
            }
        }
    }

    private fun prefs(context: android.content.Context) =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    /**
     * Records one raised cover. Best-effort and silent: this is a diagnostic, and a diagnostic
     * that can throw inside the block path would be worse than no diagnostic at all.
     */
    fun record(
        context: android.content.Context,
        kind: String,
        ownUi: Boolean,
        window: String,
        why: String,
        counted: Boolean,
        now: Long = System.currentTimeMillis(),
    ) {
        runCatching {
            val safeKind = if (kind in KINDS) kind else "other"
            val safeWindow = if (window in Window.ALL) window else Window.NA
            // `why` arrives from BlockWhy, which lives in the service module; anything else is
            // recorded as unknown rather than passed through, exactly as `kind` is. The delimiter
            // is stripped for the same reason: a code that could carry a `|` could carry content.
            val safeWhy = why.replace('|', '-').replace(';', '-').take(16).ifBlank { "?" }
            val line = "$now|$safeKind|$ownUi|$safeWindow|$safeWhy|$counted"
            val existing = prefs(context).getString(KEY, "").orEmpty()
                .split(';').filter { it.isNotBlank() }
            val trimmed = (existing + line).takeLast(MAX)
            prefs(context).edit().putString(KEY, trimmed.joinToString(";")).apply()
        }
    }

    /** The log, newest first, as report-ready lines. */
    fun recent(context: android.content.Context, now: Long = System.currentTimeMillis()):
        List<String> = runCatching {
        prefs(context).getString(KEY, "").orEmpty()
            .split(';').filter { it.isNotBlank() }
            .mapNotNull { decode(it, now) }
            .sortedBy { it.agoMs }
            .map { it.render() }
    }.getOrDefault(emptyList())

    /**
     * Reads both the 6-field format and the 5-field one that preceded it.
     *
     * **Old entries are not dropped.** The log survives an app update, so the report sent right
     * after one contains entries from the version before — and those are the ones covering the
     * minutes the user is usually reporting about. Discarding them would blank the log exactly
     * when it is most likely to be read. The old boolean's `false` is decoded as [Window.BLIND]
     * rather than [Window.OTHER], because it genuinely meant "either of those" and guessing the
     * accusing one would invent a bug that may not have happened.
     */
    internal fun decode(raw: String, now: Long): Entry? {
        val p = raw.split('|')
        if (p.size != 6 && p.size != 5) return null
        val at = p[0].toLongOrNull() ?: return null
        val legacy = p.size == 5
        return Entry(
            // Coerced: a clock change must not render "-4000s ago", and this is only ever read
            // by a human comparing entries to each other.
            agoMs = (now - at).coerceAtLeast(0L),
            kind = if (p[1] in KINDS) p[1] else "other",
            ownUi = p[2].toBoolean(),
            window = if (legacy) {
                if (p[3].toBoolean()) Window.MATCH else Window.BLIND
            } else {
                if (p[3] in Window.ALL) p[3] else Window.NA
            },
            why = if (legacy) "?" else p[4].ifBlank { "?" },
            counted = p.last().toBoolean(),
        )
    }
}
