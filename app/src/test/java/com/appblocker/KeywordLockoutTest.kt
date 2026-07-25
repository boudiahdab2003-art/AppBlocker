package com.appblocker

import com.appblocker.data.KeywordLockout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The 30-minute lockout after a blocked word used to be a bare wall-clock deadline, so winding
 * the device clock forward lifted it — the bypass every other timer was moved off the wall clock
 * to prevent. It is now anchored like a session, and these cover the anchoring and the stored
 * format (including the old form, so an update doesn't drop a lockout that is still running).
 */
class KeywordLockoutTest {

    private val pkg = "com.example.social"
    private val boot = 7

    /** A lockout started "now" in the current boot. */
    private fun lockout(
        rtStart: Long = 1_000L,
        durationMs: Long = 30 * 60_000L,
        wallStart: Long = 1_700_000_000_000L,
        bootCount: Int = boot,
        word: String? = "porn",
    ) = KeywordLockout(
        realtimeStart = rtStart,
        realtimeEnd = rtStart + durationMs,
        wallStart = wallStart,
        wallEnd = wallStart + durationMs,
        bootCount = bootCount,
        word = word,
    )

    // --- the anchoring ---

    @Test
    fun `a fresh lockout is running`() {
        // Same boot, so the monotonic anchor is used and the wall clock is irrelevant.
        assertEquals(true, lockout().remaining(boot) > 0L)
    }

    @Test
    fun `a lockout from a different boot falls back to the wall clock`() {
        // A reboot resets the monotonic clock, so the saved realtime anchors mean nothing —
        // the guarded wall-clock deadline is the only information left.
        val stale = lockout(bootCount = boot - 1)
        assertEquals(0L, stale.remaining(boot))
    }

    @Test
    fun `an expired lockout reports zero, never negative`() {
        val done = lockout(rtStart = 0L, durationMs = 0L, wallStart = 1L)
        assertEquals(0L, done.remaining(boot))
    }

    // --- the stored format ---

    @Test
    fun `encode and decode round-trip, minus the word`() {
        val original = lockout()
        val (decodedPkg, decoded) = KeywordLockout.decode(original.encode(pkg))!!
        assertEquals(pkg, decodedPkg)
        assertEquals(original.copy(word = null), decoded)
    }

    @Test
    fun `the word is deliberately not persisted`() {
        // A user keyword can contain anything, including this format's separator.
        assertNull(KeywordLockout.decode(lockout(word = "a|b").encode(pkg))!!.second.word)
    }

    @Test
    fun `the old expiry-only form is still readable`() {
        // Pre-upgrade entries were "pkg|expiryEpochMillis". Dropping them would have unlocked
        // apps early on the very update that fixed the bypass.
        val future = System.currentTimeMillis() + 10 * 60_000L
        val (decodedPkg, decoded) = KeywordLockout.decode("$pkg|$future")!!
        assertEquals(pkg, decodedPkg)
        assertEquals(-1, decoded.bootCount) // forces SessionClock's wall-clock path
        assertEquals(true, decoded.remaining(boot) > 0L)
    }

    @Test
    fun `an expired old-form entry is not running`() {
        val past = System.currentTimeMillis() - 1_000L
        assertEquals(0L, KeywordLockout.decode("$pkg|$past")!!.second.remaining(boot))
    }

    @Test
    fun `unreadable entries are dropped rather than crashing`() {
        assertNull(KeywordLockout.decode(""))
        assertNull(KeywordLockout.decode("|123"))
        assertNull(KeywordLockout.decode("no-separator"))
        assertNull(KeywordLockout.decode("$pkg|not-a-number"))
        assertNull(KeywordLockout.decode("$pkg|1|2|3")) // wrong field count
        assertNull(KeywordLockout.decode("$pkg|1|2|3|4|notanumber"))
    }

    @Test
    fun `a package name survives decoding intact`() {
        assertEquals(pkg, KeywordLockout.decode(lockout().encode(pkg))!!.first)
        assertNotNull(KeywordLockout.decode(lockout().encode("a.b.c.d_e")))
    }
}
