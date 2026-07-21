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

    /** Atomically clears only Strict state created before [currentVersion]. */
    @Query(
        "UPDATE focus_state SET endTimeMillis = 0, realtimeStartMillis = 0, " +
            "realtimeEndMillis = 0, startTimeMillis = 0, bootCount = -1, " +
            "appVersionCode = -1 WHERE id = 0 AND appVersionCode < :currentVersion"
    )
    suspend fun clearStrictSessionCreatedBefore(currentVersion: Long): Int
}
