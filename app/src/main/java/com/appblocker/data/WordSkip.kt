package com.appblocker.data

/**
 * **The apps "check every app" leaves out** (invariant 87) — his choice of 10 Oct 2026: "we don't
 * need to check for words in YouTube and Claude and WhatsApp", asked for battery. Reading an app's
 * screen for blocked words is the heaviest thing the watcher does, and in those apps it was doing
 * it for nothing he wanted.
 *
 * What the list never reaches:
 *  - **Browsers.** The site layer, the adult list and the blocked apps' websites all live in the
 *    browser scan; skipping a browser would switch off far more than words. The picker does not
 *    offer them, and this answers no for them anyway in case one is on the list from before it was
 *    a browser.
 *  - **The danger hour.** Its whole point is that every app is read while browsers are shut;
 *    an hour that skipped the apps he named would be watching less exactly when it should watch more.
 *  - **YouTube Shorts.** That is a different scan (`scheduleShortsScan`), not a word scan, so
 *    skipping YouTube for words leaves Shorts blocked.
 *
 * Plain values in, decision out: the watcher keeps the live list.
 */
object WordSkip {

    /**
     * **Apps never read for words, list or no list, Strict or not** (invariant 94). His choice on
     * 25 Oct 2026: a psychology podcast about fetishes on Spotify kept being covered, and a Strict
     * session refused Spotify on his own list. He chose "only Spotify, always" over loosening
     * Strict for every app, so this is a named exception rather than a hole in the Strict rule:
     * [allowed] still refuses additions mid-session.
     *
     * ⚠️ **The danger hour still reads them**, like everything else — that hour exists to read every
     * app, and he did not ask to change it. Browsers are never in here, and [skips] says no for a
     * browser anyway.
     */
    val ALWAYS: Set<String> = setOf("com.spotify.music", "com.spotify.lite")

    fun skips(pkg: String, skipList: Set<String>, isBrowser: Boolean, dangerZoneOn: Boolean): Boolean =
        !isBrowser && !dangerZoneOn && (pkg in skipList || pkg in ALWAYS)

    /**
     * What may actually be stored. Skipping an app is a weakening, so during a Strict session no app
     * can be added; taking one off the list makes blocking stronger and always goes through
     * (invariant 16: refusing to let him arm a protection is never the safe direction).
     */
    fun allowed(current: Set<String>, target: Set<String>, strict: Boolean): Set<String> =
        if (strict) target intersect current else target
}
