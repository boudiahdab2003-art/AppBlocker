package com.appblocker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A schedule blocks its chosen apps either during a time window or past a usage limit. */
enum class ScheduleType { TIME, USAGE_LIMIT }

@Entity(tableName = "schedules")
data class Schedule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: ScheduleType,
    val enabled: Boolean = true,
    // TIME: minutes-from-midnight window + active days (7-bit mask, bit0 = Sunday).
    val startMinutes: Int = 0,
    val endMinutes: Int = 0,
    val daysMask: Int = 0b1111111,
    // USAGE_LIMIT: allowed minutes per day before blocking.
    val limitMinutes: Int = 30,
    // Which apps this schedule applies to.
    val packages: List<String> = emptyList(),
)
