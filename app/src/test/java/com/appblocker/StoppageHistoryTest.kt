package com.appblocker

import com.appblocker.data.OutageLog
import com.appblocker.data.ProcessExits
import com.appblocker.data.StoppageHistory
import com.appblocker.data.SwitchOffLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stoppage list a report carries: outages and switched-off periods in one time order, because
 * the question it answers — do these cluster after restarts, updates, one hour of the night — is
 * a question about time.
 */
class StoppageHistoryTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    private fun outage(at: Long) = OutageLog.Episode(
        startedAt = at,
        durationMs = 10 * minute,
        detectedAfterMs = minute,
        aliveButDeaf = false,
        precededBy = OutageLog.Preceded.NOTHING,
        rebooted = false,
        versionCode = 163L,
    )

    private fun off(at: Long) = SwitchOffLog.Episode(
        startedAt = at,
        durationMs = 20 * minute,
        detectedAfterMs = minute,
        how = SwitchOffLog.How.NOT_RUNNING,
        guardArmed = null,
        precededBy = OutageLog.Preceded.BOOT,
        rebooted = true,
        fromBoot = true,
        usedDuringMin = 0,
        versionCode = 163L,
        endedBy = OutageLog.EndedBy.APP_OPENED,
    )

    /**
     * ⚠️ The kinds are deliberately lopsided — one outage newest, two periods older. The first
     * version put an outage at both ends and passed with the sort reversed: a symmetric fixture
     * reads the same in either order, so it could not see the order it was named for.
     */
    @Test fun `both kinds are one newest-first list`() {
        val lines = StoppageHistory.merge(
            outages = listOf(outage(now - 10 * minute)),
            switchOffs = listOf(off(now - 60 * minute), off(now - 30 * minute)),
        )
        assertEquals(3, lines.size)
        assertFalse(lines[0], "SWITCHED-OFF" in lines[0])
        assertTrue(lines[1], "SWITCHED-OFF" in lines[1])
        assertTrue(lines[2], "SWITCHED-OFF" in lines[2])
    }

    /** Capped from the old end, so a full history still leads with this week. */
    @Test fun `the cap drops the oldest lines, whichever kind they are`() {
        val lines = StoppageHistory.merge(
            outages = List(25) { outage(now - 1_000 * minute - it * minute) },
            switchOffs = List(25) { off(now - it * minute) },
        )
        assertEquals(StoppageHistory.MAX_LINES, lines.size)
        assertEquals(25, lines.count { "SWITCHED-OFF" in it })
    }

    /**
     * Android's own record of a death rides in the same time order (invariant 72) — the death just
     * after a stoppage's last sign of life is the one thing its line cannot say — and it never
     * pushes a stoppage out of the capped list.
     */
    @Test fun `a death sits in time order beside the stoppage it began, outside the cap`() {
        val death = ProcessExits.Exit(now - 20 * minute + 5_000L, "low-memory", null, 230, null)
        val lines = StoppageHistory.merge(
            outages = listOf(outage(now - 20 * minute), outage(now - 90 * minute)),
            switchOffs = emptyList(),
            exits = listOf(death),
        )
        assertEquals(3, lines.size)
        assertTrue(lines[0], "EXITED" in lines[0])
        assertFalse(lines[1], "EXITED" in lines[1])
        val full = StoppageHistory.merge(
            outages = List(StoppageHistory.MAX_LINES) { outage(now - it * minute) },
            switchOffs = emptyList(),
            exits = listOf(death),
        )
        assertEquals(StoppageHistory.MAX_LINES, full.count { "EXITED" !in it })
        assertEquals(1, full.count { "EXITED" in it })
    }
}
