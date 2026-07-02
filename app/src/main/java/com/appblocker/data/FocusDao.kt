package com.appblocker.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FocusDao {

    @Query("SELECT * FROM focus_state WHERE id = 0")
    fun get(): Flow<FocusState?>

    @Upsert
    suspend fun set(state: FocusState)
}
