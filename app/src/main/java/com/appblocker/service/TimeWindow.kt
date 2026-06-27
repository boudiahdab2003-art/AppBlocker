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
