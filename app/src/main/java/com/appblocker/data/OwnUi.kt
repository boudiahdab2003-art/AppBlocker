package com.appblocker.data

/**
 * Whether AppBlocker's own UI is the thing in front, published by MainActivity's lifecycle.
 *
 * The blocking watcher cannot work this out for itself. Our block cover and our activity are
 * the same package, so an accessibility event — or `rootInActiveWindow` — reporting "us"
 * is ambiguous: it means either "the cover is up, the blocked app is still behind it" or "the
 * user has walked into AppBlocker's own screens". The watcher assumed the former, which let a
 * cover be raised over our own settings screens, attributed to whichever app happened to be
 * open beforehand (the watcher never updates its foreground cache for our own package, so it
 * stays pointing at that app).
 *
 * The activity is the only place that knows, hence this flag. Read from the service's main
 * thread and written from the main thread, but volatile so the background scans can read it too.
 */
object OwnUi {
    @Volatile
    var visible: Boolean = false

    /**
     * When one of AppBlocker's own screens last came to the front, on the monotonic clock — 0 when
     * none has in this process.
     *
     * The second thing only the activities know, and the one the stoppage log needs: a watcher that
     * Android binds again seconds after our own screen came up did not come back on its own. On
     * 14 Sep 2026 a six-hour stoppage ended the moment the owner opened the app and was filed as
     * Android recovering alone (invariant 74). Set by [com.appblocker.MainActivity] and
     * [com.appblocker.ui.RestoreActivity].
     */
    @Volatile
    var resumedAtRt: Long = 0L

    /** Whether an own screen came to the front within [windowMs] before [nowRt]. Pure: the stamp is
     *  a parameter, so the rule is tested without a phone. */
    fun openedWithin(nowRt: Long, windowMs: Long, resumedAt: Long = resumedAtRt): Boolean =
        resumedAt > 0L && nowRt - resumedAt in 0..windowMs
}
