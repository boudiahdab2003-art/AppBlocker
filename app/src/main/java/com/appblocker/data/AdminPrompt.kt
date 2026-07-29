package com.appblocker.data

import android.os.SystemClock

/**
 * "We just asked Android to show the device-admin activation screen."
 *
 * The off-switch guard bounces pages that mention AppBlocker beside words like *device admin* and
 * *uninstall*. Android's **activation** prompt says both — its title is "Activate device admin
 * app?" and it carries an "Uninstall app" button — so the guard blocked the one screen the owner
 * must pass through to *enable* uninstall protection. Protection that cannot be switched on is
 * worse than none, because the app reports itself as guarded either way.
 *
 * Word-matching cannot fix this reliably: "deactivate" contains "activate", the wording differs by
 * OEM, and it is translated. But we know something the screen text cannot tell us — **we opened
 * it**. `enableDeviceAdmin` stamps this immediately before starting the intent, so the guard can
 * stand down for the moment that follows, in any language, on any build.
 *
 * Monotonic, per the rule this app has had to relearn three times (see `stopwatchNow` and
 * [SessionClock]): a wall-clock stamp here would let a clock change either strand the exemption
 * open or close it early.
 */
object AdminPrompt {

    /**
     * Long enough for the system screen to appear and be read — it has a paragraph of text and
     * three buttons — and short enough that it cannot become a way to reach the *deactivation*
     * screen afterwards.
     */
    const val WINDOW_MS = 60_000L

    @Volatile private var requestedAtRt: Long = 0L

    /** Called immediately before launching ACTION_ADD_DEVICE_ADMIN. */
    fun requested() {
        requestedAtRt = SystemClock.elapsedRealtime()
    }

    /** True while the activation screen we asked for may still be in front. */
    fun recentlyRequested(): Boolean {
        val at = requestedAtRt
        if (at == 0L) return false
        val elapsed = SystemClock.elapsedRealtime() - at
        // Negative means the monotonic clock restarted (a reboot): treat as expired, never open.
        return elapsed in 0..WINDOW_MS
    }

    /** Closes the window early — used once the admin state has actually changed. */
    fun clear() {
        requestedAtRt = 0L
    }
}

/**
 * "We just asked Android to install our own update."
 *
 * The same problem as [AdminPrompt], found the same way — by the owner hitting it. The guard
 * bounces the system installer when the screen names AppBlocker, because that is how it catches
 * "do you want to uninstall this app?". But **installing an update uses the same installer and
 * names the same app**, so the update confirmation was bounced too: the in-app updater downloaded
 * a new version, opened the installer, and the guard threw the owner back to the home screen.
 *
 * That is worse than the device-admin case it rhymes with. A guard that blocks its own updates
 * blocks the update that would fix it — including this one, which is why the release notes have to
 * tell him how to get past it once by hand.
 *
 * Reading the screen cannot separate install from uninstall: both say the app's name, both are
 * translated, and "install" is a substring of "uninstall" in English and unrelated in Arabic. But
 * we know what the screen text cannot say — **we opened it** — and the updater is the only thing
 * in this app that ever launches an install.
 *
 * Longer window than the admin prompt: this screen is followed by a real installation, which on a
 * slow phone takes a while, and the "app installed / open" screen that follows is the same
 * activity. Still bounded, so it cannot become a standing exemption.
 */
object InstallPrompt {

    const val WINDOW_MS = 5 * 60_000L

    @Volatile private var requestedAtRt: Long = 0L

    /** Called immediately before launching the package-installer intent. */
    fun requested() {
        requestedAtRt = SystemClock.elapsedRealtime()
    }

    /** True while the installer we asked for may still be in front. */
    fun recentlyRequested(): Boolean {
        val at = requestedAtRt
        if (at == 0L) return false
        val elapsed = SystemClock.elapsedRealtime() - at
        // Negative means the monotonic clock restarted (a reboot): treat as expired, never open.
        return elapsed in 0..WINDOW_MS
    }
}

/**
 * Recognising the device-admin screen by its wording, for the builds where its *name* says nothing.
 *
 * The watcher's fast path is the activity's class name (`deviceadmin`), which works on AOSP. On
 * MIUI the same screen arrives as a generic `SubSettings`, and until now the only thing catching
 * it there was Strict Mode's broad rule — "any Settings page that mentions us next to a marker
 * word". That rule bounced the whole Accessibility section and every app's App-info page as
 * collateral, so it has been deleted; this is what replaces it for the one screen that needed it.
 *
 * **The list is deliberately short.** Its predecessor also held `accessibilit`, `uninstall` and
 * `force stop` — and those are exactly the words that made it over-block: the Accessibility list
 * names every service (so it mentions us) *and* says "accessibility"; an App-info page says
 * "uninstall" and "force stop" about whichever app you opened. Device-admin wording appears on the
 * device-admin screens and essentially nowhere else in Settings, which is why it can be trusted
 * where the others could not. Anything added here must clear that same bar.
 *
 * Lives outside the watcher so it can be tested: the watcher has no test coverage and cannot have
 * any as written, and this list is precisely the kind of thing that silently stops matching.
 */
object AdminScreens {

    /**
     * Lowercased, and Arabic already folded the way `WebContentFilter.normalizeArabic` folds it —
     * the caller passes text through that first, so alef/hamza spellings and diacritics match.
     *
     * Arabic sits alongside English because this match fails **silently**: nothing errors, the page
     * simply isn't recognised and the guard doesn't bounce. On a phone whose Settings are not in
     * English the whole fallback would be dead, and under-blocking is invisible to the owner (see
     * docs/BLOCKING_INVARIANTS.md).
     */
    val MARKERS = listOf(
        "device admin", "device administrator", "deactivate",
        // مسؤول الجهاز (device admin) — two spellings of the hamza — and تعطيل (deactivate).
        "مسئول الجهاز", "مسءول الجهاز", "تعطيل",
    )

    /** Whether [text] (already lowercased and Arabic-folded) reads like a device-admin screen. */
    fun looksLikeAdminScreen(text: String): Boolean = MARKERS.any { text.contains(it) }
}
