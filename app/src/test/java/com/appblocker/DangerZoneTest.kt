package com.appblocker

import com.appblocker.data.DangerZone
import com.appblocker.data.GuardedDeadline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The escalation the owner asked for the day after a relapse. His choices are the subject of
 * these tests, not my reading of them — above all the one that decides what the feature even
 * means: three DIFFERENT words, not the same word three times.
 */
class DangerZoneTest {

    private val boot = 7

    /** A strike recorded [agoMs] ago, on this boot. */
    private fun strike(agoMs: Long) = GuardedDeadline(
        realtimeStart = 1_000_000L - agoMs,
        realtimeEnd = 1_000_000L - agoMs + DangerZone.STRIKE_WINDOW_MS,
        wallStart = 5_000_000L - agoMs,
        wallEnd = 5_000_000L - agoMs + DangerZone.STRIKE_WINDOW_MS,
        bootCount = boot,
    )

    private fun board(vararg words: Pair<String, Long>) =
        words.associate { (w, ago) -> DangerZone.key(w) to strike(ago) }

    private fun trips(b: Map<String, GuardedDeadline>) =
        DangerZone.tripsAt(b, boot, nowRt = 1_000_000L, nowWall = 5_000_000L)

    @Test
    fun `three different words inside the window arm the zone`() {
        assertTrue(trips(board("one" to 0L, "two" to 60_000L, "three" to 120_000L)))
    }

    @Test
    fun `the same word three times is not a hunt`() {
        // THE test for this feature. Typing one word over and over is one person being stubborn
        // about one thing; three different words is searching. Getting this wrong would fire the
        // hour at someone who typed the same thing twice out of frustration, which is exactly the
        // moment an unfair punishment does the most damage.
        assertTrue(board("same" to 0L, "same" to 60_000L, "same" to 120_000L).size == 1)
        assertFalse(trips(board("same" to 0L, "same" to 60_000L, "same" to 120_000L)))
    }

    @Test
    fun `two different words are not enough`() {
        assertFalse(trips(board("one" to 0L, "two" to 60_000L)))
    }

    @Test
    fun `a word that fell out of the window no longer counts`() {
        // His choice was the TIGHTEST window offered: only a real session should trip this. A
        // strike that never expired would turn the feature into "three words, ever".
        assertFalse(
            trips(
                board(
                    "old" to DangerZone.STRIKE_WINDOW_MS + 1,
                    "two" to 60_000L,
                    "three" to 120_000L,
                ),
            ),
        )
        assertEquals(
            2,
            DangerZone.liveStrikesAt(
                board("old" to DangerZone.STRIKE_WINDOW_MS + 1, "two" to 0L, "three" to 0L),
                boot, 1_000_000L, 5_000_000L,
            ),
        )
    }

    @Test
    fun `his numbers are what the code uses`() {
        // Pinned so nobody "tunes" them later without meeting the conversation they came from.
        assertEquals(30 * 60_000L, DangerZone.STRIKE_WINDOW_MS)
        assertEquals(3, DangerZone.STRIKES_TO_TRIP)
        assertEquals(60 * 60_000L, DangerZone.LOCKDOWN_MS)
    }

    @Test
    fun `a strike is stored by identity, never as the word`() {
        // Counting to three needs identity, not text - so nothing on disk can say what he
        // searched for. And the key must never carry a character that would corrupt the record
        // it lives in: GuardedDeadline joins its fields with these.
        assertEquals(DangerZone.key("Word"), DangerZone.key("  word "))
        assertNotEquals(DangerZone.key("one"), DangerZone.key("two"))
        for (w in listOf("a word", "wörd", "كلمة", "x|y", "x;y", "-neg")) {
            val k = DangerZone.key(w)
            assertFalse("key must not contain a separator: $k", k.contains('|') || k.contains(';'))
            assertFalse("key must not be the word itself", k == w)
        }
    }

    @Test
    fun `the block screen names it and counts down, and says nothing else`() {
        val msg = DangerZone.message(47 * 60_000L)
        assertTrue(msg.contains("three"))
        assertTrue(msg.contains("47"))
        // No scoreboard: this screen arrives uninvited at the worst moment.
        assertFalse(msg.contains("total"))
        assertFalse(msg.contains("times"))
        // Never "0 minutes" while the zone is still running.
        assertTrue(DangerZone.message(1L).contains("1 minute."))
    }
    // ---- a site caught in two different browsers ------------------------------------------

    @Test
    fun `one browser is not enough to block a site outright`() {
        // One adult word on a page proves very little - a news article, a forum thread about
        // quitting, a word in a comment. This is the rule that keeps those from being blocked.
        assertFalse(DangerZone.learns(setOf("com.android.chrome")))
        assertFalse(DangerZone.learns(emptySet()))
    }

    @Test
    fun `going to a second browser and arriving at the same place is the signal`() {
        // His own condition, and the reason it is trustworthy: switching browsers to reach the
        // same site is not something that happens by accident. It is a statement about intent
        // rather than about the page's content.
        assertTrue(DangerZone.learns(setOf("com.android.chrome", "com.miui.browser")))
    }

    @Test
    fun `the same browser twice is still one browser`() {
        // The evidence is a SET of packages, so a page reloaded twenty times in one browser
        // never graduates. If this were a count instead, refreshing would be enough.
        val seen = setOf("com.android.chrome") + "com.android.chrome"
        assertFalse(DangerZone.learns(seen))
    }

}
