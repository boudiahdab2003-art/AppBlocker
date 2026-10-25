package com.appblocker

import com.appblocker.data.ItemLocks
import com.appblocker.data.ItemLocks.Lock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariant 95: Strict Mode for one thing at a time — "this website, 50 days", everything else free.
 */
class ItemLocksTest {

    private val day = 24 * 60 * 60_000L
    private val boot = 7
    private val rt = 1_000_000L
    private val wall = 1_790_000_000_000L

    private fun remaining(locks: List<Lock>, key: String, afterMs: Long = 0L): Long =
        locks.first { it.key == key }.let { ItemLocks.remainingAt(it, boot, rt + afterMs, wall + afterMs) }

    // ---- the write rule ---------------------------------------------------------------------

    @Test
    fun `a locked item cannot be taken off`() {
        val kept = ItemLocks.keepLocked(
            current = setOf("pornhub", "reddit"),
            target = setOf("reddit"),
            locked = setOf("pornhub"),
        )
        assertEquals(setOf("pornhub", "reddit"), kept)
    }

    @Test
    fun `everything that is not locked stays free`() {
        val kept = ItemLocks.keepLocked(
            current = setOf("pornhub", "reddit"),
            target = setOf("pornhub", "youtube"),
            locked = setOf("pornhub"),
        )
        assertEquals(setOf("pornhub", "youtube"), kept)
    }

    @Test
    fun `a lock on something not there adds nothing`() {
        assertEquals(setOf("a"), ItemLocks.keepLocked(setOf("a"), setOf("a"), locked = setOf("gone")))
    }

    // ---- lengthen, never shorten -------------------------------------------------------------

    @Test
    fun `a new lock runs for the days chosen`() {
        val locks = ItemLocks.withLock(emptyList(), ItemLocks.Kind.WORD, "pornhub", 50 * day, boot, rt, wall)
        assertEquals(50 * day, remaining(locks, "pornhub"))
        assertEquals(0L, remaining(locks, "pornhub", afterMs = 50 * day))
    }

    @Test
    fun `a lock can be made longer`() {
        val first = ItemLocks.withLock(emptyList(), ItemLocks.Kind.WORD, "x", 7 * day, boot, rt, wall)
        val longer = ItemLocks.withLock(first, ItemLocks.Kind.WORD, "x", 30 * day, boot, rt, wall)
        assertEquals(1, longer.size)
        assertEquals(30 * day, remaining(longer, "x"))
    }

    @Test
    fun `a lock can never be made shorter`() {
        val first = ItemLocks.withLock(emptyList(), ItemLocks.Kind.APP, "com.x", 50 * day, boot, rt, wall)
        val shorter = ItemLocks.withLock(first, ItemLocks.Kind.APP, "com.x", 1 * day, boot, rt, wall)
        assertEquals(50 * day, remaining(shorter, "com.x"))
    }

    @Test
    fun `an app and a word with the same name are separate locks`() {
        val a = ItemLocks.withLock(emptyList(), ItemLocks.Kind.APP, "x", day, boot, rt, wall)
        val both = ItemLocks.withLock(a, ItemLocks.Kind.WORD, "x", day, boot, rt, wall)
        assertEquals(2, both.size)
    }

    @Test
    fun `ended locks are dropped on the next write`() {
        val old = ItemLocks.withLock(emptyList(), ItemLocks.Kind.WORD, "old", day, boot, rt, wall)
        val next = ItemLocks.withLock(old, ItemLocks.Kind.WORD, "new", day, boot, rt + 2 * day, wall + 2 * day)
        assertEquals(listOf("new"), next.map { it.key })
    }

    /** The same clock rules as Strict: moving the phone's clock does not end a lock within a boot. */
    @Test
    fun `moving the wall clock forward does not end a lock`() {
        val locks = ItemLocks.withLock(emptyList(), ItemLocks.Kind.WORD, "x", 50 * day, boot, rt, wall)
        val lock = locks.single()
        assertEquals(50 * day, ItemLocks.remainingAt(lock, boot, rt, wall + 100 * day))
    }

    // ---- storage ------------------------------------------------------------------------------

    @Test
    fun `locks survive encode and decode, odd characters included`() {
        val locks = ItemLocks.withLock(emptyList(), ItemLocks.Kind.WORD, "a|b\nc نيك", 3 * day, boot, rt, wall) +
            ItemLocks.withLock(emptyList(), ItemLocks.Kind.APP, "com.instagram.android", day, boot, rt, wall)
        assertEquals(locks, ItemLocks.decode(ItemLocks.encode(locks)))
    }

    @Test
    fun `a damaged line is skipped, not fatal`() {
        val good = ItemLocks.withLock(emptyList(), ItemLocks.Kind.WORD, "x", day, boot, rt, wall)
        val raw = ItemLocks.encode(good) + "\nnonsense|1|2\nmovie|x|1|2|3|4|5"
        assertEquals(good, ItemLocks.decode(raw))
        assertTrue(ItemLocks.decode("").isEmpty())
    }
}
