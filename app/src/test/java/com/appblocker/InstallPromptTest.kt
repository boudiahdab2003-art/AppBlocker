package com.appblocker

import com.appblocker.data.InstallPrompt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exemption that says *"we opened this installer ourselves"* — and whether it is still there
 * after the install it exists to cover.
 *
 * **This is report #6.** The off-switch guard treats an installer screen naming AppBlocker as
 * "do you want to uninstall this app?", because that is how it catches a real removal in any
 * language. Installing an update opens the same installer and shows the same name, so the guard
 * stands down while we know we asked for it — for five minutes, deliberately long enough to cover
 * the "app installed / Open" screen that follows.
 *
 * It never reached that screen. The stamp lived in a `@Volatile` field, and installing our own APK
 * makes Android kill our process: the service came back with the field at 0, read the post-install
 * screen as a removal, covered it and threw the owner home. *"After the update I got a blocking
 * screen idk why"* — 21 Aug 2026, one `why=guard` line 26 seconds before he wrote it.
 *
 * So the decision takes its world as arguments (there is no Robolectric here — same reason
 * `SessionClock.remainingAt` is shaped this way), and the case that matters is the last one.
 */
class InstallPromptTest {

    private val boot = 42

    @Test fun theExemptionIsOpenInsideTheWindow() {
        assertTrue(InstallPrompt.openAt(1_000L, boot, boot, 1_000L))
        assertTrue(InstallPrompt.openAt(1_000L, boot, boot, 1_000L + 60_000L))
        assertTrue(InstallPrompt.openAt(1_000L, boot, boot, 1_000L + InstallPrompt.WINDOW_MS))
    }

    /** Bounded, so it can never become a standing exemption from the uninstall guard. */
    @Test fun itClosesOnceTheWindowHasPassed() {
        assertFalse(InstallPrompt.openAt(1_000L, boot, boot, 1_000L + InstallPrompt.WINDOW_MS + 1))
    }

    /**
     * A reboot restarts the monotonic clock, so a stamp from before one cannot be compared against
     * it. This is what the old code's "negative elapsed means a reboot" was reaching for, said
     * directly instead of inferred.
     */
    @Test fun aStampFromBeforeARebootIsExpired() {
        assertFalse(InstallPrompt.openAt(1_000L, boot, boot + 1, 2_000L))
    }

    /** Every way of not knowing answers closed — closed is the guarding direction. */
    @Test fun notKnowingAnswersClosed() {
        assertFalse(InstallPrompt.openAt(0L, boot, boot, 5_000L))        // never stamped
        assertFalse(InstallPrompt.openAt(-1L, boot, boot, 5_000L))       // junk
        assertFalse(InstallPrompt.openAt(1_000L, -1, boot, 2_000L))      // boot unreadable then
        assertFalse(InstallPrompt.openAt(1_000L, boot, -1, 2_000L))      // boot unreadable now
        assertFalse(InstallPrompt.openAt(1_000L, -1, -1, 2_000L))        // and both
        assertFalse(InstallPrompt.openAt(5_000L, boot, boot, 1_000L))    // clock went backwards
    }

    /**
     * **The report, as a test.** The stamp is written just before the installer opens; the process
     * is killed when the new APK lands; the accessibility service reconnects and asks. Same boot —
     * a reinstall is not a reboot — so the answer must still be "ours", or the post-install screen
     * is read as somebody removing the app.
     */
    @Test fun itSurvivesTheProcessDeathTheInstallCauses() {
        val stampedBeforeInstall = 120_000L
        val serviceAsksAfterRestart = 120_000L + 25_000L
        assertTrue(
            "the guard must still know this installer is ours after the update replaced us",
            InstallPrompt.openAt(stampedBeforeInstall, boot, boot, serviceAsksAfterRestart),
        )
    }
}
