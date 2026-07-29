package com.appblocker

import com.appblocker.data.AiCoach
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rescuing an answer that arrived *nearly* as JSON.
 *
 * The coach is asked for one JSON object so the app can read the goals and suggestions out of it.
 * The cost of that is total: an answer cut off by the output ceiling leaves an object that never
 * closes, `JSONObject` throws, and a perfectly good reply is thrown away over a missing brace —
 * on exactly the requests worth having, since it is the long ones that get cut off.
 *
 * Only the words are rescued. Goals and profile updates need a well-formed object, and
 * half-applying a goal update from a truncated one would write something the owner never agreed to.
 */
class CoachSalvageTest {

    @Test
    fun `a reply cut off mid-sentence is still a reply`() {
        val truncated =
            """{"reply": "You're on track today — 40m by 14:00, which is well under yesterday's"""
        val out = AiCoach.salvageReply(truncated)
        assertTrue(out!!.startsWith("You're on track today"))
        assertTrue(out.endsWith("yesterday's"))
    }

    @Test
    fun `a complete reply is read exactly`() {
        val out = AiCoach.salvageReply("""{"reply": "Nice work.", "suggestions": ["More"]}""")
        assertEquals("Nice work.", out)
    }

    @Test
    fun `escapes come back as the characters they stand for`() {
        // A coach reply routinely contains quotation marks and paragraph breaks; a rescue that
        // showed \n and \" literally would look broken in a way the original never was.
        val out = AiCoach.salvageReply("""{"reply": "Line one.\nHe said \"stop\" and meant it."}""")
        assertEquals("Line one.\nHe said \"stop\" and meant it.", out)
    }

    @Test
    fun `a reply that is not first is still found`() {
        val out = AiCoach.salvageReply("""{"advice": "x", "reply": "Found me.", "goals": []}""")
        assertEquals("Found me.", out)
    }

    @Test
    fun `nothing to rescue answers null rather than nonsense`() {
        // Null means "fall through to naming the failure". Returning an empty string here would
        // show him a blank message bubble and call it an answer.
        assertNull(AiCoach.salvageReply(""))
        assertNull(AiCoach.salvageReply("""{"goals": []}"""))
        assertNull(AiCoach.salvageReply("""{"reply": "   "}"""))
        assertNull(AiCoach.salvageReply("not json at all"))
    }
}
