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
        SavedPlace::class],
    version = 7,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class BlockerDatabase : RoomDatabase() {
    abstract fun appRuleDao(): AppRuleDao
    abstract fun focusDao(): FocusDao
    abstract fun blockedKeywordDao(): BlockedKeywordDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun savedPlaceDao(): SavedPlaceDao

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

        fun get(context: Context): BlockerDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BlockerDatabase::class.java,
                    "appblocker.db"
                )
                    .addMigrations(MIGRATION_5_6, MIGRATION_6_7)
                    // Only wipe on a downgrade (installing an older APK) — never on upgrade.
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build().also { INSTANCE = it }
            }
    }
}
