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

    // --- the settings context, which is the newest way this could leak ---

    @Test
    fun `a key that isn't on the allow-list is dropped`() {
        // The realistic future mistake: someone adds "the word that was blocked" to the context
        // map in the service layer, where a Context is available and the privacy rules are less
        // in view. It must not reach a payload no matter how it got into the map.
        val sanitized = BugReport.sanitizeContext(
            mapOf(
                "layout" to "focus",
                "keyword" to secret,
                "url" to "https://example.com/$secret",
                "blockedApp" to "com.instagram.android",
            ),
        )
        assertEquals(mapOf("layout" to "focus"), sanitized)
    }

    @Test
    fun `a forbidden key cannot reach the body even via the factories`() {
        val r = BugReport.fromNote(
            "it broke", "1.103", "github", 35, "d",
            context = mapOf("keyword" to secret, "layout" to "editorial"),
        )
        assertFalse(r.body().contains(secret))
        assertTrue(r.body().contains("editorial"))
    }

    @Test
    fun `allowed values are truncated, so nothing long slips through a permitted key`() {
        val sanitized = BugReport.sanitizeContext(mapOf("layout" to secret.repeat(20)))
        assertTrue((sanitized["layout"]?.length ?: 0) <= 24)
    }

    @Test
    fun `every allowed key is a setting or a count, never content`() {
        // A tripwire for the list itself: if someone adds a key that sounds like content, this
        // fails and makes them argue for it in a code review rather than in a payload.
        val contentish = listOf("keyword", "word", "url", "site", "domain", "app", "package",
            "name", "text", "query", "title", "location")
        val offenders = BugReport.ALLOWED_CONTEXT_KEYS.filter { key ->
            contentish.any { key.lowercase().contains(it) }
        }
        assertEquals(emptyList<String>(), offenders)
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

/**
 * The block log answers "why did it block?" without saying *what* it blocked — the distinction
 * the whole reporting feature rests on. Its value is that a cover landing somewhere wrong is
 * invisible after the milliseconds it lasts, so the shape has to be recorded as it happens.
 */
class BlockLogTest {

    @Test
    fun `an unknown kind is recorded as other, never passed through`() {
        // The realistic mistake: the call site is deep in the watcher where a package name is
        // the nearest variable to hand. A stray one must not become a log line.
        val e = com.appblocker.data.BlockLog.decode(
            "1000|com.instagram.android|false|true|true", now = 1000,
        )
        assertEquals("other", e?.kind)
    }

    @Test
    fun `a rendered line contains only fixed tokens`() {
        val line = com.appblocker.data.BlockLog
            .decode("1000|app|true|false|true", now = 4000)!!.render()
        assertEquals("3s ago  app  ownUi=true  rootOk=false  counted=true", line)
    }

    @Test
    fun `a clock jump never renders a negative age`() {
        // Recorded "in the future" after a clock change; a human reads these against each other.
        val e = com.appblocker.data.BlockLog.decode("9000|app|false|true|false", now = 1000)
        assertEquals(0L, e?.agoMs)
    }

    @Test
    fun `a malformed entry is dropped rather than crashing the report`() {
        assertEquals(null, com.appblocker.data.BlockLog.decode("nonsense", now = 1))
        assertEquals(null, com.appblocker.data.BlockLog.decode("1|app|true", now = 1))
        assertEquals(null, com.appblocker.data.BlockLog.decode("x|app|true|true|true", now = 1))
    }
}

/**
 * The device-admin activation exemption.
 *
 * The guard blocked Android's "Activate device admin app?" screen — which says *device admin* and
 * carries an *Uninstall app* button — so uninstall protection could never be switched ON while
 * the app still reported itself as guarded. The window below is what lets that screen through.
 */
class AdminPromptTest {

    @Test
    fun `nothing is exempt before we ask`() {
        com.appblocker.data.AdminPrompt.clear()
        assertFalse(com.appblocker.data.AdminPrompt.recentlyRequested())
    }

    @Test
    fun `clearing closes the window immediately`() {
        // Called once the admin state actually changed, so the exemption can't outlive its screen.
        com.appblocker.data.AdminPrompt.requested()
        com.appblocker.data.AdminPrompt.clear()
        assertFalse(com.appblocker.data.AdminPrompt.recentlyRequested())
    }

    @Test
    fun `the window is long enough to read the screen and short enough not to be a door`() {
        // It has a paragraph and three buttons, so seconds is too short; but it must not survive
        // long enough to walk to the DEACTIVATION screen afterwards.
        assertTrue(com.appblocker.data.AdminPrompt.WINDOW_MS >= 30_000L)
        assertTrue(com.appblocker.data.AdminPrompt.WINDOW_MS <= 120_000L)
    }
}
