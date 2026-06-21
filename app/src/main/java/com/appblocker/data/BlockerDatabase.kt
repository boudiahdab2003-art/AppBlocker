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
}

@Database(entities = [AppRule::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class BlockerDatabase : RoomDatabase() {
    abstract fun appRuleDao(): AppRuleDao

    companion object {
        @Volatile private var INSTANCE: BlockerDatabase? = null

        fun get(context: Context): BlockerDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BlockerDatabase::class.java,
                    "appblocker.db"
                ).build().also { INSTANCE = it }
            }
    }
}
