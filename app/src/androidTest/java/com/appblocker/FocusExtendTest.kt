package com.appblocker

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblocker.data.BlockerDatabase
import com.appblocker.data.FocusDao
import com.appblocker.data.FocusState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Strict Mode's central promise, tested where it actually lives.**
 *
 * A Strict session cannot be cut short — that is the entire product. Adding "add more time" put a
 * fourth writer on the `focus_state` row, and the other three include two that write a fully
 * zeroed row the instant a session expires (`FocusViewModel`'s ticker and the watcher's
 * `focusClearRunnable`). A read-modify-write extend would race both, and the race is not
 * symmetrical: losing it one way loses an extension the user asked for; losing it the other way
 * **brings a finished session back to life**, which is exactly what the zeroing exists to prevent.
 *
 * So the arithmetic and both invariants live in one SQL statement in [FocusDao.extend], and this
 * is the only layer that can prove them — a JVM test cannot run SQLite, and no unit test in this
 * project constructs a [FocusState] at all. `SessionClockTest` covers the *other* half (that
 * bumping these two fields is the right choice); this covers that the bump is the only thing that
 * can happen.
 *
 * An in-memory database, so it never touches the owner's real session on the test device.
 */
@RunWith(AndroidJUnit4::class)
class FocusExtendTest {

    private lateinit var db: BlockerDatabase
    private lateinit var dao: FocusDao

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, BlockerDatabase::class.java).build()
        dao = db.focusDao()
    }

    @After
    fun close() = db.close()

    /** A running session: one hour left, anchored on both clocks, on a known boot. */
    private fun running(
        wallEnd: Long = 3_600_000L,
        realtimeEnd: Long = 3_600_000L,
    ) = FocusState(
        id = 0,
        endTimeMillis = wallEnd,
        realtimeStartMillis = 1_000L,
        realtimeEndMillis = realtimeEnd,
        startTimeMillis = 1_000L,
        bootCount = 7,
        appVersionCode = 121L,
    )

    @Test
    fun extendingMovesBothDeadlinesByTheSameAmount() = runBlocking {
        dao.set(running())

        dao.extend(600_000L)

        val after = current()
        assertEquals(3_600_000L + 600_000L, after.endTimeMillis)
        assertEquals(3_600_000L + 600_000L, after.realtimeEndMillis)
    }

    /**
     * Both, or neither. [com.appblocker.data.SessionClock] reads the monotonic deadline within a
     * boot and the wall-clock one after a reboot, so an extension that moved only one would work
     * until the phone restarted and then silently vanish.
     */
    @Test
    fun startAnchorsAndBootCountAreUntouched() = runBlocking {
        val before = running()
        dao.set(before)

        dao.extend(600_000L)

        val after = current()
        assertEquals(before.realtimeStartMillis, after.realtimeStartMillis)
        assertEquals(before.startTimeMillis, after.startTimeMillis)
        assertEquals(before.bootCount, after.bootCount)
        assertEquals(before.appVersionCode, after.appVersionCode)
    }

    /** The whole promise in one test: the deadline may not move backwards, ever. */
    @Test
    fun aNegativeExtensionCannotShortenTheSession() = runBlocking {
        dao.set(running())

        dao.extend(-600_000L)

        val after = current()
        assertEquals(3_600_000L, after.endTimeMillis)
        assertEquals(3_600_000L, after.realtimeEndMillis)
    }

    @Test
    fun aZeroExtensionChangesNothing() = runBlocking {
        dao.set(running())

        dao.extend(0L)

        assertEquals(3_600_000L, current().endTimeMillis)
    }

    /**
     * The dangerous race, made impossible. If a clear lands first, the row is zeroed — and an
     * extend arriving a moment later must not turn that back into a live session, because from the
     * user's point of view Strict Mode already ended and their blocks are already unlocked.
     */
    @Test
    fun anExpiredZeroedSessionIsNotResurrected() = runBlocking {
        dao.set(FocusState(id = 0)) // what both clear paths write

        dao.extend(600_000L)

        val after = current()
        assertEquals(0L, after.endTimeMillis)
        assertEquals(0L, after.realtimeEndMillis)
    }

    /**
     * The return value is not decoration — `FocusViewModel` records Strict minutes to the
     * statistics only when this says the row moved. A session can expire between the button being
     * drawn and the tap landing, or while the duration picker is open, and without this the
     * statistic would report time that was never added.
     */
    @Test
    fun theRowCountSaysWhetherTheExtensionApplied() = runBlocking {
        dao.set(running())
        assertEquals(1, dao.extend(600_000L))

        assertEquals(0, dao.extend(0L))
        assertEquals(0, dao.extend(-1L))

        dao.set(FocusState(id = 0))
        assertEquals(0, dao.extend(600_000L))
    }

    @Test
    fun extensionsCompound() = runBlocking {
        dao.set(running())

        dao.extend(600_000L)
        dao.extend(900_000L)

        assertEquals(3_600_000L + 1_500_000L, current().endTimeMillis)
    }

    /** Reads the single row. Room exposes it as a Flow; the first emission is the settled state. */
    private suspend fun current(): FocusState = db.focusDao().get().first()!!
}
