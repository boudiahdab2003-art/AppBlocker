package com.appblocker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row table holding the current Focus session. endTimeMillis in the
 * future means a session is active. Focus sessions are un-stoppable: there is
 * no way to clear this early — the watcher enforces it until the time passes.
 */
@Entity(tableName = "focus_state")
data class FocusState(
    @PrimaryKey val id: Int = 0,
    val endTimeMillis: Long = 0L,
)
