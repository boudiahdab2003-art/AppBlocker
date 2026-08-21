package com.appblocker.data

import android.content.Context
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

/*
 * **[AdminPrompt] stays in memory on purpose, and [InstallPrompt] does not.** They read as twins,
 * so the asymmetry has to be written down or it gets tidied away: activating device admin does not
 * replace our APK, so our process is still the one that stamped the request when the guard asks.
 * An install *is* the process ending — that is the whole difference, and it is why only one of
 * these two needed to reach the disk. Making both persistent would widen a standing exemption to
 * no purpose.
 */

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

    private const val PREFS = "appblocker_prefs"
    private const val KEY_AT = "install_prompt_at"
    private const val KEY_BOOT = "install_prompt_boot"

    /** A fast path only. The answer of record is on disk — see [requested]. */
    @Volatile private var requestedAtRt: Long = 0L

    /**
     * Called immediately before launching the package-installer intent.
     *
     * **Written to disk, because the process holding it is about to be killed.** This lived in the
     * `@Volatile` above and nowhere else, and that is precisely the screen it fails on: installing
     * our own APK makes Android kill our process, the accessibility service comes back a moment
     * later with the field reset to 0, and the "app installed / Open" screen still in front is
     * read as an uninstall confirmation. Reported 21 Aug 2026 — *"after the update I got a
     * blocking screen idk why"* — and the KDoc above had already named that screen as the one the
     * five-minute window exists to cover. A window is worth nothing when the clock holding it is
     * reset at minute zero.
     *
     * **`commit()`, not `apply()`**, for the reason `SettingsStore.setAutoInstalled` spells out:
     * `apply()` writes to memory now and to disk on a background thread, and here the process is
     * about to die by design. This is the one place in the app where that argument is not
     * theoretical — the thing being defended against is the process ending.
     *
     * The boot count goes with it so the window stays monotonic across the restart without
     * becoming a standing exemption: a stamp from before a reboot is expired, exactly as the
     * negative-elapsed check used to say. Same rule as [SessionClock], and it is deliberately the
     * same rule rather than a second opinion.
     */
    fun requested(context: Context) {
        val now = SystemClock.elapsedRealtime()
        requestedAtRt = now
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_AT, now)
                .putInt(KEY_BOOT, DeviceBoot.count(context))
                .commit()
        }
    }

    /** True while the installer we asked for may still be in front — across the process death the
     *  install itself causes. */
    fun recentlyRequested(context: Context): Boolean {
        val prefs = runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }.getOrNull()
        // An unreadable prefs file falls back to this process's own memory rather than answering
        // "not ours": within one process that is the same answer it always gave.
        val savedAt = prefs?.getLong(KEY_AT, 0L) ?: requestedAtRt
        val savedBoot = prefs?.getInt(KEY_BOOT, -1) ?: DeviceBoot.count(context)
        return openAt(savedAt, savedBoot, DeviceBoot.count(context), SystemClock.elapsedRealtime())
    }

    /**
     * Whether the exemption is open, given what was stored and what the clock says now.
     *
     * Split out with the clock and the boot count as parameters for the same reason
     * [SessionClock.remainingAt] is: this app has no Robolectric, so the only decisions that can be
     * tested are the ones that take their world as arguments. Every way of not knowing answers
     * false — an unset stamp, an unknown boot count on either side, a different boot, a clock that
     * has gone backwards. Closed is the guarding direction.
     */
    internal fun openAt(savedAtRt: Long, savedBoot: Int, currentBoot: Int, nowRt: Long): Boolean {
        if (savedAtRt <= 0L) return false
        if (savedBoot < 0 || currentBoot < 0 || savedBoot != currentBoot) return false
        return (nowRt - savedAtRt) in 0..WINDOW_MS
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
