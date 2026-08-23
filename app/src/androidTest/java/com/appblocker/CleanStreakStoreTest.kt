package com.appblocker

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblocker.data.CleanStreak
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The stored half of the clean counter — the part `CleanStreakTest` structurally cannot reach.
 *
 * The arithmetic is pure and unit-tested. What is **not** pure is everything that makes the
 * promises on the screen true: that a relapse banks the run it ends, that the dates accumulate in
 * a preference string of JSON, and above all that **Undo puts back exactly what was there** — the
 * same start, the same record, the date gone from the history. That last one is the safety net in
 * front of the most valuable number in the app, and until now nothing tested it.
 *
 * A device test rather than a Robolectric one because SharedPreferences and `org.json` are the two
 * things being exercised; mocking either would test the mock.
 *
 * **This writes to the app's own preferences on the device under test** and clears them before and
 * after. Rendering tests run on the emulator and in CI, never on the owner's phone.
 */
@RunWith(AndroidJUnit4::class)
class CleanStreakStoreTest {

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val day = 86_400_000L
    private val hour = 3_600_000L

    @Before
    @After
    fun emptyTheStore() {
        ctx.getSharedPreferences("recovery", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun aFreshInstallIsNotCountingAnything() {
        assertFalse(CleanStreak.isRunning(ctx))
        assertEquals(0L, CleanStreak.startedAt(ctx))
        assertEquals(0L, CleanStreak.bestMs(ctx))
        assertEquals(emptyList<Long>(), CleanStreak.resets(ctx))
        assertFalse(CleanStreak.canUndo(ctx))
    }

    @Test
    fun startingItSticks() {
        val now = System.currentTimeMillis()
        CleanStreak.setStart(ctx, now - 3 * day, now)

        assertTrue(CleanStreak.isRunning(ctx))
        assertEquals(3 * day, CleanStreak.elapsedMs(ctx, now))
    }

    /** The first-ever relapse must not invent a record out of the run that never existed. */
    @Test
    fun aFirstRelapseBanksNoRecord() {
        val now = System.currentTimeMillis()
        CleanStreak.relapse(ctx, at = now, now = now)

        assertEquals(0L, CleanStreak.bestMs(ctx))
        assertEquals(1, CleanStreak.resets(ctx).size)
    }

    @Test
    fun aRelapseBanksTheRunItEndsAndStartsTheNextOne() {
        val now = System.currentTimeMillis()
        CleanStreak.setStart(ctx, now - 20 * day, now)
        CleanStreak.relapse(ctx, at = now, now = now)

        assertEquals(20 * day, CleanStreak.bestMs(ctx))
        assertEquals(now, CleanStreak.startedAt(ctx))
        assertEquals(listOf(now), CleanStreak.resets(ctx))
    }

    /** A shorter run afterwards must not overwrite the longer one. */
    @Test
    fun theRecordIsTheLongestRunNotTheLastOne() {
        val now = System.currentTimeMillis()
        CleanStreak.setStart(ctx, now - 30 * day, now)
        CleanStreak.relapse(ctx, at = now - 10 * day, now = now - 10 * day)
        CleanStreak.relapse(ctx, at = now, now = now)

        assertEquals(20 * day, CleanStreak.bestMs(ctx))
        assertEquals(2, CleanStreak.resets(ctx).size)
    }

    // ─────────────────────────────────────────────────────── the safety net

    /**
     * **The promise the screen makes, tested end to end.** A mis-tap costs nothing: the start, the
     * record and the history all come back exactly as they were.
     */
    @Test
    fun undoPutsBackTheExactRunThatWasLost() {
        val now = System.currentTimeMillis()
        val originalStart = now - 40 * day
        CleanStreak.setStart(ctx, originalStart, now)

        CleanStreak.relapse(ctx, at = now, now = now)
        assertEquals(now, CleanStreak.startedAt(ctx))
        assertEquals(40 * day, CleanStreak.bestMs(ctx))

        CleanStreak.undo(ctx, now)

        assertEquals("the start was not restored", originalStart, CleanStreak.startedAt(ctx))
        assertEquals("the record was not un-banked", 0L, CleanStreak.bestMs(ctx))
        assertEquals("the date was left in the history", emptyList<Long>(), CleanStreak.resets(ctx))
        assertFalse("the same reset can be undone twice", CleanStreak.canUndo(ctx, now))
    }

    /** Undoing the first-ever relapse has to restore "never started", not leave a count running
     *  from zero — the case an `if (previous > 0)` guard would quietly get wrong. */
    @Test
    fun undoingAFirstRelapseGoesBackToNotCounting() {
        val now = System.currentTimeMillis()
        CleanStreak.relapse(ctx, at = now, now = now)
        assertTrue(CleanStreak.isRunning(ctx))

        CleanStreak.undo(ctx, now)

        assertFalse(CleanStreak.isRunning(ctx))
        assertEquals(0L, CleanStreak.startedAt(ctx))
    }

    /** An earlier reset must survive undoing a later one — the history is a stack, not a flag. */
    @Test
    fun undoOnlyTakesBackTheMostRecentReset() {
        val now = System.currentTimeMillis()
        CleanStreak.setStart(ctx, now - 30 * day, now)
        val firstReset = now - 10 * day
        CleanStreak.relapse(ctx, at = firstReset, now = firstReset)
        CleanStreak.relapse(ctx, at = now, now = now)

        CleanStreak.undo(ctx, now)

        assertEquals(listOf(firstReset), CleanStreak.resets(ctx))
        assertEquals(firstReset, CleanStreak.startedAt(ctx))
    }

    @Test
    fun theOfferExpiresAfterADay() {
        val now = System.currentTimeMillis()
        CleanStreak.setStart(ctx, now - 5 * day, now)
        CleanStreak.relapse(ctx, at = now, now = now)

        assertTrue(CleanStreak.canUndo(ctx, now + 23 * hour))
        assertFalse(CleanStreak.canUndo(ctx, now + 25 * hour))

        // …and calling it anyway changes nothing.
        CleanStreak.undo(ctx, now + 25 * hour)
        assertEquals(now, CleanStreak.startedAt(ctx))
    }

    /** Correcting the start is a different action from a relapse, and must not leave a stale undo
     *  standing that would wind the count back to a run the owner has just edited away. */
    @Test
    fun correctingTheStartClearsAPendingUndo() {
        val now = System.currentTimeMillis()
        CleanStreak.setStart(ctx, now - 9 * day, now)
        CleanStreak.relapse(ctx, at = now, now = now)
        assertTrue(CleanStreak.canUndo(ctx, now))

        CleanStreak.setStart(ctx, now - 2 * hour, now)

        assertFalse(CleanStreak.canUndo(ctx, now))
    }

    /** The journal's margin marker reads this. */
    @Test
    fun theDaysAResetHappenedOnAreReadable() {
        val now = System.currentTimeMillis()
        CleanStreak.setStart(ctx, now - 4 * day, now)
        CleanStreak.relapse(ctx, at = now, now = now)

        assertEquals(setOf(com.appblocker.data.todayStamp()), CleanStreak.resetDays(ctx))
    }
}
