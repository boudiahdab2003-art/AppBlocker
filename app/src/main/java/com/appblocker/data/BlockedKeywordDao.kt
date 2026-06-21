package com.appblocker.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedKeywordDao {

    @Query("SELECT * FROM blocked_keywords ORDER BY keyword")
    fun getAll(): Flow<List<BlockedKeyword>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(keyword: BlockedKeyword)

    @Delete
    suspend fun delete(keyword: BlockedKeyword)
}
