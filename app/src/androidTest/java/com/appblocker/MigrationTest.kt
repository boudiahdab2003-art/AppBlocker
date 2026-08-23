package com.appblocker

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblocker.data.BlockerDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **The database survives the update.**
 *
 * `app/build.gradle.kts` has said "export Room schemas so future DB migrations can be authored and
 * tested" since the schemas were turned on, and until now no migration had ever actually been
 * tested. Five of them shipped on reasoning alone.
 *
 * That was survivable while every migration was `ALTER TABLE … ADD COLUMN`. It stops being
 * survivable here, because getting one wrong does not produce a crash report — Room refuses to open
 * the database, and the app that will not open is the app that was holding every block the owner
 * relies on, plus a journal with no copy anywhere else.
 *
 * `runMigrationsAndValidate` compares the migrated database against the exported `11.json`. It
 * compares the **parsed** schema rather than the SQL text, so what it catches is a missing or extra
 * column, a wrong type, wrong nullability or the wrong primary key — not a different spelling of
 * the same thing. Both were tried against this class to establish which: writing the key inline as
 * `INTEGER PRIMARY KEY NOT NULL` passes, dropping `updatedAt` fails with "Migration didn't properly
 * handle: journal_entries".
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val testDb = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BlockerDatabase::class.java,
    )

    /**
     * A v10 database with a real blocked app in it, taken to v11.
     *
     * The row matters as much as the schema: the question an owner would ask is not "did the table
     * appear" but "are my blocks still there afterwards".
     */
    @Test
    fun aBlockedAppSurvivesTheJournalBeingAdded() {
        helper.createDatabase(testDb, 10).use { db ->
            db.execSQL(
                "INSERT INTO app_rules (packageName, appLabel, isBlocked, isAllowed, mode, " +
                    "scheduleStartMinutes, scheduleEndMinutes, dailyLimitMinutes) " +
                    "VALUES ('com.instagram.android', 'Instagram', 1, 0, 'ALWAYS', 0, 0, 0)"
            )
            db.execSQL("INSERT INTO blocked_keywords (keyword) VALUES ('example')")
        }

        val db = helper.runMigrationsAndValidate(
            testDb, 11, true, BlockerDatabase.MIGRATION_10_11,
        )

        db.query("SELECT appLabel, isBlocked FROM app_rules").use { c ->
            assertTrue("the blocked app is gone after the migration", c.moveToFirst())
            assertEquals("Instagram", c.getString(0))
            assertEquals(1, c.getInt(1))
        }
        db.query("SELECT keyword FROM blocked_keywords").use { c ->
            assertTrue("the blocked word is gone after the migration", c.moveToFirst())
            assertEquals("example", c.getString(0))
        }
    }

    /** …and the new table is not merely present but writable, with the day as its identity: two
     *  entries for one day must be one row, or a diary would silently keep duplicates. */
    @Test
    fun theJournalTableAcceptsOneEntryPerDay() {
        helper.createDatabase(testDb, 10).close()
        val db = helper.runMigrationsAndValidate(
            testDb, 11, true, BlockerDatabase.MIGRATION_10_11,
        )

        db.execSQL(
            "INSERT OR REPLACE INTO journal_entries (day, text, createdAt, updatedAt) " +
                "VALUES (2026235, 'first', 1, 1)"
        )
        db.execSQL(
            "INSERT OR REPLACE INTO journal_entries (day, text, createdAt, updatedAt) " +
                "VALUES (2026235, 'second', 1, 2)"
        )

        db.query("SELECT text FROM journal_entries WHERE day = 2026235").use { c ->
            assertEquals("a day must hold exactly one entry", 1, c.count)
            assertTrue(c.moveToFirst())
            assertEquals("second", c.getString(0))
        }
    }
}
