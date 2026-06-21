package com.appblocker.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FocusDao {

    @Query("SELECT * FROM focus_state WHERE id = 0")
    fun get(): Flow<FocusState?>

    @Query("SELECT endTimeMillis FROM focus_state WHERE id = 0")
    suspend fun endTimeOnce(): Long?

    @Upsert
    suspend fun set(state: FocusState)
}
