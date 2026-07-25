package com.appblocker

import com.appblocker.data.GuardedDeadline
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
class GuardedDeadlineTest {

    private val pkg = "com.example.social"
    private val boot = 7

    /** A lockout started "now" in the current boot. */
    private fun lockout(
        rtStart: Long = 1_000L,
        durationMs: Long = 30 * 60_000L,
        wallStart: Long = 1_700_000_000_000L,
        bootCount: Int = boot,
        note: String? = "porn",
    ) = GuardedDeadline(
        realtimeStart = rtStart,
        realtimeEnd = rtStart + durationMs,
        wallStart = wallStart,
        wallEnd = wallStart + durationMs,
        bootCount = bootCount,
        note = note,
    )

    // --- the anchoring ---
    // These use remainingAt so both clocks are explicit. remaining() reads the real ones, and
    // under isReturnDefaultValues SystemClock.elapsedRealtime() is 0 while currentTimeMillis()
    // is genuine — which makes every lockout look post-reboot and nothing deterministic.

    private val halfHour = 30 * 60_000L

    @Test
    fun `a fresh lockout is running on the monotonic clock`() {
        // Same boot, 10 minutes in. The wall clock is deliberately absurd (far past) to prove
        // it is not consulted — this is the bypass that used to work.
        val remaining = lockout().remainingAt(boot, nowRt = 1_000L + 10 * 60_000L, nowWall = 1L)
        assertEquals(20 * 60_000L, remaining)
    }

    @Test
    fun `winding the wall clock forward does not lift a lockout`() {
        // The whole point of the fix: same boot, only 1 minute elapsed monotonically, but the
        // wall clock has jumped a year. 29 minutes must still be left.
        val remaining = lockout().remainingAt(
            boot, nowRt = 1_000L + 60_000L, nowWall = 1_700_000_000_000L + 365L * 86_400_000L,
        )
        assertEquals(29 * 60_000L, remaining)
    }

    @Test
    fun `a lockout expires on the monotonic clock`() {
        assertEquals(0L, lockout().remainingAt(boot, nowRt = 1_000L + halfHour, nowWall = 1L))
        assertEquals(0L, lockout().remainingAt(boot, nowRt = 1_000L + halfHour * 9, nowWall = 1L))
    }

    @Test
    fun `after a reboot it falls back to the guarded wall clock`() {
        // A reboot resets the monotonic clock, so the saved realtime anchors mean nothing.
        val l = lockout(bootCount = boot - 1)
        // Wall clock 10 minutes in: 20 left.
        assertEquals(
            20 * 60_000L,
            l.remainingAt(boot, nowRt = 0L, nowWall = 1_700_000_000_000L + 10 * 60_000L),
        )
        // Wall clock past the end: expired.
        assertEquals(0L, l.remainingAt(boot, nowRt = 0L, nowWall = 1_700_000_000_000L + halfHour))
    }

    @Test
    fun `a wall clock reading before the lockout even started is treated as expired`() {
        // Impossible, so it means the clock is wrong — SessionClock's guard, inherited here.
        val l = lockout(bootCount = boot - 1)
        assertEquals(0L, l.remainingAt(boot, nowRt = 0L, nowWall = 1_699_999_000_000L))
    }

    // --- the shifted end, used by the adult-pack gate's follow-up window ---

    @Test
    fun `extraMs shifts the deadline later without moving the anchors`() {
        // The gate needs two moments from one record: "the 24h wait is over" and "and the window
        // to actually flip the switch has now lapsed too".
        val l = lockout(durationMs = 24 * 60 * 60_000L)
        val nowRt = 1_000L + 24 * 60 * 60_000L // exactly at the unlock moment
        assertEquals(0L, l.remainingAt(boot, nowRt = nowRt, nowWall = 1L))
        assertEquals(
            60 * 60_000L,
            l.remainingAt(boot, nowRt = nowRt, nowWall = 1L, extraMs = 60 * 60_000L),
        )
    }

    @Test
    fun `winding the clock forward does not open the gate early either`() {
        // Same bypass as the lockout's, on the app's most hardened protection: request made one
        // minute ago on this boot, wall clock jumped two days. Still ~24h to wait.
        val l = lockout(durationMs = 24 * 60 * 60_000L)
        val remaining = l.remainingAt(
            boot,
            nowRt = 1_000L + 60_000L,
            nowWall = 1_700_000_000_000L + 2L * 24 * 60 * 60_000L,
        )
        assertEquals(24 * 60 * 60_000L - 60_000L, remaining)
    }

    @Test
    fun `starting anchors to both clocks and the given boot`() {
        val d = GuardedDeadline.starting(halfHour, boot, note = "porn")
        assertEquals(boot, d.bootCount)
        assertEquals("porn", d.note)
        assertEquals(halfHour, d.realtimeEnd - d.realtimeStart)
        assertEquals(halfHour, d.wallEnd - d.wallStart)
    }

    @Test
    fun `remaining never exceeds the lockout's own length`() {
        // A backward clock jump post-reboot must not stretch a 30-minute lockout into days.
        val l = lockout(bootCount = boot - 1)
        assertEquals(true, l.remainingAt(boot, nowRt = 0L, nowWall = 1_700_000_100_000L) <= halfHour)
    }

    // --- the stored format ---

    @Test
    fun `encode and decode round-trip, minus the word`() {
        val original = lockout()
        val (decodedPkg, decoded) = GuardedDeadline.decode(original.encode(pkg))!!
        assertEquals(pkg, decodedPkg)
        assertEquals(original.copy(note = null), decoded)
    }

    @Test
    fun `the word is deliberately not persisted`() {
        // A user keyword can contain anything, including this format's separator.
        assertNull(GuardedDeadline.decode(lockout(note = "a|b").encode(pkg))!!.second.note)
    }

    @Test
    fun `the old expiry-only form is still readable`() {
        // Pre-upgrade entries were "pkg|expiryEpochMillis". Dropping them would have unlocked
        // apps early on the very update that fixed the bypass.
        val deadline = 1_700_000_000_000L
        val (decodedPkg, decoded) = GuardedDeadline.decode("$pkg|$deadline")!!
        assertEquals(pkg, decodedPkg)
        assertEquals(-1, decoded.bootCount) // forces SessionClock's wall-clock path
        assertEquals(
            10 * 60_000L,
            decoded.remainingAt(boot, nowRt = 0L, nowWall = deadline - 10 * 60_000L),
        )
    }

    @Test
    fun `an expired old-form entry is not running`() {
        val deadline = 1_700_000_000_000L
        val legacy = GuardedDeadline.decode("$pkg|$deadline")!!.second
        assertEquals(0L, legacy.remainingAt(boot, nowRt = 0L, nowWall = deadline + 1_000L))
    }

    @Test
    fun `unreadable entries are dropped rather than crashing`() {
        assertNull(GuardedDeadline.decode(""))
        assertNull(GuardedDeadline.decode("|123"))
        assertNull(GuardedDeadline.decode("no-separator"))
        assertNull(GuardedDeadline.decode("$pkg|not-a-number"))
        assertNull(GuardedDeadline.decode("$pkg|1|2|3")) // wrong field count
        assertNull(GuardedDeadline.decode("$pkg|1|2|3|4|notanumber"))
    }

    @Test
    fun `a package name survives decoding intact`() {
        assertEquals(pkg, GuardedDeadline.decode(lockout().encode(pkg))!!.first)
        assertNotNull(GuardedDeadline.decode(lockout().encode("a.b.c.d_e")))
    }
}
