package com.appblocker.service

/**
 * True if [nowMinutes] (minutes from midnight) falls inside the half-open window
 * [startMinutes, endMinutes). Windows where start > end wrap past midnight
 * (e.g. 22:00–06:00). Extracted as a pure function so the wrap logic is unit-testable.
 */
internal fun timeWindowContains(nowMinutes: Int, startMinutes: Int, endMinutes: Int): Boolean =
    if (startMinutes <= endMinutes) {
        nowMinutes in startMinutes until endMinutes
    } else {
        nowMinutes >= startMinutes || nowMinutes < endMinutes // wraps past midnight
    }

/**
 * Combines the selected-day and time checks for a schedule. [dayBit] is zero-based with
 * Sunday at bit 0, matching [com.appblocker.data.Schedule.daysMask].
 */
internal fun scheduleWindowContains(
    daysMask: Int,
    dayBit: Int,
    nowMinutes: Int,
    startMinutes: Int,
    endMinutes: Int,
): Boolean {
    val activeDayBit = if (startMinutes > endMinutes && nowMinutes < endMinutes) {
        (dayBit + 6) % 7
    } else {
        dayBit
    }
    return (daysMask shr activeDayBit) and 1 != 0 &&
        timeWindowContains(nowMinutes, startMinutes, endMinutes)
}
