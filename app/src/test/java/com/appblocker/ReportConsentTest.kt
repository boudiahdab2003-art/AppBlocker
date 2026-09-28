package com.appblocker

import com.appblocker.data.ReportConsent
import com.appblocker.data.ReportConsent.State
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Whether an install may send reports by itself — asked once on a new install, his choice of
 * 28 Sep 2026 after a stranger's PC emulator (#202) sent its profile unasked.
 */
class ReportConsentTest {

    /** His four: main space, Second Space copy, second phone, tablet. They never see the question. */
    @Test fun `his own installs count as yes without being asked`() {
        listOf("a8dfc579", "5bf2e3b3", "868db09c", "8eef460f").forEach {
            assertEquals(it, State.YES, ReportConsent.state(stored = null, installId = it))
        }
    }

    /** Only his: an addition here is a decision about someone else's phone. */
    @Test fun `the list of his installs is exactly the four`() =
        assertEquals(setOf("a8dfc579", "5bf2e3b3", "868db09c", "8eef460f"), ReportConsent.OWNER_INSTALLS)

    @Test fun `the stranger's install and a fresh one are asked`() {
        assertEquals(State.UNASKED, ReportConsent.state(stored = null, installId = "2eb67345"))
        assertEquals(State.UNASKED, ReportConsent.state(stored = null, installId = null))
    }

    @Test fun `an answer given always wins, his own installs included`() {
        assertEquals(State.NO, ReportConsent.state(stored = "no", installId = "a8dfc579"))
        assertEquals(State.YES, ReportConsent.state(stored = "yes", installId = "2eb67345"))
        assertEquals(State.NO, ReportConsent.state(stored = "no", installId = null))
    }

    @Test fun `anything else stored is no answer`() =
        assertEquals(State.UNASKED, ReportConsent.state(stored = "maybe", installId = "2eb67345"))
}
