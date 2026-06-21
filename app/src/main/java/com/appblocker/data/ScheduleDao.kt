package com.appblocker.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules ORDER BY id")
    fun getAll(): Flow<List<Schedule>>

    @Upsert
    suspend fun upsert(schedule: Schedule): Long

    @Delete
    suspend fun delete(schedule: Schedule)
}
