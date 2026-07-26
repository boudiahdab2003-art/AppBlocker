package com.appblocker

import com.appblocker.data.BugReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The privacy contract for anything leaving the device.
 *
 * This app's blocked-keyword list is largely adult words, and the watcher reads browser URLs and
 * on-screen text as a matter of course. A leak here would publish the exact data the app exists to
 * keep private, to a place the owner cannot easily unpublish. So the redaction is not a detail of
 * the reporter — it is the reason the reporter is allowed to exist, and it gets tests.
 *
 * The load-bearing case is [an exception message is never sent]: `Throwable.message` is where a
 * failing value gets quoted back ("no match for /pattern/"), and in this app that value is a
 * blocked word.
 */
class BugReportTest {

    private val secret = "someveryprivateblockedword"

    private fun report(t: Throwable) = BugReport.fromThrowable(
        where = "webScan",
        t = t,
        appVersion = "1.103",
        flavor = "github",
        androidSdk = 35,
        device = "Xiaomi 2312DRA50G",
    )

    // --- the contract ---

    @Test
    fun `an exception message is never sent`() {
        // The whole reason messages are dropped rather than sanitised.
        val body = report(IllegalArgumentException("blocked keyword: $secret")).body()
        assertFalse(body.contains(secret))
        assertFalse(body.contains("blocked keyword"))
    }

    @Test
    fun `a message hidden in a cause is never sent either`() {
        val inner = IllegalStateException("url was https://example.com/$secret")
        val body = report(RuntimeException("wrapper", inner)).body()
        assertFalse(body.contains(secret))
        assertFalse(body.contains("example.com"))
    }

    @Test
    fun `the title cannot carry a message either`() {
        // Titles are the most visible part of an issue, and the easiest place to leak by accident.
        val title = report(IllegalArgumentException(secret)).title()
        assertFalse(title.contains(secret))
        assertTrue(title.contains("IllegalArgumentException"))
    }

    @Test
    fun `the class name and location still make it through`() {
        // Redaction that removed everything useful would just get reverted later.
        val body = report(IllegalArgumentException(secret)).body()
        assertTrue(body.contains("IllegalArgumentException"))
        assertTrue(body.contains("webScan"))
        assertTrue(body.contains("1.103"))
        assertTrue(body.contains("SDK 35"))
    }

    @Test
    fun `only our own stack frames are kept`() {
        // A framework or OEM frame is noise at best, and names things we did not choose to send.
        val t = Throwable().apply {
            stackTrace = arrayOf(
                StackTraceElement("com.appblocker.service.Thing", "doIt", "Thing.kt", 42),
                StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 1),
                StackTraceElement("com.example.other.Secret", "leak", "S.java", 7),
            )
        }
        val frames = BugReport.ourFrames(t)
        assertEquals(listOf("Thing.doIt:42"), frames)
    }

    // --- the owner's own words are the one field that is user text by design ---

    @Test
    fun `a typed note is sent, because he chose to send it`() {
        val r = BugReport.fromNote("Instagram opened with no block screen", "1.103", "github", 35, "d")
        assertTrue(r.body().contains("Instagram opened with no block screen"))
    }

    @Test
    fun `a typed note is capped rather than refused`() {
        val r = BugReport.fromNote("x".repeat(9000), "1.103", "github", 35, "d")
        assertTrue((r.note?.length ?: 0) <= 2000)
    }

    // --- de-duplication, which is what stops a crash loop filing hundreds of issues ---

    @Test
    fun `the same crash twice is one bug`() {
        val t = { IllegalStateException("boom").apply {
            stackTrace = arrayOf(
                StackTraceElement("com.appblocker.A", "b", "A.kt", 3),
            )
        } }
        assertEquals(report(t()).dedupeKey(), report(t()).dedupeKey())
    }

    @Test
    fun `a different error in the same place is a different bug`() {
        assertNotEquals(
            report(IllegalStateException("a")).dedupeKey(),
            report(NullPointerException("a")).dedupeKey(),
        )
    }

    @Test
    fun `two typed notes are always separate, even if identical in spirit`() {
        // If he bothered to type it twice, he means it twice.
        val a = BugReport.fromNote("still broken", "1", "github", 35, "d")
        val b = BugReport.fromNote("still broken but worse", "1", "github", 35, "d")
        assertNotEquals(a.dedupeKey(), b.dedupeKey())
    }

    @Test
    fun `the payload is valid json`() {
        val json = report(IllegalArgumentException(secret)).toJson()
        val parsed = org.json.JSONObject(json)
        assertTrue(parsed.getString("title").isNotBlank())
        assertTrue(parsed.getString("body").isNotBlank())
        assertFalse(json.contains(secret))
    }
}
