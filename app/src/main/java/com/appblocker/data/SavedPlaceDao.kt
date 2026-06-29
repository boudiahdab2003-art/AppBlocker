package com.appblocker.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedPlaceDao {
    @Query("SELECT * FROM saved_places ORDER BY name COLLATE NOCASE")
    fun getAll(): Flow<List<SavedPlace>>

    @Query("SELECT * FROM saved_places WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): SavedPlace?

    @Upsert
    suspend fun upsert(place: SavedPlace): Long

    @Delete
    suspend fun delete(place: SavedPlace)
}
