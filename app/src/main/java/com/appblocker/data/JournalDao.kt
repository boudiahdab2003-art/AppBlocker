package com.appblocker.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface JournalDao {
    /** Newest day first — the order the index reads in, so today is always at the top. */
    @Query("SELECT * FROM journal_entries ORDER BY day DESC")
    fun getAll(): Flow<List<JournalEntry>>

    @Query("SELECT * FROM journal_entries WHERE day = :day LIMIT 1")
    suspend fun find(day: Int): JournalEntry?

    @Upsert
    suspend fun upsert(entry: JournalEntry)

    /** By day rather than by row: an entry emptied to nothing is deleted, and the screen that
     *  does it may never have loaded the row it is removing. */
    @Query("DELETE FROM journal_entries WHERE day = :day")
    suspend fun deleteDay(day: Int)
}
