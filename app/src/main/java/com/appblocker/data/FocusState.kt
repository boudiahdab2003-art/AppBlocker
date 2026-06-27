package com.appblocker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row table holding the current Focus session. A session is active while
 * time is left. Focus sessions are un-stoppable: there is no way to clear this
 * early — the watcher enforces it until the time passes.
 *
 * The deadline is anchored to the monotonic clock ([realtimeEndMillis]) so it can't
 * be defeated by changing the device clock. [endTimeMillis] (wall clock) is kept as a
 * fallback for after a reboot, when the monotonic clock resets. See [SessionClock].
 */
@Entity(tableName = "focus_state")
data class FocusState(
    @PrimaryKey val id: Int = 0,
    val endTimeMillis: Long = 0L,        // wall-clock deadline (reboot/legacy fallback)
    val realtimeStartMillis: Long = 0L,  // SystemClock.elapsedRealtime() at session start
    val realtimeEndMillis: Long = 0L,    // monotonic deadline (clock-change-proof)
)
