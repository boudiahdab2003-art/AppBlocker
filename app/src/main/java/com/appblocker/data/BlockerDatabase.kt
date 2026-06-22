package com.appblocker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

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
    entities = [AppRule::class, FocusState::class, BlockedKeyword::class, Schedule::class],
    version = 5,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class BlockerDatabase : RoomDatabase() {
    abstract fun appRuleDao(): AppRuleDao
    abstract fun focusDao(): FocusDao
    abstract fun blockedKeywordDao(): BlockedKeywordDao
    abstract fun scheduleDao(): ScheduleDao

    companion object {
        @Volatile private var INSTANCE: BlockerDatabase? = null

        fun get(context: Context): BlockerDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BlockerDatabase::class.java,
                    "appblocker.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}
