package com.appblocker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** How an app is blocked. SCHEDULE/LIMIT columns are reserved for M3/M4. */
enum class BlockMode { HARD, SCHEDULE, LIMIT }

@Entity(tableName = "app_rules")
data class AppRule(
    @PrimaryKey val packageName: String,
    val appLabel: String,
    val isBlocked: Boolean = false,
    val mode: BlockMode = BlockMode.HARD,
    // Reserved for later milestones:
    val scheduleStartMinutes: Int = -1, // minutes from midnight; -1 = unset
    val scheduleEndMinutes: Int = -1,
    val dailyLimitMinutes: Int = -1,
)
