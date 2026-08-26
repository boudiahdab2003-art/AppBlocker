package com.appblocker

import com.appblocker.data.BlockLatency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first instrument in this app that measures how long something took rather than whether it
 * happened. The bucketing is the whole of the rule; the storage around it is prefs and cannot be
 * reached from here.
 *
 * The boundaries matter for the same reason SilenceLog's does: put them in the wrong place and
 * the dial reads "fine" through exactly the slowness it exists to show.
 */
class BlockLatencyTest {

    @Test
    fun `an instant block lands in the fastest bucket`() {
        assertEquals(0, BlockLatency.bucketFor(0L))
        assertEquals(0, BlockLatency.bucketFor(249L))
    }

    @Test
    fun `each boundary belongs to the slower bucket`() {
        // A block that took exactly a quarter of a second did not take LESS than one, and a dial
        // that rounds in the flattering direction is the one mistake this must not make.
        assertEquals(1, BlockLatency.bucketFor(250L))
        assertEquals(2, BlockLatency.bucketFor(500L))
        assertEquals(3, BlockLatency.bucketFor(1_000L))
        assertEquals(4, BlockLatency.bucketFor(2_000L))
    }

    @Test
    fun `the slow tail is never averaged away`() {
        // The point of buckets over a mean: however long it took, it still shows up as one slow
        // block rather than being diluted by the fast ones around it.
        assertEquals(4, BlockLatency.bucketFor(2_001L))
        assertEquals(4, BlockLatency.bucketFor(30_000L))
        assertEquals(4, BlockLatency.bucketFor(Long.MAX_VALUE))
    }

    @Test
    fun `a nonsense interval cannot break the block it is measuring`() {
        // The clock is monotonic so this should not arise, but an instrument that throws on the
        // way out of raising a cover would cost the very thing it exists to protect.
        assertEquals(0, BlockLatency.bucketFor(-1L))
        assertEquals(0, BlockLatency.bucketFor(Long.MIN_VALUE))
    }

    @Test
    fun `every bucket has a label and the labels read fastest first`() {
        assertEquals(BlockLatency.SIZE, BlockLatency.LABELS.size)
        // Each bucket index has to be reachable, or a label would describe nothing.
        val reached = listOf(0L, 300L, 700L, 1_500L, 5_000L).map { BlockLatency.bucketFor(it) }
        assertEquals(listOf(0, 1, 2, 3, 4), reached)
        assertTrue(BlockLatency.LABELS.all { it.isNotBlank() })
    }
}
