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

    // --- the percentage, which is where a wrong reading gets its authority --------------------

    @Test
    fun `quick is the first two buckets and nothing measured is null`() {
        assertEquals(null, BlockLatency.sharePercent(listOf(0, 0, 0, 0, 0)))
        assertEquals(100, BlockLatency.sharePercent(listOf(1, 1, 0, 0, 0)))
        assertEquals(0, BlockLatency.sharePercent(listOf(0, 0, 1, 1, 1)))
        // His own lifetime figure: 62/27/14/11/1 reads 77%, which is where this started.
        assertEquals(77, BlockLatency.sharePercent(listOf(62, 27, 14, 11, 1)))
    }

    /**
     * ⚠️ **The reason [BlockLatency.MIN_FOR_VERDICT] exists, stated as arithmetic.**
     *
     * Over eighteen covers one block is worth five and a half points. 12 quick of 18 reads 66%
     * against a lifetime 77%, and that gap — a single cover either way — was quoted to the owner
     * as blocking having got slower. A share this coarse is a reading, not a verdict.
     */
    @Test
    fun `a share over a handful of covers moves several points per block`() {
        assertEquals(66, BlockLatency.sharePercent(listOf(7, 5, 3, 3, 0)))
        assertEquals(72, BlockLatency.sharePercent(listOf(8, 5, 3, 2, 0)))
        assertTrue(
            "one cover must move a small sample by several points, or the guard is unnecessary",
            BlockLatency.sharePercent(listOf(8, 5, 3, 2, 0))!! -
                BlockLatency.sharePercent(listOf(7, 5, 3, 3, 0))!! >= 5,
        )
        assertTrue(BlockLatency.MIN_FOR_VERDICT > 18)
    }

    /**
     * ⚠️ **The two paths are not comparable, and this is the arithmetic that says so.**
     *
     * The debounced page scan does nothing for 250ms before it starts work, and up to ~950ms
     * across a burst, while the stopwatch runs from the event that armed it. So a settled cover
     * cannot land in bucket 0 at all and reaches bucket 1 only if it settles instantly. Blending
     * it with the instant paths produces a percentage that tracks which paths were used rather
     * than how fast anything was.
     */
    @Test
    fun `a settled cover cannot reach the fastest bucket however fast the work is`() {
        // Zero work, but the debounce has already run: the floor is its own delay.
        assertEquals(1, BlockLatency.bucketFor(250L))
        assertTrue(BlockLatency.bucketFor(250L) > 0)
        // The realistic settle across a burst is past half a second before work begins.
        assertTrue(BlockLatency.bucketFor(700L) >= 2)
        assertTrue(BlockLatency.bucketFor(949L) >= 2)
    }
}
