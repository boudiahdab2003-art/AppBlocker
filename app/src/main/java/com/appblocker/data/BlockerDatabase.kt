package com.appblocker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter fun fromMode(mode: BlockMode): String = mode.name
    @TypeConverter fun toMode(value: String): BlockMode = BlockMode.valueOf(value)

    @TypeConverter fun fromScheduleType(type: ScheduleType): String = type.name
    @TypeConverter fun toScheduleType(value: String): ScheduleType = ScheduleType.valueOf(value)

    // Package lists are stored as a newline-joined string (package names never contain \n).
    @TypeConverter fun fromPackages(list: List<String>): String = list.joinToString("\n")
    @TypeConverter fun toPackages(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split("\n")
}

@Database(
    entities = [AppRule::class, FocusState::class, BlockedKeyword::class, Schedule::class,
        SavedPlace::class, JournalEntry::class],
    version = 11,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class BlockerDatabase : RoomDatabase() {
    abstract fun appRuleDao(): AppRuleDao
    abstract fun focusDao(): FocusDao
    abstract fun blockedKeywordDao(): BlockedKeywordDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun savedPlaceDao(): SavedPlaceDao
    abstract fun journalDao(): JournalDao

    companion object {
        @Volatile private var INSTANCE: BlockerDatabase? = null

        /**
         * v5 -> v6: add realtime-anchored columns to focus_state so Strict Mode survives
         * device-clock changes. Existing rows keep realtime* = 0, which makes the watcher
         * fall back to the (still valid) wall-clock endTimeMillis for any in-flight session.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE focus_state ADD COLUMN realtimeStartMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE focus_state ADD COLUMN realtimeEndMillis INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v6 -> v7: add the saved_places table so locations can be named and reused. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS saved_places (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL)"
                )
            }
        }

        /**
         * v7 -> v8: add a wall-clock start anchor to focus_state so the post-reboot fallback
         * can reject an impossible device clock — without it, a long-finished Strict session
         * could resurrect after a reboot whose clock briefly read earlier than the old deadline.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE focus_state ADD COLUMN startTimeMillis INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v8 -> v9: add app_rules.isAllowed for Quick Block's Allowlist mode. Existing rows
         * default to 0 (not allowed) — harmless while the default Blocklist mode is active.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_rules ADD COLUMN isAllowed INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v9 -> v10: identify the boot and app version that created a Strict session. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE focus_state ADD COLUMN bootCount INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE focus_state ADD COLUMN appVersionCode INTEGER NOT NULL DEFAULT -1")
            }
        }

        /**
         * v10 -> v11: the journal — one row per calendar day, kept forever.
         *
         * Written to match `app/schemas/…/11.json` character for character, which is the least
         * surprising thing to read next to it — **but that is a convention, not the rule.** Room
         * validates the *parsed* schema, so what actually has to be right is the set of columns,
         * their types, their nullability and which ones form the primary key. Spelling the key
         * inline as `INTEGER PRIMARY KEY NOT NULL` passes; dropping a column does not, and
         * `MigrationTest` was run against both to find out rather than assuming.
         *
         * Getting it wrong is not a crash report. Room refuses to open the database, and the app
         * that will not open is the one holding every block plus a journal with no copy anywhere.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `journal_entries` (" +
                        "`day` INTEGER NOT NULL, `text` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`day`))"
                )
            }
        }

        fun get(context: Context): BlockerDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BlockerDatabase::class.java,
                    "appblocker.db"
                )
                    .addMigrations(
                        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                        MIGRATION_9_10, MIGRATION_10_11,
                    )
                    // Only wipe on a downgrade (installing an older APK) — never on upgrade.
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build().also { INSTANCE = it }
            }
    }
}
