package com.appblocker.service

/**
 * The timing rules around the block cover: how long a just-dismissed cover stays suppressed,
 * and when raising a cover counts as a new attempt.
 *
 * These used to be private methods reading service fields directly, which made them
 * untestable — and they were guessed at twice (the "shows twice" fixes in v1.91 and this one)
 * with no test to state what they promise. Extracted here as plain values in / decision out,
 * the same shape as [decideBlock], [com.appblocker.data.SessionClock] and
 * [scheduleWindowContains]. The service keeps owning the live state; this only decides.
 */
internal object CoverGate {

    /** The counter key the YouTube-Shorts covers use — exempt from dismiss suppression. */
    const val SHORTS_KEY = "shorts"

    /** Grace after "Got it" during which the same block must not be raised again — the event
     *  stragglers emitted while the phone is on its way Home. Kept short on purpose: it is also
     *  a window in which a deliberate re-open would go uncovered, and the cover is now held up
     *  until the user is really out, which is what protects the transition itself. */
    const val DISMISS_GRACE_MS = 1_500L

    /** Longer grace while the dismissed app is *still* what's on screen — Home can land slowly
     *  or be swallowed entirely (HyperOS, split-screen), and the app is genuinely still there. */
    const val DISMISS_GRACE_STUCK_MS = 8_000L

    /** How long the same target's attempt count stays deduplicated. One open of a blocked app
     *  is one attempt, however many times the cover has to be drawn to keep the user out. */
    const val COUNT_COOLDOWN_MS = 15_000L

    /**
     * Whether raising a cover for [counterKey] should be suppressed because a cover was just
     * dismissed. [sinceDismissMs] is the age of that dismissal; [dismissedKey] and
     * [dismissedPkg] are what it was showing and the app that was behind it; [currentPkg] is
     * the foreground app now.
     *
     * Only the dismissed cover's own key/package is suppressed — a *different* app opened
     * right after a dismissal still blocks instantly. Shorts covers are exempt: their scan
     * tracks whether it is covering in its own flag, and suppressing a show here would desync
     * that and leave Shorts uncovered.
     */
    fun suppressed(
        counterKey: String,
        dismissedKey: String?,
        dismissedPkg: String?,
        currentPkg: String?,
        sinceDismissMs: Long,
    ): Boolean {
        if (counterKey == SHORTS_KEY) return false
        if (dismissedKey == null) return false
        if (counterKey != dismissedKey && counterKey != dismissedPkg) return false
        return inGrace(dismissedPkg, currentPkg, sinceDismissMs)
    }

    /**
     * The timing half of [suppressed], without the key matching: [DISMISS_GRACE_MS] after any
     * dismissal, extended to [DISMISS_GRACE_STUCK_MS] while [currentPkg] is still the app the
     * cover was over. The web scan uses this directly — any dismissal suppresses it, because
     * the page stays on screen for the trip Home whatever the cover was keyed to.
     */
    fun inGrace(dismissedPkg: String?, currentPkg: String?, sinceDismissMs: Long): Boolean {
        if (sinceDismissMs < DISMISS_GRACE_MS) return true
        return sinceDismissMs < DISMISS_GRACE_STUCK_MS && currentPkg != null &&
            currentPkg == dismissedPkg
    }

    /**
     * Whether raising a cover for [counterKey] should record a new attempt. False while the
     * same key is still inside [COUNT_COOLDOWN_MS], so a cover that has to be redrawn — the
     * app re-blocking because Home never landed, a stray event, a mid-use re-check — doesn't
     * inflate the count (and with it the cover's "minutes reclaimed" and the Insights totals).
     *
     * [lastCountedKey] is the key counted most recently and [sinceLastCountMs] how long ago;
     * a different key always counts, since that is a different target being blocked.
     */
    fun shouldCount(
        counterKey: String,
        lastCountedKey: String?,
        sinceLastCountMs: Long,
    ): Boolean = counterKey != lastCountedKey || sinceLastCountMs >= COUNT_COOLDOWN_MS
}
