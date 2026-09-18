package com.appblocker

import com.appblocker.data.ProcessExits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android's record of why AppBlocker's process ended, turned into what a stoppage line carries
 * (invariant 72). Everything here ends up in a report read once, later, by someone who cannot
 * re-run the phone — so a wrong token is worse than `?`.
 */
class ProcessExitsTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    private fun exit(
        at: Long,
        reason: String = "low-memory",
        sub: String? = null,
        importance: Int = 230,
        note: String? = null,
    ) = ProcessExits.Exit(at, reason, sub, importance, note)

    // ---- what Android's words become ------------------------------------------------------------

    @Test fun `every reason Android defines has its own word, and an unknown code keeps its number`() {
        val words = (0..16).map { ProcessExits.reasonName(it) }
        assertFalse(words.toString(), words.any { it.startsWith("code-") })
        assertEquals("two codes share a word: $words", 17, words.toSet().size)
        assertEquals("low-memory", ProcessExits.reasonName(3))
        assertEquals("user-requested", ProcessExits.reasonName(10))
        assertEquals("freezer", ProcessExits.reasonName(14))
        assertEquals("package-updated", ProcessExits.reasonName(16))
        assertEquals("code-99", ProcessExits.reasonName(99))
    }

    /** The shape AOSP's `ApplicationExitInfo.toString()` prints — the subreason is nowhere else. */
    private val forceStop = "ApplicationExitInfo(timestamp=9/11/26 4:58 PM pid=12345 realUid=10234 " +
        "packageUid=10234 definingUid=10234 user=0 process=com.appblocker reason=10 (USER REQUESTED) " +
        "subreason=23 (FORCE STOP) status=0 importance=230 pss=41MB rss=120MB " +
        "description=stop com.appblocker due to from pid 2345 state=empty trace=null)"

    @Test fun `the subreason is read out of the one place Android prints it`() {
        assertEquals("force-stop", ProcessExits.subreasonOf(forceStop))
        assertEquals(
            "cached-idle-forced-app-standby",
            ProcessExits.subreasonOf("reason=13 (OTHER) subreason=18 (CACHED IDLE & FORCED APP STANDBY) status=0"),
        )
    }

    @Test fun `no subreason, an unknown one, or a string of another shape is null rather than a guess`() {
        assertNull(ProcessExits.subreasonOf("reason=3 (LOW MEMORY) subreason=0 (UNKNOWN) status=0"))
        assertNull(ProcessExits.subreasonOf("something else entirely"))
        assertNull(ProcessExits.subreasonOf(null))
    }

    @Test fun `importance separates dying as the blocker from being reclaimed afterwards`() {
        assertEquals("foreground", ProcessExits.importanceName(100))
        assertEquals("visible", ProcessExits.importanceName(200))
        assertEquals("perceptible", ProcessExits.importanceName(230))
        assertEquals("service", ProcessExits.importanceName(300))
        assertEquals("cached", ProcessExits.importanceName(400))
        assertEquals("empty", ProcessExits.importanceName(500))
        assertEquals("gone", ProcessExits.importanceName(1000))
        assertEquals("unknown", ProcessExits.importanceName(0))
    }

    // ---- the note: the phone's own words, never another app's name ------------------------------

    @Test fun `a note keeps the platform's names and loses every other app's`() {
        val note = ProcessExits.sanitiseNote("killed by com.miui.powerkeeper for com.whatsapp")!!
        assertTrue(note, "com.miui.powerkeeper" in note)
        assertFalse(note, "whatsapp" in note)
        assertTrue(note, "<app>" in note)
        assertTrue(ProcessExits.sanitiseNote("stop com.appblocker due to from pid 2345")!!.contains("com.appblocker"))
        assertFalse(ProcessExits.sanitiseNote("empty for example.com")!!.contains("example"))
    }

    @Test fun `a note is plain, short and never an empty string`() {
        assertNull(ProcessExits.sanitiseNote(null))
        assertNull(ProcessExits.sanitiseNote("   "))
        assertTrue(ProcessExits.sanitiseNote("kill " + "x".repeat(200))!!.length <= ProcessExits.MAX_NOTE)
        val odd = ProcessExits.sanitiseNote("clean|by;powerkeeper\n\"now\"")!!
        assertFalse(odd, odd.any { it == '|' || it == ';' || it == '\n' || it == '"' })
    }

    // ---- which death closed the watcher ---------------------------------------------------------

    /** The first after the last sign of life, not the newest: everything after it is aftermath. */
    @Test fun `the first death after the last sign of life is the one that closed the watcher`() {
        val exits = listOf(
            exit(now - 5 * minute, reason = "low-memory", importance = 400),
            exit(now - 40 * minute, reason = "other", sub = "kill-background", importance = 230),
            exit(now - 90 * minute, reason = "user-requested", sub = "force-stop"),
        )
        assertEquals(
            "other:kill-background@perceptible",
            ProcessExits.killedBy(exits, lastSignOfLife = now - 60 * minute, now = now),
        )
    }

    @Test fun `no death after the last sign of life is none, and a question nobody could ask is not`() {
        assertEquals(ProcessExits.NONE, ProcessExits.killedBy(listOf(exit(now - 90 * minute)), now - 60 * minute, now))
        assertEquals(ProcessExits.NONE, ProcessExits.killedBy(emptyList(), now - 60 * minute, now))
        assertEquals(ProcessExits.UNREAD, ProcessExits.killedBy(null, now - 60 * minute, now))
        assertEquals(ProcessExits.UNREAD, ProcessExits.killedBy(emptyList(), 0L, now))
    }

    /** Android keeps a short list. When all of it is inside the window, the first death may be gone. */
    @Test fun `a list that is full inside the window says the first death may be missing`() {
        val full = List(4) { exit(now - (it + 1) * minute) }
        assertEquals(
            "low-memory@perceptible" + ProcessExits.MAYBE_EARLIER,
            ProcessExits.killedBy(full, now - 60 * minute, now, max = 4),
        )
        // One record older than the window proves the list reached back past its start.
        val reached = full.dropLast(1) + exit(now - 120 * minute)
        assertEquals("low-memory@perceptible", ProcessExits.killedBy(reached, now - 60 * minute, now, max = 4))
    }

    // ---- what may be stored and printed ---------------------------------------------------------

    @Test fun `every token a line can carry survives storage, and anything else is refused`() {
        listOf(
            "low-memory@cached", "user-requested:force-stop@perceptible+earlier?",
            ProcessExits.NONE, ProcessExits.UNREAD,
        ).forEach { assertEquals(it, ProcessExits.safeToken(it)) }
        listOf("a|b", "x;y", "", "Low-Memory", "two words", null)
            .forEach { assertEquals(it.toString(), ProcessExits.UNREAD, ProcessExits.safeToken(it)) }
        val made = exit(now, reason = "other", sub = "kill-background", importance = 400).token()
        assertEquals(made, ProcessExits.safeToken(made))
    }

    @Test fun `an exit line names itself and can never pass for a stoppage`() {
        val line = exit(now, reason = "freezer", sub = "freezer-binder-transaction", importance = 400, note = "frozen").render()
        assertTrue(line, "EXITED" in line)
        assertTrue(line, "reason=freezer" in line)
        assertTrue(line, "was=cached" in line)
        assertTrue(line, "note=frozen" in line)
        assertFalse(line, "down=" in line || "off=" in line)
    }

    // ---- a signalled death names its signal -----------------------------------------------------

    /**
     * 15 Sep 2026: the first killer the owner's phone named was `signaled@fg-service`, with no
     * subreason and no description. The status is the signal for that reason only; for the others it
     * is an exit code or zero, and borrowing it would print a signal that never happened.
     */
    @Test fun `a signalled death says which signal, and no other reason borrows the status`() {
        assertEquals("sig-9", ProcessExits.signalOf(reason = 2, status = 9))
        assertNull(ProcessExits.signalOf(reason = 2, status = 0))
        assertNull("exit-self: the status is an exit code", ProcessExits.signalOf(reason = 1, status = 9))
        assertNull("crash", ProcessExits.signalOf(reason = 4, status = 9))
        val token = exit(now, reason = "signaled", sub = ProcessExits.signalOf(2, 9), importance = 125).token()
        assertEquals("signaled:sig-9@fg-service", token)
        assertEquals("a signal token must survive storage", token, ProcessExits.safeToken(token))
        assertTrue(exit(now, reason = "signaled", sub = "sig-9", importance = 125).render().contains("sub=sig-9"))
    }

    /** The memory held at death rides the line, and a record with none never reads as an empty process. */
    @Test
    fun `an exit line says how much memory the process held`() {
        val big = ProcessExits.Exit(now, "signaled", "sig-9", 125, null, rssKb = 212L * 1024 + 300)
        assertTrue(big.render(), "rss=212mb" in big.render())
        assertEquals("rounds to the nearest megabyte", "213mb", ProcessExits.sizeName(212L * 1024 + 700))
        val none = exit(now).render()
        assertTrue(none, "rss=?" in none)
        assertFalse(none, "rss=0mb" in none)
        assertTrue("the size must not push the note off the line", none.endsWith("note=-"))
    }
}
