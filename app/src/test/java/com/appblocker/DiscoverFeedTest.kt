package com.appblocker

import com.appblocker.service.DiscoverFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Google's news feed, blocked always — invariant 101, his ask of 2 Nov 2026. The ids below were read
 * off his phone that day over USB (`uiautomator dump`), the feed open and then the home screen.
 */
class DiscoverFeedTest {

    @Test fun theFeedListIsRecognised() {
        assertTrue(DiscoverFeed.isFeedList("com.google.android.googlequicksearchbox:id/googleapp_discover_recycler_view"))
    }

    /** ⚠️ The home screen carries the Google app's search widget. Its ids must never read as the
     *  feed — a match there would push HOME over the home screen for ever. */
    @Test fun theHomeScreenSearchWidgetIsNotTheFeed() {
        for (id in listOf(
            "com.google.android.googlequicksearchbox:id/googleapp_search_widget_background",
            "com.google.android.googlequicksearchbox:id/googleapp_search_widget_google_logo",
            "com.google.android.googlequicksearchbox:id/googleapp_search_widget_shortcut_btn",
            "com.google.android.googlequicksearchbox:id/googleapp_doodle_fragment",
            "com.google.android.googlequicksearchbox:id/video_frame",
            null,
        )) assertFalse("$id read as the feed", DiscoverFeed.isFeedList(id))
    }

    @Test fun oneOpeningIsOneBounce() {
        assertTrue(DiscoverFeed.shouldBounce(listVisible = true, sinceLastBounceMs = DiscoverFeed.BOUNCE_GAP_MS))
        assertFalse(DiscoverFeed.shouldBounce(listVisible = true, sinceLastBounceMs = 200))
        assertFalse(DiscoverFeed.shouldBounce(listVisible = false, sinceLastBounceMs = 60_000))
    }

    @Test fun itWatchesTheGoogleApp() {
        assertEquals("com.google.android.googlequicksearchbox", DiscoverFeed.PACKAGE)
    }

    /**
     * ⚠️ **His choice, 2 Nov 2026: "Always".** Neither the scheduler nor the scan may ask for a setting
     * or for Quick Block; the scan must only act on a VISIBLE list, and must go HOME with the
     * safety net behind it.
     */
    @Test fun itIsBlockedAlwaysAndOnlyWhenSeen() {
        val dir = listOf(File("src/main/java/com/appblocker/service"), File("app/src/main/java/com/appblocker/service"))
            .first { it.exists() }
        val text = File(dir, "BlockerAccessibilityService.kt").readText()
        fun body(start: String): String {
            val from = text.indexOf(start)
            assertTrue("cannot find $start", from >= 0)
            return text.substring(from, text.indexOf("\n    private ", from + start.length))
        }
        val schedule = body("private fun scheduleDiscoverScan(")
        val scan = body("private suspend fun scanDiscoverFeed()")
        for ((name, code) in mapOf("scheduleDiscoverScan" to schedule, "scanDiscoverFeed" to scan)) {
            for (gate in listOf("quickBlockActive()", "SettingsStore.")) {
                assertFalse("$name asks $gate — the news feed is blocked always", gate in code)
            }
        }
        assertTrue(scan.contains("discoverFeedVisible()"))
        assertTrue(scan.contains("performGlobalAction(GLOBAL_ACTION_HOME)"))
        assertTrue("the cover needs its safety net", scan.contains("overlay.remove()"))
        // The event routes that reach it: the front app's content, someone else's content (the
        // launcher hosts the feed), and a new front app.
        assertTrue(Regex("scheduleDiscoverScan\\(").findAll(text).count() >= 4)

        val reader = File(dir, "ScreenText.kt").readText()
            .substringAfter("fun AccessibilityService.discoverFeedVisible()").substringBefore("\n}")
        assertTrue("a hidden feed list must not count", reader.contains("isVisibleToUser"))
    }
}
