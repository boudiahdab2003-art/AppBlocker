package com.appblocker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A reusable named location the user can pick when building a Location schedule. */
@Entity(tableName = "saved_places")
data class SavedPlace(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
)
