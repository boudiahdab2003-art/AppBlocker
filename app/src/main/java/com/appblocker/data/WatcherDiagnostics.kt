package com.appblocker.data

import android.content.Context

/**
 * **What the watcher actually saw, last time it looked at a browser.**
 *
 * This exists because of a question that took three wrong answers to stop guessing at: *"why
 * isn't Instagram blocked on Brave?"* Every layer involved is invisible from the outside. Whether
 * a package counts as a browser, whether the address bar could be read, what the address said,
 * which site words were live at that moment — none of it appears anywhere, and the symptom of all
 * four failing is identical: **nothing happens.** Under-blocking has no screen.
 *
 * That is the standing complaint in `docs/BLOCKING_INVARIANTS.md` — the owner notices a block that
 * shouldn't be there and never one that failed to appear — and until now the only way to answer it
 * was to reason from the source and ship a guess. This records the answer instead.
 *
 * Deliberately **not** a log. One record, overwritten, holding the last browser look: a log would
 * need a size policy, a privacy policy and a screen to page through it, and the question is always
 * about the thing that just happened. Addresses are the one genuinely private thing here, so the
 * record holds only the **host** ([hostOf]) and it is cleared whenever browser scanning is not
 * running — this is a diagnostic, not a history.
 *
 * Shares `appblocker_prefs` with [SettingsStore]. Written from the service's scan path, which is
 * already off the main thread, and throttled to one write a second: a scrolling page fires content
 * events continuously and this must not become a reason the scan costs more than it did.
 */
object WatcherDiagnostics {
    private const val PREFS = "appblocker_prefs"
    private const val KEY_AT = "diag_at"
    private const val KEY_PKG = "diag_pkg"
    private const val KEY_IS_BROWSER = "diag_is_browser"
    private const val KEY_HOST = "diag_host"
    private const val KEY_SITE_WORDS = "diag_site_words"

    private const val WRITE_THROTTLE_MS = 1_000L

    @Volatile private var lastWrittenAt = 0L

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * One look at one app.
     *
     * [host] is null when the address bar could not be read — **the distinction the whole record
     * exists for.** "Browser, but no address" is the shape of a browser whose toolbar the app
     * cannot see, and it is exactly what a working Chrome and a silent Brave differ by.
     */
    fun record(
        context: Context,
        pkg: String,
        isBrowser: Boolean,
        host: String?,
        siteWords: List<String>,
        /** False for the once-per-app-switch record, which is not high-frequency and must not be
         *  dropped. It also clears the throttle so the richer record from the scan that follows
         *  a few hundred milliseconds later is not itself suppressed. */
        throttle: Boolean = true,
        now: Long = System.currentTimeMillis(),
    ) {
        if (throttle && now - lastWrittenAt < WRITE_THROTTLE_MS) return
        lastWrittenAt = if (throttle) now else 0L
        runCatching {
            prefs(context).edit()
                .putLong(KEY_AT, now)
                .putString(KEY_PKG, pkg)
                .putBoolean(KEY_IS_BROWSER, isBrowser)
                .putString(KEY_HOST, host)
                .putString(KEY_SITE_WORDS, siteWords.joinToString(", "))
                .apply()
        }
    }

    /** The last look, or null if the watcher has not scanned anything since it was installed. */
    fun last(context: Context): Look? = runCatching {
        val p = prefs(context)
        val at = p.getLong(KEY_AT, 0L)
        val pkg = p.getString(KEY_PKG, null)
        if (at == 0L || pkg == null) return@runCatching null
        Look(
            at = at,
            packageName = pkg,
            isBrowser = p.getBoolean(KEY_IS_BROWSER, false),
            host = p.getString(KEY_HOST, null),
            siteWords = p.getString(KEY_SITE_WORDS, "").orEmpty(),
        )
    }.getOrNull()

    /** One recorded look at one app. */
    data class Look(
        val at: Long,
        val packageName: String,
        /** Whether the watcher counted this package as a browser — the gate in front of
         *  every web-filtering layer there is. */
        val isBrowser: Boolean,
        /** The host from the address bar, or null when it could not be read. */
        val host: String?,
        /** The blocked apps' website words that were live at that moment, comma-separated. */
        val siteWords: String,
    )

    /**
     * The host part of an address, which is all this record keeps.
     *
     * The full URL is the most private thing the watcher ever touches — the path is what says
     * *which* page — and none of it is needed to answer "could the address bar be read, and what
     * site did it say". So the path, query and fragment are dropped before anything is written.
     */
    fun hostOf(url: String?): String? {
        val t = url?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return t.substringAfter("://").substringBefore('/').substringBefore('?')
            .takeIf { it.isNotEmpty() }
    }
}
