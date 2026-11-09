package com.appblocker

import com.appblocker.service.CoverHold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A notice cover gets its moment on screen and is faded, not cut — his report of 9 Nov 2026: "it
 * blocks a real thing like the google discovery or the edit of notifications panel but then it
 * flash in an unprofessional way". The rule is [CoverHold.waitMs]; the rest pins where it is wired.
 */
class CoverHoldTest {

    @Test fun anOrdinaryCoverIsRemovedAtOnce() {
        assertEquals(0L, CoverHold.waitMs(holdUntilMs = 0L, nowMs = 5_000L, force = false))
    }

    @Test fun aNoticeWaitsOutWhatIsLeftOfItsHold() {
        assertEquals(600L, CoverHold.waitMs(holdUntilMs = 5_900L, nowMs = 5_300L, force = false))
        assertEquals(0L, CoverHold.waitMs(holdUntilMs = 5_900L, nowMs = 5_900L, force = false))
        assertEquals(0L, CoverHold.waitMs(holdUntilMs = 5_900L, nowMs = 9_000L, force = false))
    }

    @Test fun aTapIsNeverKeptWaiting() {
        assertEquals(0L, CoverHold.waitMs(holdUntilMs = 5_900L, nowMs = 5_000L, force = true))
    }

    /** A hold may delay a removal, never prevent one — whatever a clock says. */
    @Test fun aHoldIsNeverLongerThanTheNotice() {
        assertEquals(CoverHold.NOTICE_MS, CoverHold.waitMs(holdUntilMs = Long.MAX_VALUE, nowMs = 0L, force = false))
    }

    /** Each notice's own safety net (1.5 s) must fall after the hold, or it would be postponed too. */
    @Test fun theHoldEndsBeforeEveryNoticesSafetyNet() {
        assertTrue(CoverHold.NOTICE_MS < 1_500L)
    }

    @Test fun theThreeNoticesAskForTheHoldAndATapSkipsIt() {
        val dir = listOf("src/main/java/com/appblocker/service", "app/src/main/java/com/appblocker/service")
            .map(::File).first { it.exists() }
        val watcher = File(dir, "BlockerAccessibilityService.kt").readText()
        assertEquals(
            "the off-switch guard, the news feed and the Ultra saver are the notices",
            3, Regex("minVisibleMs = CoverHold\\.NOTICE_MS").findAll(watcher).count(),
        )
        val dismissed = watcher.substringAfter("private fun onCoverDismissed()").substringBefore("\n    private fun ")
        assertTrue("Got it must not wait for a hold", "overlay.remove()" !in dismissed)
        val overlay = File(dir, "BlockOverlay.kt").readText()
        assertTrue("the cover window fades", "windowAnimations = R.style.BlockCoverWindow" in overlay)
    }
}
