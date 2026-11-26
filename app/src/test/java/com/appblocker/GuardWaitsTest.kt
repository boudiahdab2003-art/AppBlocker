package com.appblocker

import com.appblocker.data.OffSwitchGuard
import com.appblocker.data.UninstallUnlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **Invariant 111 — every wait, "Very strong"** (his pick, 26 Nov 2026, the evening he relapsed by
 * uninstalling): off-switch guard 48 h, adult filter off 7 days, uninstall 14 days, the lockout after
 * a blocked word 6 h, and every "the switch works now" window 30 minutes. A wait that quietly shrank
 * back would look exactly as strict on screen, so each number is pinned here.
 */
class GuardWaitsTest {

    private val hour = 60 * 60_000L
    private val day = 24 * hour

    @Test
    fun `the off-switch guard waits two days`() {
        assertEquals(48 * hour, OffSwitchGuard.UNLOCK_DELAY_MS)
        assertEquals(30 * 60_000L, OffSwitchGuard.UNLOCK_WINDOW_MS)
    }

    @Test
    fun `uninstalling waits fourteen days, longer than any other wait`() {
        assertEquals(14 * day, UninstallUnlock.UNLOCK_DELAY_MS)
        assertTrue(UninstallUnlock.UNLOCK_DELAY_MS > OffSwitchGuard.UNLOCK_DELAY_MS)
    }

    @Test
    fun `the adult filter waits seven days, then works for only half an hour`() {
        val text = source("ui/KeywordsScreen.kt").readText()
        assertTrue("private const val OFF_DELAY_MS = 7 * 24 * 60 * 60_000L" in text)
        // It used to stay switchable for a whole further day once served.
        assertTrue("private const val OFF_WINDOW_MS = 30 * 60_000L" in text)
        assertTrue("private const val OFF_DELAY_LABEL = \"7 days\"" in text)
    }

    @Test
    fun `an app stays locked six hours after a blocked word`() {
        val text = source("service/BlockerAccessibilityService.kt").readText()
        assertTrue("private const val KEYWORD_LOCKOUT_MS = 6 * 60 * 60_000L" in text)
    }

    private fun source(path: String): File =
        listOf(File("src/main/java/com/appblocker/$path"), File("app/src/main/java/com/appblocker/$path"))
            .firstOrNull { it.isFile }
            ?: error("cannot find $path from ${File(".").absolutePath}")
}
