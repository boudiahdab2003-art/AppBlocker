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
 * it**. [toggleDeviceAdmin] stamps this immediately before starting the intent, so the guard can
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
