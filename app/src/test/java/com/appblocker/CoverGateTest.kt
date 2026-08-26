package com.appblocker

import com.appblocker.service.CoverGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cover's timing rules. The "block screen shows twice, and counts twice" bug was fixed
 * twice by adjusting these numbers with nothing stating what they promise — so each rule the
 * service depends on gets a case here.
 */
class CoverGateTest {

    private val app = "com.example.social"
    private val other = "com.example.news"

    private fun suppressed(
        counterKey: String = app,
        dismissedKey: String? = app,
        dismissedPkg: String? = app,
        currentPkg: String? = app,
        sinceDismissMs: Long = 0L,
    ) = CoverGate.suppressed(counterKey, dismissedKey, dismissedPkg, currentPkg, sinceDismissMs)

    // --- Post-"Got it" suppression ---

    @Test
    fun `nothing is suppressed when no cover was dismissed`() {
        assertFalse(suppressed(dismissedKey = null, dismissedPkg = null))
    }

    @Test
    fun `the dismissed block cannot be raised again straight away`() {
        assertTrue(suppressed(sinceDismissMs = 0L))
        assertTrue(suppressed(sinceDismissMs = CoverGate.DISMISS_GRACE_MS - 1))
    }

    @Test
    fun `the short grace holds even once the user has left the app`() {
        // Home landed, so the foreground has moved on — stragglers from the departing app
        // must still not re-block during the transition.
        assertTrue(suppressed(currentPkg = other, sinceDismissMs = CoverGate.DISMISS_GRACE_MS - 1))
    }

    @Test
    fun `staying in the blocked app stays suppressed for the longer grace`() {
        // Home was slow or swallowed: the app really is still on screen.
        assertTrue(suppressed(sinceDismissMs = CoverGate.DISMISS_GRACE_STUCK_MS - 1))
    }

    @Test
    fun `staying in the blocked app re-blocks once the longer grace runs out`() {
        assertFalse(suppressed(sinceDismissMs = CoverGate.DISMISS_GRACE_STUCK_MS))
    }

    @Test
    fun `reopening the app after leaving blocks again once the short grace is over`() {
        assertFalse(suppressed(currentPkg = other, sinceDismissMs = CoverGate.DISMISS_GRACE_MS))
    }

    @Test
    fun `a different app opened right after a dismissal blocks instantly`() {
        assertFalse(suppressed(counterKey = other, currentPkg = other))
    }

    @Test
    fun `a web dismissal also suppresses the same app's lockout cover`() {
        // The word cover is keyed "web" but the lockout re-show is keyed by package; without
        // this the cover flashed during the trip Home.
        assertTrue(suppressed(counterKey = app, dismissedKey = "web", dismissedPkg = app))
    }

    @Test
    fun `shorts covers are never suppressed`() {
        // Their scan owns adding and removing them; suppressing a show would desync it and
        // leave Shorts uncovered.
        assertFalse(
            suppressed(counterKey = CoverGate.SHORTS_KEY, dismissedKey = CoverGate.SHORTS_KEY)
        )
    }

    // --- The key-agnostic grace the web scan uses ---

    @Test
    fun `the web scan's grace ignores which cover was dismissed`() {
        // Whatever the cover was keyed to, the page behind it is still on screen during the
        // trip Home — including after a Shorts cover, which suppressed() itself exempts.
        assertTrue(CoverGate.inGrace(app, app, 0L))
        assertTrue(CoverGate.inGrace(app, other, CoverGate.DISMISS_GRACE_MS - 1))
        assertTrue(CoverGate.inGrace(app, app, CoverGate.DISMISS_GRACE_STUCK_MS - 1))
    }

    @Test
    fun `the web scan's grace ends once the user has left and the short grace is up`() {
        assertFalse(CoverGate.inGrace(app, other, CoverGate.DISMISS_GRACE_MS))
        assertFalse(CoverGate.inGrace(app, app, CoverGate.DISMISS_GRACE_STUCK_MS))
    }

    // --- Releasing the grace when the user really leaves ---

    @Test
    fun `leaving the blocked app for another one spends the grace`() {
        // The whole point: the grace absorbs the departing app's stragglers, so once something
        // else is genuinely in front it has nothing left to do. Without this it kept running,
        // and coming straight back to the blocked site found both scanners switched off.
        assertTrue(CoverGate.graceSpentBy(newPkg = other, dismissedPkg = app))
    }

    @Test
    fun `staying in the blocked app does not spend the grace`() {
        // Home was swallowed and the app really is still on screen — the long window exists
        // for exactly this, so releasing here would re-block during the transition.
        assertFalse(CoverGate.graceSpentBy(newPkg = app, dismissedPkg = app))
    }

    @Test
    fun `there is nothing to spend when no cover was dismissed`() {
        assertFalse(CoverGate.graceSpentBy(newPkg = other, dismissedPkg = null))
    }

    @Test
    fun `an unknown foreground app never spends the grace`() {
        // A null package is "we could not tell", and acting on that is how covers ended up over
        // the wrong screen. Not knowing must leave the grace alone.
        assertFalse(CoverGate.graceSpentBy(newPkg = null, dismissedPkg = app))
    }

    @Test
    fun `leaving then returning is watched again immediately`() {
        // The two rules composed, which is the behaviour the owner actually reported: after the
        // grace is spent on the way out, re-opening the browser is NOT in grace any more, at a
        // moment when the un-spent version was still suppressing everything.
        val backInsideTheOldWindow = CoverGate.DISMISS_GRACE_STUCK_MS - 1
        assertTrue(CoverGate.inGrace(app, app, backInsideTheOldWindow))
        assertTrue(CoverGate.graceSpentBy(newPkg = other, dismissedPkg = app))
        // Spent means dismissedPkg is cleared, so the same instant now reads as no grace.
        assertFalse(CoverGate.inGrace(null, app, backInsideTheOldWindow))
    }

    // --- Releasing the grace when the user moves on inside the same app ---

    @Test
    fun `opening another page in the same app spends the grace`() {
        // The reported relapse. "Got it" on Instagram fires HOME, HyperOS swallows it, and the
        // user simply carries on to the next screen - same package throughout, so the package
        // rule above never fires and the cover stayed suppressed for the full eight seconds.
        // A different destination inside the app is the user moving on, not the app leaving.
        assertTrue(
            CoverGate.graceSpentBy(
                newPkg = app, dismissedPkg = app,
                newDest = "ReelsActivity", dismissedDest = "FeedActivity",
            ),
        )
    }

    @Test
    fun `a new site in the same browser spends the grace`() {
        // The same rule with a host as the destination, which is the browser half of the bug:
        // since v1.135 a website block sends the user BACK rather than Home, so staying in the
        // browser is the NORMAL outcome and the long grace applied to every website block.
        assertTrue(
            CoverGate.graceSpentBy(
                newPkg = app, dismissedPkg = app,
                newDest = "other.example", dismissedDest = "blocked.example",
            ),
        )
    }

    @Test
    fun `the stragglers from the page that was just covered do not spend the grace`() {
        // Same destination = the events the covered screen is still emitting on its way out.
        // Absorbing those is the entire reason the grace exists; releasing here would put the
        // flashing cover of v1.131 straight back.
        assertFalse(
            CoverGate.graceSpentBy(
                newPkg = app, dismissedPkg = app,
                newDest = "FeedActivity", dismissedDest = "FeedActivity",
            ),
        )
    }

    @Test
    fun `an unknown destination never spends the grace`() {
        // Both directions of "we could not tell", and both must leave the grace alone - the
        // same rule as the null package above. If not knowing released it, the first event
        // carrying no class name would cancel the protection outright.
        assertFalse(
            CoverGate.graceSpentBy(
                newPkg = app, dismissedPkg = app,
                newDest = null, dismissedDest = "FeedActivity",
            ),
        )
        assertFalse(
            CoverGate.graceSpentBy(
                newPkg = app, dismissedPkg = app,
                newDest = "FeedActivity", dismissedDest = null,
            ),
        )
    }

    @Test
    fun `the stuck case still holds the full grace when nothing has moved`() {
        // The protection this fix must not delete. Home was swallowed, the user has not moved,
        // and the app is genuinely still on screen: that is what the eight seconds are for, and
        // it is still eight seconds.
        assertFalse(
            CoverGate.graceSpentBy(
                newPkg = app, dismissedPkg = app,
                newDest = "FeedActivity", dismissedDest = "FeedActivity",
            ),
        )
        assertTrue(CoverGate.inGrace(app, app, CoverGate.DISMISS_GRACE_STUCK_MS - 1))
    }

    @Test
    fun `leaving for another app spends the grace whatever the destinations say`() {
        // The original rule keeps priority: a different package is already proof the user left,
        // and the destination is then irrelevant (a launcher class name means nothing here).
        assertTrue(
            CoverGate.graceSpentBy(
                newPkg = other, dismissedPkg = app,
                newDest = "FeedActivity", dismissedDest = "FeedActivity",
            ),
        )
    }

    // --- Attempt counting ---

    @Test
    fun `the first block of a target counts`() {
        assertTrue(CoverGate.shouldCount(app, lastCountedOffence = null, sinceLastCountMs = 0L))
    }

    @Test
    fun `redrawing the same block does not count twice`() {
        assertFalse(CoverGate.shouldCount(app, lastCountedOffence = app, sinceLastCountMs = 0L))
        assertFalse(
            CoverGate.shouldCount(
                app, lastCountedOffence = app, sinceLastCountMs = CoverGate.COUNT_COOLDOWN_MS - 1,
            )
        )
    }

    @Test
    fun `a genuine later open of the same app counts again`() {
        assertTrue(
            CoverGate.shouldCount(
                app, lastCountedOffence = app, sinceLastCountMs = CoverGate.COUNT_COOLDOWN_MS,
            )
        )
    }

    @Test
    fun `the plain cooldown stays short enough to count a real second open`() {
        // It only has to absorb event bursts. Padding it far enough to reach the resume redraw
        // below is what made two separate urges read as one, so that case is handled explicitly.
        assertTrue(CoverGate.COUNT_COOLDOWN_MS < CoverGate.RESUME_GRACE_MS)
        assertTrue(CoverGate.shouldCount(app, lastCountedOffence = app, sinceLastCountMs = 6_000L))
    }

    @Test
    fun `a cover put back up because Home failed is not a second attempt`() {
        // Give-up is ~2.5s after the dismissal and the redraw lands ~8s after that, measured
        // from the FIRST count — so this is past the plain cooldown and only the resume marker
        // can recognise it.
        assertFalse(
            CoverGate.shouldCount(
                app, lastCountedOffence = app, sinceLastCountMs = 11_000L, resumingOffence = app,
            )
        )
    }

    @Test
    fun `the resume allowance is bounded and applies only to that offence`() {
        // A marker left set can delay a count but never lose one indefinitely...
        assertTrue(
            CoverGate.shouldCount(
                app, lastCountedOffence = app,
                sinceLastCountMs = CoverGate.RESUME_GRACE_MS, resumingOffence = app,
            )
        )
        // ...and it never covers a different app.
        assertTrue(
            CoverGate.shouldCount(
                other, lastCountedOffence = other, sinceLastCountMs = 6_000L, resumingOffence = app,
            )
        )
    }

    @Test
    fun `a different target counts even inside the cooldown`() {
        assertTrue(CoverGate.shouldCount(other, lastCountedOffence = app, sinceLastCountMs = 0L))
    }

    @Test
    fun `a blocked word and the app lockout it creates count once between them`() {
        // The word cover is recorded under "web" but declares the APP as its offence, because
        // the lockout it adds makes a second, package-keyed "Locked" cover follow seconds later.
        // Counting the offence rather than the recorded key is what keeps that pair to one.
        assertTrue(CoverGate.shouldCount(app, lastCountedOffence = null, sinceLastCountMs = 0L))
        assertFalse(CoverGate.shouldCount(app, lastCountedOffence = app, sinceLastCountMs = 3_000L))
        // Whereas keying on what each was recorded under would have counted both.
        assertTrue(CoverGate.shouldCount(app, lastCountedOffence = "web", sinceLastCountMs = 3_000L))
    }

    // --- Whose cover is it (CoverGate.ownedByScan) ---

    @Test
    fun `a page cover over the app in front is not the app-block path's to remove`() {
        // The browser is not blocked as an app, so blockReason answers null every time it emits a
        // window event. Removing the cover there is what made a blocked page flicker.
        assertTrue(
            CoverGate.ownedByScan(
                coverShowing = true, isAppBlock = false, coverOwner = app, pkg = app,
            )
        )
    }

    @Test
    fun `a page cover is released once a different app is really in front`() {
        // The other direction matters as much: protect it forever and it strands over an app that
        // was never blocked.
        assertFalse(
            CoverGate.ownedByScan(
                coverShowing = true, isAppBlock = false, coverOwner = app, pkg = other,
            )
        )
    }

    @Test
    fun `a whole-app cover always belongs to the app-block path`() {
        assertFalse(
            CoverGate.ownedByScan(
                coverShowing = true, isAppBlock = true, coverOwner = app, pkg = app,
            )
        )
    }

    @Test
    fun `nothing is owned when no cover is up, or when the owner is unknown`() {
        assertFalse(
            CoverGate.ownedByScan(
                coverShowing = false, isAppBlock = false, coverOwner = app, pkg = app,
            )
        )
        assertFalse(
            CoverGate.ownedByScan(
                coverShowing = true, isAppBlock = false, coverOwner = null, pkg = app,
            )
        )
    }

    // --- Stray window events while a cover is up (CoverGate.strayWindowEvent) ---

    private fun stray(
        coverShowing: Boolean = true,
        pkg: String? = other,
        currentPkg: String? = app,
        isLauncher: Boolean = false,
        activeWindowPkg: String? = null,
    ) = CoverGate.strayWindowEvent(coverShowing, pkg, currentPkg, isLauncher, activeWindowPkg)

    @Test
    fun `a package the window tree does not confirm is ignored while a cover is up`() {
        // The classic case: our own non-focusable cover is what the tree reports, which says
        // nothing about what is behind it.
        assertTrue(stray(activeWindowPkg = "com.appblocker"))
        assertTrue(stray(activeWindowPkg = null))
    }

    @Test
    fun `this guard is not limited to whole-app covers`() {
        // It used to be, and a page cover therefore had no protection at all — the one kind with
        // no rule of its own to put it back, since a blocked site arms no lockout.
        assertTrue(stray(activeWindowPkg = app))
    }

    @Test
    fun `a confirmed app switch is acted on`() {
        assertFalse(stray(activeWindowPkg = other))
    }

    @Test
    fun `Home is always honoured`() {
        // Invariant 7: the user must never be trapped under a cover.
        assertFalse(stray(isLauncher = true, activeWindowPkg = app))
    }

    @Test
    fun `nothing is stray when no cover is up, or when the package has not changed`() {
        assertFalse(stray(coverShowing = false, activeWindowPkg = app))
        assertFalse(stray(pkg = app, activeWindowPkg = null))
        assertFalse(stray(pkg = null))
    }

    // ---- which way out a dismissal earns (v1.135) ---------------------------------------

    /**
     * **Reported as a loop.** He opened instagram.com in Chrome, was covered — correctly, Instagram
     * is on his blocked list — tapped "Got it", and was sent Home. The site was still the open tab,
     * so re-opening Chrome landed on it and covered again, with no route from the cover to a
     * different page. *"how can i correct it if i keep getting blocked and change the page"*.
     *
     * The app already treats a site hit as the gentle one: no lockout is added, so the browser is
     * not locked. The exit was the part that did not agree.
     */
    @Test fun aPageCoverGoesBackOffThePage() {
        assertEquals(CoverGate.Exit.BACK, CoverGate.exitFor(CoverGate.WEB_KEY))
    }

    /** A blocked app is blocked all the way through, so back would land on more of it. */
    @Test fun anAppCoverStillLeavesTheApp() {
        assertEquals(CoverGate.Exit.HOME, CoverGate.exitFor("com.instagram.android"))
        assertEquals(CoverGate.Exit.HOME, CoverGate.exitFor("strict_guard"))
    }

    /** Home is the answer that always works, so it is what an unkeyed cover gets. */
    @Test fun anUnkeyedCoverGetsTheExitThatAlwaysWorks() {
        assertEquals(CoverGate.Exit.HOME, CoverGate.exitFor(null))
    }

    /**
     * Android will not say whether BACK went anywhere, and a page opened straight into a fresh tab
     * has no history to step through. So a second "Got it" in the same app leaves instead — one
     * more tap, rather than the loop this whole change is about.
     */
    @Test fun aSecondGotItInTheSameAppLeavesInstead() {
        assertTrue(CoverGate.backExitFailed("com.android.chrome", "com.android.chrome", 500L))
        assertTrue(
            CoverGate.backExitFailed(
                "com.android.chrome", "com.android.chrome", CoverGate.BACK_RETRY_MS - 1,
            ),
        )
    }

    /** Bounded, so a genuine block later is a fresh start and still gets its back. */
    @Test fun theBackMemoryExpires() {
        assertFalse(
            CoverGate.backExitFailed(
                "com.android.chrome", "com.android.chrome", CoverGate.BACK_RETRY_MS,
            ),
        )
        // A clock that has gone backwards must not read as "just now" (invariant 9's habit).
        assertFalse(CoverGate.backExitFailed("com.android.chrome", "com.android.chrome", -1L))
    }

    /** Being blocked in a different app is a different situation, and deserves its own back. */
    @Test fun anotherAppGetsItsOwnBack() {
        assertFalse(CoverGate.backExitFailed("org.mozilla.firefox", "com.android.chrome", 500L))
        assertFalse(CoverGate.backExitFailed("com.android.chrome", null, 500L))
        assertFalse(CoverGate.backExitFailed(null, "com.android.chrome", 500L))
    }
}
