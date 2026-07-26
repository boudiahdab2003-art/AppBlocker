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
    private const val MAX = 15

    /**
     * The only values [kind] may take. A fixed vocabulary rather than free text, for the same
     * reason [BugReport.ALLOWED_CONTEXT_KEYS] is a list: the call site is deep in the watcher
     * where a package name is the nearest variable to hand, and anything unrecognised here is
     * recorded as "other" rather than passed through.
     */
    val KINDS = setOf("app", "guard", "shorts", "purchase", "word", "other")

    data class Entry(
        val agoMs: Long,
        val kind: String,
        val ownUi: Boolean,
        val rootOk: Boolean,
        val counted: Boolean,
    ) {
        /** One line for the report body. Fixed tokens only — nothing here can carry content. */
        fun render(): String =
            "${agoMs / 1000}s ago  $kind  ownUi=$ownUi  rootOk=$rootOk  counted=$counted"
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
        rootOk: Boolean,
        counted: Boolean,
        now: Long = System.currentTimeMillis(),
    ) {
        runCatching {
            val safeKind = if (kind in KINDS) kind else "other"
            val line = "$now|$safeKind|$ownUi|$rootOk|$counted"
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

    internal fun decode(raw: String, now: Long): Entry? {
        val p = raw.split('|')
        if (p.size != 5) return null
        val at = p[0].toLongOrNull() ?: return null
        return Entry(
            // Coerced: a clock change must not render "-4000s ago", and this is only ever read
            // by a human comparing entries to each other.
            agoMs = (now - at).coerceAtLeast(0L),
            kind = if (p[1] in KINDS) p[1] else "other",
            ownUi = p[2].toBoolean(),
            rootOk = p[3].toBoolean(),
            counted = p[4].toBoolean(),
        )
    }
}
