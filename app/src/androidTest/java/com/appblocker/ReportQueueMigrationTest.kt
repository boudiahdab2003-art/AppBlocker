package com.appblocker

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblocker.data.BugReport
import com.appblocker.data.BugReportQueue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Moving the report queue out of the app's main preferences file, without losing what is in it.**
 *
 * A report grew from a few hundred bytes to a few kilobytes — settings table, block log, stoppage
 * history, health verdicts — and up to twenty can be waiting at once. Android loads a preferences
 * file whole into memory on first touch, so leaving that in `appblocker_prefs` meant every launch
 * parsing the backlog before it could read a single setting. Hence its own file.
 *
 * **The migration is the risky part, not the move.** On the owner's phone that backlog is not
 * hypothetical: the delivery route has been down since 26 Aug 2026, so his queue is the evidence
 * we have been trying to get. Dropping it on update would destroy exactly the thing being
 * investigated. And the *sent keys* matter in the opposite direction — they are what stops a
 * report being filed twice, so losing them re-files the device profile for every build the phone
 * has ever run, the next time he opens the app.
 *
 * A device test because SharedPreferences is the thing under test; a JVM test would be testing a
 * mock of the exact API whose behaviour is in question.
 *
 * ⚠️ Written but **not run locally** — there was no device or emulator in the session that added
 * it. It runs in CI and as the release gate. If it fails there, believe it over this comment.
 */
@RunWith(AndroidJUnit4::class)
class ReportQueueMigrationTest {

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val legacy get() = ctx.getSharedPreferences("appblocker_prefs", Context.MODE_PRIVATE)
    private val queueFile get() = ctx.getSharedPreferences("appblocker_reports", Context.MODE_PRIVATE)

    private fun report(note: String) = BugReport.fromNote(
        note = note,
        appVersion = "1.145",
        flavor = "github",
        androidSdk = 36,
        device = "Xiaomi 25080RABDG",
        context = mapOf("noteAt" to "1756000000", "serviceOn" to "true"),
    )

    @Before
    @After
    fun clean() {
        queueFile.edit().clear().commit()
        legacy.edit()
            .remove("bugreport_pending").remove("bugreport_sent_keys")
            .remove("bugreport_sent_day").remove("bugreport_sent_today")
            .commit()
        resetMigrationFlag()
    }

    /**
     * The one-shot guard is process-wide, so each case has to put it back or only the first test
     * in the class would exercise a migration at all — a suite that silently stops testing after
     * its first case is worse than no suite.
     */
    private fun resetMigrationFlag() {
        val f = BugReportQueue::class.java.getDeclaredField("migrated")
        f.isAccessible = true
        f.setBoolean(BugReportQueue, false)
    }

    @Test
    fun undeliveredReportsSurviveTheMoveToTheirOwnFile() {
        legacy.edit()
            .putString("bugreport_pending", BugReportQueue.encode(listOf(report("it stopped again"))))
            .commit()
        resetMigrationFlag()

        val carried = BugReportQueue.pending(ctx)

        assertEquals(1, carried.size)
        assertEquals("it stopped again", carried.single().note)
    }

    /** Losing these re-files every profile report the phone has ever sent. */
    @Test
    fun theMemoryOfWhatWasAlreadySentSurvivesToo() {
        val alreadySent = report("sent earlier").dedupeKey()
        legacy.edit()
            .putStringSet("bugreport_sent_keys", setOf(alreadySent))
            .putString("bugreport_pending", "[]")
            .commit()
        resetMigrationFlag()

        // enqueue returns false only because the key is remembered — which is the thing being
        // asserted; a lost memory would let this through and open a duplicate issue.
        assertFalse(BugReportQueue.enqueue(ctx, report("sent earlier")))
    }

    @Test
    fun theOldKeysAreClearedSoTheMainFileActuallyShrinks() {
        legacy.edit()
            .putString("bugreport_pending", BugReportQueue.encode(listOf(report("x"))))
            .putStringSet("bugreport_sent_keys", setOf("k"))
            .commit()
        resetMigrationFlag()

        BugReportQueue.pending(ctx)

        assertFalse(legacy.contains("bugreport_pending"))
        assertFalse(legacy.contains("bugreport_sent_keys"))
    }

    /**
     * A second migration must never overwrite work done since the first. The old file is cleared
     * on the way out, so this can only happen if something re-seeds it — but the guard is cheap
     * and the cost of being wrong is the owner's backlog.
     */
    @Test
    fun aSecondMigrationCannotOverwriteNewerReports() {
        BugReportQueue.enqueue(ctx, report("the new one"))
        legacy.edit()
            .putString("bugreport_pending", BugReportQueue.encode(listOf(report("the stale one"))))
            .commit()
        resetMigrationFlag()

        val kept = BugReportQueue.pending(ctx).map { it.note }

        assertTrue(kept.toString(), kept.contains("the new one"))
        assertFalse(kept.toString(), kept.contains("the stale one"))
    }

    /** A phone that never had a legacy queue must not be disturbed by the check for one. */
    @Test
    fun aFreshInstallMigratesNothingAndStillWorks() {
        assertTrue(BugReportQueue.pending(ctx).isEmpty())
        assertTrue(BugReportQueue.enqueue(ctx, report("first ever")))
        assertEquals(1, BugReportQueue.pending(ctx).size)
    }
}
