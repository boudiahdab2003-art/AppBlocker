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

    @Upsert
    suspend fun upsert(rule: AppRule)

    @Delete
    suspend fun delete(rule: AppRule)
}
