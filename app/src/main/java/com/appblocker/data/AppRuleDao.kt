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

    /** One rule, or null if the app has never been configured. Used before auto-adding a newly
     *  installed app, so a reinstall keeps the mode and limit the owner chose rather than being
     *  reset to a plain block. */
    @Query("SELECT * FROM app_rules WHERE packageName = :packageName LIMIT 1")
    suspend fun get(packageName: String): AppRule?

    @Upsert
    suspend fun upsert(rule: AppRule)

    /** Upserts many rules in a single transaction (used when committing a Quick Block selection). */
    @Upsert
    suspend fun upsertAll(rules: List<AppRule>)

    @Delete
    suspend fun delete(rule: AppRule)
}
