package com.appblocker

import com.appblocker.data.AiCoach
import com.appblocker.data.ChatMsg
import com.appblocker.data.CoachActions
import com.appblocker.data.CoachActions.Type
import com.appblocker.data.CoachError
import com.appblocker.data.Goal
import com.appblocker.data.GoalKind
import com.appblocker.data.Goals
import com.appblocker.service.BlockWhy
import com.appblocker.service.CoverCoach
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * **Invariant 114 — the AI Coach, reviewed and upgraded (26 Nov 2026).** His picks: action buttons,
 * the 🆘 urge button, recovery streak + dates (never the journal), Deep thinking, and "Talk to
 * coach" on the block screen. Plus the bugs the review found.
 */
class CoachUpgradeTest {

    private val apps = listOf(
        CoachActions.App("com.google.android.youtube", "YouTube"),
        CoachActions.App("com.android.chrome", "Chrome"),
        CoachActions.App("com.zhiliaoapp.musically", "TikTok"),
        CoachActions.App("com.google.android.dialer", "Phone"),
    )
    private val notPhone: (String) -> Boolean = { it != "com.google.android.dialer" }

    // ---- action buttons ----------------------------------------------------------------------------

    @Test
    fun `the coach can only offer actions that make blocking stronger`() {
        assertEquals(
            setOf("lock_app", "lock_until_morning", "daily_limit", "strict"),
            Type.entries.map { it.wire }.toSet(),
        )
        for (t in Type.entries) {
            for (weak in listOf("unlock", "pause", "remove", "stop", "allow", "disable")) {
                assertFalse("${t.wire} reads like a weakening action", weak in t.wire)
            }
        }
        val parsed = CoachActions.parse(
            JSONArray("""[{"type":"unlock_app","app":"YouTube"},{"type":"remove_limit","app":"Chrome"},{"type":"pause"}]"""),
            apps, notPhone,
        )
        assertTrue("unknown types must be dropped: $parsed", parsed.isEmpty())
    }

    @Test
    fun `actions resolve to real apps, skip essentials, clamp minutes and stop at three`() {
        val parsed = CoachActions.parse(
            JSONArray(
                """[
                {"type":"lock_app","app":"youtube","minutes":5000},
                {"type":"lock_app","app":"Phone","minutes":60},
                {"type":"daily_limit","app":"Chro","minutes":1},
                {"type":"lock_app","app":"Netflix","minutes":60},
                {"type":"strict","minutes":2},
                {"type":"lock_until_morning","app":"TikTok"}
                ]""",
            ),
            apps, notPhone,
        )
        assertEquals(3, parsed.size)
        assertEquals(CoachActions.Action(Type.LOCK_APP, "com.google.android.youtube", "YouTube", 720), parsed[0])
        assertEquals(CoachActions.Action(Type.DAILY_LIMIT, "com.android.chrome", "Chrome", 5), parsed[1])
        assertEquals(CoachActions.Action(Type.STRICT, minutes = 15), parsed[2])
    }

    @Test
    fun `the same button in a later reply is a new button`() {
        val a = CoachActions.Action(Type.STRICT, minutes = 60)
        assertNotEquals(CoachActions.key(1000L, a), CoachActions.key(2000L, a))
        val back = CoachActions.fromJson(CoachActions.toJson(listOf(a)))
        assertEquals(listOf(a), back)
    }

    @Test
    fun `chat history keeps buttons, time and kind, and still reads the old format`() {
        val msgs = listOf(
            ChatMsg("user", "🆘 I have an urge right now.", at = 10L, kind = ChatMsg.KIND_URGE),
            ChatMsg("local", "not stored"),
            ChatMsg("model", "Try this.", listOf(CoachActions.Action(Type.STRICT, minutes = 60)), at = 20L),
        )
        val back = AiCoach.decodeChat(AiCoach.encodeChat(msgs))!!
        assertEquals(listOf(msgs[0], msgs[2]), back)
        val old = AiCoach.decodeChat("""[{"r":"user","t":"hi"},{"r":"model","t":"hello"}]""")!!
        assertEquals(listOf(ChatMsg("user", "hi"), ChatMsg("model", "hello")), old)
    }

    // ---- the model chain ---------------------------------------------------------------------------

    @Test
    fun `a model that hangs or errors walks on to the next one`() {
        assertTrue(AiCoach.tryNextModel(java.net.SocketTimeoutException("Read timed out")))
        assertTrue(AiCoach.tryNextModel(java.io.IOException("HTTP 524: error code: 524")))
        assertTrue(AiCoach.tryNextModel(java.io.IOException("HTTP 500: internal")))
        assertTrue(AiCoach.tryNextModel(java.io.IOException("HTTP 502: bad gateway")))
        assertFalse(AiCoach.tryNextModel(java.io.IOException("HTTP 400: bad request")))
        assertFalse(AiCoach.tryNextModel(java.io.IOException("HTTP 403: forbidden")))
    }

    @Test
    fun `a model that misbehaved recently goes to the back of the line`() {
        val chain = listOf("pro", "flash3", "flash35", "flash25")
        assertEquals(listOf("flash3", "flash25", "pro", "flash35"),
            AiCoach.modelOrder(chain, null, false, cooling = setOf("pro", "flash35")))
        assertEquals(listOf("flash25", "pro", "flash3", "flash35"),
            AiCoach.modelOrder(chain, "flash25", true, cooling = emptySet()))
        // A remembered winner that is resting loses its place too.
        assertEquals(listOf("pro", "flash3", "flash35", "flash25"),
            AiCoach.modelOrder(chain, "flash25", true, cooling = setOf("flash25")))
    }

    @Test
    fun `the working flash model is tried before the one that hung`() {
        val m = AiCoach.COACH_CHAIN.models
        assertTrue(m.indexOf("gemini-3-flash-preview") in 0 until m.indexOf("gemini-3.5-flash"))
        assertTrue(m.first().contains("pro"))
        assertTrue(AiCoach.CHAT_MAX_OUTPUT_TOKENS >= 16_384)
    }

    // ---- reading a response ------------------------------------------------------------------------

    @Test
    fun `every text part is read, thoughts are not`() {
        val r = """{"candidates":[{"content":{"parts":[{"text":"secret","thought":true},{"text":"{\"reply\":"},{"text":" \"hi\"}"}]},"finishReason":"STOP"}]}"""
        assertEquals("{\"reply\": \"hi\"}", AiCoach.extractText(r))
    }

    @Test
    fun `a refusal is told apart from a broken reply`() {
        try {
            AiCoach.extractText("""{"promptFeedback":{"blockReason":"SAFETY"}}""")
            fail("a blocked prompt must throw")
        } catch (e: Exception) {
            assertEquals(CoachError.BLOCKED, AiCoach.classify(e))
        }
        try {
            AiCoach.extractText("""{"candidates":[{"finishReason":"SAFETY"}]}""")
            fail("a safety stop must throw")
        } catch (e: Exception) {
            assertEquals(CoachError.BLOCKED, AiCoach.classify(e))
        }
        try {
            AiCoach.extractText("""{"candidates":[{"content":{"parts":[]},"finishReason":"MAX_TOKENS"}]}""")
            fail("an empty reply must throw")
        } catch (e: Exception) {
            assertEquals(CoachError.BAD_REPLY, AiCoach.classify(e))
        }
    }

    // ---- goals -------------------------------------------------------------------------------------

    @Test
    fun `a goal update the app cannot fully understand changes nothing`() {
        val existing = listOf(Goal("g1", GoalKind.APP_LIMIT, 30, "com.google.android.youtube", "YouTube"))
        assertNull(
            AiCoach.goalUpdate(
                JSONArray("""[{"kind":"app","app":"YouTube","minutes":30},{"kind":"app","app":"Netflix","minutes":20}]"""),
                apps, existing,
            ),
        )
        val ok = AiCoach.goalUpdate(
            JSONArray("""[{"kind":"app","app":"YouTube","minutes":30},{"kind":"screen_time","minutes":180}]"""),
            apps, existing,
        )!!
        assertEquals("the kept goal keeps its id and history", "g1", ok[0].id)
        assertEquals(GoalKind.SCREEN_TIME, ok[1].kind)
    }

    @Test
    fun `goal ids made in the same moment are still different`() {
        val ids = (1..50).map { Goals.newId() }
        assertEquals(ids.size, ids.toSet().size)
    }

    // ---- recovery: streak and dates, never the journal ----------------------------------------------

    @Test
    fun `the coach gets the streak and the dates`() {
        val day = 24 * 60 * 60_000L
        val now = 1_800_000_000_000L
        val text = AiCoach.recoveryPromptText(
            startedAt = now - 12 * day, bestMs = 30 * day,
            resets = listOf(now - 12 * day, now - 50 * day), urges = listOf(now - day), now = now,
        )
        assertTrue(text, "Clean for 12 days" in text)
        assertTrue(text, "Longest streak so far: 30 days" in text)
        assertTrue(text, "Relapses recorded" in text)
        assertTrue(text, "I have an urge\" 1 time" in text)
        assertEquals("", AiCoach.recoveryPromptText(0L, 0L, emptyList(), emptyList(), now))
    }

    @Test
    fun `the journal never reaches the coach`() {
        val coach = source("data/AiCoach.kt").readLines()
            .filterNot { val t = it.trim(); t.startsWith("*") || t.startsWith("//") || t.startsWith("/**") }
            .joinToString("\n")
        for (bad in listOf("JournalStore", "journalDao", "JournalDao", "JournalEntry")) {
            assertFalse("AiCoach must not read the journal ($bad)", bad in coach)
        }
        assertTrue("CleanStreak.resets(ctx)" in coach)
    }

    // ---- what he is told when he is low ------------------------------------------------------------

    @Test
    fun `the coach answers struggle without numbers, and urges at once`() {
        val coach = source("data/AiCoach.kt").readText()
        assertTrue("NO numbers, NO statistics" in coach)
        assertTrue("URGE MODE" in coach)
        assertTrue("never name adult websites" in coach)
        assertTrue("0800 111 0 111" in coach)
        val vm = source("ui/CoachChatViewModel.kt").readText()
        // The calm card is written into the app, so it shows before (and without) any reply.
        assertTrue("R.string.coach_urge_card" in vm)
        assertTrue("AiCoach.recordUrge(" in vm)
    }

    // ---- "Talk to coach" on the block screen -------------------------------------------------------

    @Test
    fun `talk to coach never passes a blocked word or site`() {
        for (why in listOf(BlockWhy.WORD, BlockWhy.SITE, BlockWhy.ADULT)) {
            assertEquals("a website", CoverCoach.label(why, null))
        }
        assertEquals("YouTube", CoverCoach.label(BlockWhy.QUICK, "YouTube"))
        assertEquals("a protection setting", CoverCoach.label(BlockWhy.GUARD_UNINSTALL, null))
        assertEquals("Instagram", CoverCoach.label(BlockWhy.INSTAGRAM, "Instagram"))
    }

    @Test
    fun `every cover layout has the button, and it leaves like Got it`() {
        for (f in listOf("overlay_block", "overlay_block_focus", "overlay_block_quote", "overlay_block_scoreboard")) {
            val xml = listOf(File("src/main/res/layout/$f.xml"), File("app/src/main/res/layout/$f.xml"))
                .first { it.isFile }.readText()
            assertTrue("$f has no Talk to coach", "@+id/overlay_coach" in xml)
        }
        val overlay = source("service/BlockOverlay.kt").readText()
        assertTrue("R.id.overlay_coach)?.setOnClickListener { onCoach() }" in overlay)
        val service = source("service/BlockerAccessibilityService.kt").readText()
        val body = service.substringAfter("private fun onCoverCoach() {").substringBefore("\n    }")
        assertTrue("it must leave exactly as Got it does", "onCoverDismissed()" in body)
        assertTrue(body.indexOf("onCoverDismissed()") < body.indexOf("startActivity("))
        assertTrue("overlay.remove(now = true)" in body)
        assertTrue("MainActivity.EXTRA_OPEN_COACH" in body)
    }

    private fun source(path: String): File =
        listOf(File("src/main/java/com/appblocker/$path"), File("app/src/main/java/com/appblocker/$path"))
            .firstOrNull { it.isFile }
            ?: error("cannot find $path from ${File(".").absolutePath}")
}
