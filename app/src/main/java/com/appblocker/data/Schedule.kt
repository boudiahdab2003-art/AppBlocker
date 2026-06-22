package com.appblocker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A schedule blocks its chosen apps when its trigger condition is met. */
enum class ScheduleType { TIME, USAGE_LIMIT, LAUNCH_COUNT, WIFI, LOCATION }

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
    // LAUNCH_COUNT: allowed opens per day before blocking.
    val limitCount: Int = 5,
    // WIFI: target network name; empty = any Wi-Fi.
    val wifiSsid: String = "",
    // LOCATION: block within radiusMeters of this point.
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val radiusMeters: Int = 150,
    // Which apps this schedule applies to.
    val packages: List<String> = emptyList(),
)
