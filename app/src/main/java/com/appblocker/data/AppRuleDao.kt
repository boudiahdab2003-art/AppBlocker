package com.appblocker.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AppRuleDao {

    @Query("SELECT * FROM app_rules")
    fun getAll(): Flow<List<AppRule>>

    /** Packages currently blocked — used by the M2 accessibility watcher. */
    @Query("SELECT packageName FROM app_rules WHERE isBlocked = 1")
    fun getBlockedPackages(): Flow<List<String>>

    @Upsert
    suspend fun upsert(rule: AppRule)

    @Delete
    suspend fun delete(rule: AppRule)
}
