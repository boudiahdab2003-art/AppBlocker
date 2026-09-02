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

    /** How long the same target's attempt count stays deduplicated. Only has to absorb event
     *  bursts — a cover redrawn moments later by a stray event, or the second cover one offence
     *  can legitimately raise (see shouldCount) — so it is kept short: past this, opening the
     *  same app again is a genuine second attempt and should be counted as one. */
    const val COUNT_COOLDOWN_MS = 5_000L

    /** The longer window used only when the cover is a known *resume*: "Got it" was tapped, the
     *  trip Home failed, and the app had to be re-covered several seconds later. That is one
     *  sitting, not two attempts, and it cannot be caught by [COUNT_COOLDOWN_MS] — the gap runs
     *  from the first count, so it is however long the user looked at the cover plus the dismiss
     *  grace. Deliberately separate so this padding doesn't apply to ordinary blocks. */
    const val RESUME_GRACE_MS = 15_000L

    /** Where "Got it" should send the user — which depends on what the cover was over. */
    enum class Exit {
        /** Out of the app entirely. The app itself is blocked, so there is nowhere in it to go. */
        HOME,

        /** Off the page, staying in the app. */
        BACK,

        /**
         * Close the surface the cover was over, *then* leave the app — the Shorts exit.
         *
         * A third value rather than "BACK, and also HOME afterwards", because the two are not a
         * sequence of independent moves: the HOME is conditional on the BACK having demonstrably
         * worked. See [ShortsExit], which owns that decision, and its one hard rule — a close that
         * could not be confirmed never presses HOME.
         */
        BACK_THEN_HOME,
    }

    /**
     * The exit a dismissal earns, from what the cover was keyed to.
     *
     * **The exit has to match the scope of what was covered**, and it did not. A page cover
     * (`counterKey == "web"`) covers ONE page inside an app that is not blocked — `scanWebContent`
     * says so where it decides the lockout: *"A blocked WEBSITE is gentler: cover the page so the
     * site stays blocked every visit, but don't lock the whole browser."* No lockout is added for a
     * site hit, so the browser genuinely is not locked. Then "Got it" fired HOME and threw him out
     * of it anyway.
     *
     * Which put him in a loop, and he reported it as one: the blocked site is still the open tab,
     * so re-opening the browser lands straight back on it and covers again. Gentle enforcement,
     * ungentle exit — the distinction v1.132 split `word` from `site` to preserve, given away at
     * the moment of dismissal.
     *
     * An app cover keeps HOME: there the whole app IS blocked, and going back a page inside it
     * would land on another part of the same blocked app.
     *
     * **A Shorts cover is the third scope, and it used to be an exception instead of an answer.**
     * It covers a *surface inside* an app that is not blocked — the reel player inside YouTube,
     * which the owner is otherwise free to use ("The rest of YouTube still works", the cover says
     * so itself). So neither of the other two exits fits: HOME leaves the player open, and BACK
     * alone leaves him sitting in an app he was told to leave. It closes the player, then leaves.
     *
     * ⚠️ And HOME was not merely the wrong scope here, it was the mechanism of a reported bug.
     * HOME fires `onUserLeaveHint()`, which is exactly the signal Android gives an app to hand a
     * playing video to a **picture-in-picture** window — BACK does not. So "Got it" on a Short
     * put that Short in a floating window over the launcher, and the owner reported precisely
     * that: *"the short even when you are in home screen you can watch the short"*. HOME also
     * only backgrounds YouTube, leaving the reel on its back stack, so re-opening resumed onto
     * the same Short. One action, both halves of his complaint.
     *
     * Null is HOME. It is the answer that always works, and an unkeyed cover has nothing to say
     * about what it was over.
     */
    fun exitFor(counterKey: String?): Exit = when (counterKey) {
        WEB_KEY -> Exit.BACK
        SHORTS_KEY -> Exit.BACK_THEN_HOME
        else -> Exit.HOME
    }

    /**
     * How long a page cover's BACK exit is remembered, so a second "Got it" in the same app falls
     * back to HOME.
     *
     * Android will not say whether BACK actually went anywhere — `performGlobalAction` reports that
     * the action was *dispatched*, not that the page moved — and a blocked page opened straight
     * into a fresh tab has no history to step back through. So the failure is detected by it
     * happening twice rather than predicted: one tap goes back, and if the same app is raising
     * another page cover moments later, the next tap leaves.
     *
     * The same order as [DISMISS_GRACE_STUCK_MS] and [RESUME_GRACE_MS], and for the same reason:
     * long enough to cover one sitting, short enough that a genuine block later is a fresh start.
     */
    const val BACK_RETRY_MS = 10_000L

    /**
     * Whether a BACK exit has just been tried in this app and left the user somewhere still worth
     * covering — in which case this dismissal leaves instead.
     *
     * Deliberately bounded to the *same package*: going back out of one app and being blocked in
     * another is two separate situations, and the second deserves its own back.
     */
    fun backExitFailed(pkg: String?, lastBackPkg: String?, sinceLastBackMs: Long): Boolean =
        pkg != null && pkg == lastBackPkg && sinceLastBackMs in 0 until BACK_RETRY_MS

    /** The synthetic key every page cover is raised under — a blocked word, site, or adult page.
     *  Insights renders it as one "Websites" row; here it is what says "this cover was over a page,
     *  not an app". Was a bare string at four call sites in the watcher. */
    const val WEB_KEY = "web"

    /**
     * Whether raising a cover for [counterKey] should be suppressed because a cover was just
     * dismissed. [sinceDismissMs] is the age of that dismissal; [dismissedKey] and
     * [dismissedPkg] are what it was showing and the app that was behind it; [currentPkg] is
     * the foreground app now.
     *
     * Only the dismissed cover's own key/package is suppressed — a *different* app opened
     * right after a dismissal still blocks instantly.
     *
     * **Shorts covers are exempt, and the reason has changed.** The old one — "their scan tracks
     * whether it is covering in its own flag, and suppressing a show would desync that" — has
     * been stale since v1.98, when that flag was deleted and `shortsCovering` became derived from
     * the overlay itself. The reason that holds now is [Exit.BACK_THEN_HOME]: the Shorts exit
     * takes its own cover down at the *start* of the walk, because the player can only be read
     * while nothing of ours is in front of it, and then spends up to
     * [ShortsExit.CLOSE_GIVE_UP_MS] trying to close it. If dismiss suppression applied, a BACK
     * that did not land would leave a Short playing **uncovered** for the whole grace. The
     * exemption is what makes taking the cover down safe, and taking it down is what makes the
     * close verifiable.
     */
    fun suppressed(
        counterKey: String,
        dismissedKey: String?,
        dismissedPkg: String?,
        currentPkg: String?,
        sinceDismissMs: Long,
        /** See [inGrace]. */
        viaBack: Boolean,
    ): Boolean {
        if (counterKey == SHORTS_KEY) return false
        if (dismissedKey == null) return false
        if (counterKey != dismissedKey && counterKey != dismissedPkg) return false
        return inGrace(dismissedPkg, currentPkg, sinceDismissMs, viaBack)
    }

    /**
     * The timing half of [suppressed], without the key matching: [DISMISS_GRACE_MS] after any
     * dismissal, extended to [DISMISS_GRACE_STUCK_MS] while [currentPkg] is still the app the
     * cover was over. The web scan uses this directly — any dismissal suppresses it, because
     * the page stays on screen for the trip Home whatever the cover was keyed to.
     */
    fun inGrace(
        dismissedPkg: String?,
        currentPkg: String?,
        sinceDismissMs: Long,
        /** Whether that dismissal left via BACK rather than HOME — see [Exit] and [exitFor].
         *  Passed rather than derived from the key, because a page cover falls back to HOME when
         *  BACK has already failed once ([backExitFailed]), and then it needs the long window
         *  like any other trip Home. What matters is the move that was actually made. */
        viaBack: Boolean,
    ): Boolean {
        if (sinceDismissMs < DISMISS_GRACE_MS) return true
        // **No extension for a cover dismissed with BACK**, and staying in the app is exactly why.
        //
        // The long window exists for one thing: HOME landing slowly or being swallowed whole
        // (HyperOS, split-screen), leaving the blocked app genuinely still on screen with its
        // stragglers still arriving. BACK does not make that trip. It is a move *inside* the app,
        // it lands at once, and "the same app is still in front" is not a symptom of it — it is
        // the expected outcome of it. So the condition below is true from the first instant of
        // every page dismissal, and the eight seconds ran in full every time.
        //
        // That is invariant 20 read the other way round. The fix there was to release the grace
        // when the destination changes; this is the half that release cannot reach — stepping BACK
        // onto another page of the same site, or onto one whose address cannot be read at all.
        // The short window above still absorbs the departing page's stragglers, which is the only
        // thing this ever had to absorb.
        if (viaBack) return false
        return sinceDismissMs < DISMISS_GRACE_STUCK_MS && currentPkg != null &&
            currentPkg == dismissedPkg
    }

    /**
     * **How much longer [inGrace] will keep saying yes** — 0 once it says no.
     *
     * The service needs this to schedule its own return. A cover declined by the grace used to
     * schedule *nothing*: `showBlockScreen` returns at the decline, and the line that re-arms the
     * 30-second re-check sits at the end of that function. So the grace ended at 1.5s or 8s and
     * nothing looked again for up to another 28, with the blocked app still on screen and static
     * enough to emit no events. That is invariant 20's closing question asked of the grace itself:
     * *what ends it, and what is the user doing if that never happens?*
     *
     * ⚠️ **Derived from [inGrace] rather than re-deciding**, and that is the whole point of it
     * living here. Two sites already computed this by hand — one adding to [DISMISS_GRACE_MS],
     * one subtracting from [DISMISS_GRACE_STUCK_MS] — and each was correct only because of a
     * property of the branch it sat in. A third copy, or any change to the rule above, and they
     * drift apart silently, which reopens the under-block instead of closing it.
     */
    fun graceRemainingMs(
        dismissedPkg: String?,
        currentPkg: String?,
        sinceDismissMs: Long,
        viaBack: Boolean,
    ): Long {
        if (!inGrace(dismissedPkg, currentPkg, sinceDismissMs, viaBack)) return 0L
        // Which window is holding it. The long one only ever applies to a trip Home that has not
        // landed — the same two conditions inGrace tests, in the same order.
        val stuck = !viaBack && currentPkg != null && currentPkg == dismissedPkg
        val endsAt = if (stuck) DISMISS_GRACE_STUCK_MS else DISMISS_GRACE_MS
        return (endsAt - sinceDismissMs).coerceAtLeast(0L)
    }

    /**
     * Whether a confirmed foreground change to [newPkg] means the dismiss grace recorded for
     * [dismissedPkg] has done its job and must be released.
     *
     * The grace exists to absorb the events the *departing* app emits on the way out. Once a
     * different app is genuinely in front, the departing app is off screen and there is nothing
     * left to absorb — so returning to it later has to be watched from the first event rather
     * than treated as still-leaving. Nothing used to release it at all, which is why "Got it"
     * followed by re-opening the browser found both scanners switched off and the blocked site
     * sitting there unwatched.
     *
     * **This is a rule about *which event* releases it, and that is the part that went wrong.**
     * The release was first hung off the exit watcher's `left = true` verdict, which sounds
     * equivalent and is not: that watcher gives up *blind* whenever it cannot read the window
     * tree — routine with gesture navigation — and reports `left = false` when it does. The grace
     * then ran its full [DISMISS_GRACE_STUCK_MS] with the user already back on the site. Three
     * emulator rounds still reproduced the bug before the logs said why. Hence a named rule with
     * tests, rather than a line buried in the watcher.
     *
     * **A different package was never the only proof the user moved on** (26 Aug 2026). The
     * package rule cannot see the case the owner actually relapsed through: HOME is swallowed
     * on HyperOS, he stays in the blocked app, opens the *next* screen of it, and every window
     * event returns early for the full [DISMISS_GRACE_STUCK_MS]. Worse in a browser, because
     * since v1.135 a website block deliberately exits BACK and *keeps* him there — so "still in
     * the same app" went from the rare stuck case to the normal one, and both web scanners sat
     * out eight seconds after every website block.
     *
     * So [newDest] / [dismissedDest] carry *where* inside the package: the window class name for
     * an app cover, the host for a page cover. A different destination is the user navigating; the
     * same destination is the covered screen still emitting stragglers, which is what the grace
     * is for. Null on either side means "we could not tell" and never releases — the same rule as
     * a null package, and the reason this cannot degrade into releasing on every event.
     *
     * The stuck case the long window exists for is therefore still untouched: Home swallowed with
     * the user sitting where they were reads as unchanged, and holds.
     */
    fun graceSpentBy(
        newPkg: String?,
        dismissedPkg: String?,
        newDest: String? = null,
        dismissedDest: String? = null,
    ): Boolean {
        if (dismissedPkg == null || newPkg == null) return false
        if (newPkg != dismissedPkg) return true
        // Same package, and the user is somewhere else inside it. Not knowing where they were
        // or where they are now leaves the grace alone: a null destination is "we could not
        // tell", and releasing on that would cancel the protection on the first event that
        // happens to carry no class name.
        return dismissedDest != null && newDest != null && newDest != dismissedDest
    }

    /**
     * Whether a cover raised for [offenceKey] should record a new attempt, so that one open of a
     * blocked app is one attempt however many times the cover has to be drawn to keep the user
     * out — otherwise the cover's "minutes reclaimed" and the Insights totals over-report.
     *
     * The offence is not always the key the attempt is *recorded* under; see the service's
     * showBlockScreen, where a blocked word is recorded as "web" but declares the app as its
     * offence, because the lockout it adds raises a second cover moments later.
     *
     * [lastCountedOffence] is the offence counted most recently and [sinceLastCountMs] how long
     * ago — a different offence always counts, since that is a different target being blocked.
     * [resumingOffence] is set only while the service knows a cover is being put back up because
     * the trip Home failed; that one offence gets [RESUME_GRACE_MS] instead of the short
     * [COUNT_COOLDOWN_MS]. Bounding it by the same elapsed time means a stuck marker can delay a
     * count but never lose one indefinitely.
     */
    fun shouldCount(
        offenceKey: String,
        lastCountedOffence: String?,
        sinceLastCountMs: Long,
        resumingOffence: String? = null,
    ): Boolean {
        if (offenceKey != lastCountedOffence) return true
        val window = if (offenceKey == resumingOffence) RESUME_GRACE_MS else COUNT_COOLDOWN_MS
        return sinceLastCountMs >= window
    }

    /**
     * Whether the cover currently up belongs to a **scan** rather than to the app-block path —
     * i.e. [handleAppBlock][BlockerAccessibilityService] must leave it alone.
     *
     * The app-block path answers one question: *is this whole app blocked?* When the answer is no
     * it takes the cover down — and it used to take down whatever was up, including a cover raised
     * by a completely different question (*is this page blocked?*, *is this a purchase sheet?*).
     * So a blocked page inside an unblocked browser was covered by the scan and uncovered again by
     * the very next window event the browser emitted: the flicker the owner reported, and — because
     * the scan's text dedup then read the page as "already handled" — a page left silently
     * uncovered afterwards.
     *
     * A whole-app cover ([isAppBlock]) is always the app-block path's own, so it stays removable.
     * A scan's cover is only protected while its owner is still the app in front: once the user is
     * genuinely somewhere else the cover must come down, or it would strand over an innocent app.
     *
     * There used to be exactly one hardcoded instance of this rule — "keep a Shorts cover up even
     * though the whole app isn't blocked" — which is what a general rule looks like when it is
     * noticed for one caller only.
     */
    fun ownedByScan(
        coverShowing: Boolean,
        isAppBlock: Boolean,
        coverOwner: String?,
        pkg: String,
    ): Boolean = coverShowing && !isAppBlock && coverOwner != null && coverOwner == pkg

    /**
     * Whether a window event naming [pkg] must be ignored because a cover is up and the window
     * tree does not confirm that package is really in front.
     *
     * A blocked app runs behind the (non-focusable) cover and spits out stray windows — a splash,
     * a floating popup, a sub-window under another package — and background apps emit window-state
     * events routinely. Acting on one adopts it as the foreground app, and the app-block path then
     * finds *that* package unblocked and tears the live cover down; the real app's next event puts
     * it back. That is the "disappears ~2s then reblocks" flicker.
     *
     * This guard is deliberately **not** limited to whole-app covers. It was, and a page/word cover
     * therefore had no protection at all — the one kind of cover with no rule of its own to put it
     * back, since a blocked *site* arms no lockout. [currentPkg] is the app the service already
     * believes is in front, [activeWindowPkg] what the window tree says right now.
     *
     * Launchers are always exempt: Home must be honoured or the user is trapped under a cover
     * (docs/BLOCKING_INVARIANTS.md, invariant 7).
     */
    fun strayWindowEvent(
        coverShowing: Boolean,
        pkg: String?,
        currentPkg: String?,
        isLauncher: Boolean,
        activeWindowPkg: String?,
    ): Boolean {
        if (!coverShowing || pkg == null) return false
        if (pkg == currentPkg) return false
        if (isLauncher) return false
        return activeWindowPkg != pkg
    }
}
