package com.appblocker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A word the user wants blocked anywhere it appears (web address, search, page). */
@Entity(tableName = "blocked_keywords")
data class BlockedKeyword(
    @PrimaryKey val keyword: String, // stored lowercase, trimmed
)
