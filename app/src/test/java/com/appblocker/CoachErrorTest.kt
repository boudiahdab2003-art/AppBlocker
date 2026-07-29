package com.appblocker

import com.appblocker.data.AiCoach
import com.appblocker.data.CoachError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Which failure the coach reports.
 *
 * Every coach failure used to end as `null` and render as one grey line — "Couldn't reach Gemini
 * — check your connection and try again" — for no internet, an exhausted quota, a dead server, a
 * rejected key and an unparsable reply alike. So the only report the owner could make was "the
 * coach isn't working", and on the day this was written that cost a whole round of investigation
 * against a server that turned out to be healthy: the real answer was that he hadn't installed
 * the update. The categories exist so the app can say which one it is.
 *
 * The call itself needs a network and a live key, so this is the only part of that path a test
 * can reach — and it is the part that decides what he is told.
 */
class CoachErrorTest {

    @Test
    fun `quota is quota, however Google phrases it`() {
        assertEquals(
            CoachError.QUOTA,
            AiCoach.classify(java.io.IOException("HTTP 429: quota exceeded")),
        )
        assertEquals(
            CoachError.QUOTA,
            AiCoach.classify(java.io.IOException("RESOURCE_EXHAUSTED")),
        )
    }

    @Test
    fun `a rejected key is not a network problem`() {
        // Telling him to check his connection when the fault is my key wastes his time on the
        // one thing he cannot fix.
        assertEquals(CoachError.REJECTED, AiCoach.classify(java.io.IOException("HTTP 401")))
        assertEquals(CoachError.REJECTED, AiCoach.classify(java.io.IOException("HTTP 403")))
    }

    @Test
    fun `no internet and no server are told apart`() {
        // One is his to fix in a second; the other is mine. They used to read identically.
        assertEquals(CoachError.OFFLINE, AiCoach.classify(java.net.UnknownHostException("x")))
        assertEquals(CoachError.SERVER_DOWN, AiCoach.classify(java.net.ConnectException("x")))
        assertEquals(
            CoachError.SERVER_DOWN,
            AiCoach.classify(java.net.SocketTimeoutException("read timed out")),
        )
        assertEquals(CoachError.SERVER_DOWN, AiCoach.classify(java.io.IOException("HTTP 503")))
    }

    @Test
    fun `an answer the app could not read is its own case`() {
        assertEquals(CoachError.BAD_REPLY, AiCoach.classify(org.json.JSONException("No value for reply")))
    }

    @Test
    fun `an unrecognised failure is visibly unrecognised, not silently something else`() {
        assertEquals(CoachError.UNKNOWN, AiCoach.classify(IllegalStateException("boom")))
        assertEquals(CoachError.UNKNOWN, AiCoach.classify(null))
    }

    // --- the privacy line, which is the same one BugReport draws ---

    @Test
    fun `the error text never rides along with the category`() {
        // Gemini's error bodies quote the request back, and this app's requests carry the owner's
        // usage figures, his goals and the coach conversation. The category may leave the app —
        // shown on screen, sent in a report — so it must not be able to carry any of that with it.
        val leaky = "HTTP 429: quota exceeded for request containing 'my private note'"
        val category = AiCoach.classify(java.io.IOException(leaky))
        assertEquals(CoachError.QUOTA, category)
        assertFalse(category.name.contains("private"))
        assertFalse(category.name.contains("quota exceeded"))
    }
}
