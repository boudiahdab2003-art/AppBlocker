package com.appblocker

import com.appblocker.service.CoverGate
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
}
