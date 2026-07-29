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
 * Whether a TIME schedule is capable of *ever* firing.
 *
 * Two ways to save one that cannot, both reachable from the editor with no warning:
 *  - **every day deselected** — the day chips are free toggles, so the mask can reach 0;
 *  - **start equal to end** — the window is half-open, so `09:00–09:00` contains no minute at all.
 *
 * Either way the schedule sits in the list looking enabled and never blocks anything. That is the
 * invisible failure this project keeps having to hunt (docs/BLOCKING_INVARIANTS.md: over-blocking
 * is noticed instantly, under-blocking never is) — and here the owner would have every reason to
 * believe he was covered. The editor refuses to save these and says why.
 *
 * Note what this deliberately does NOT do: guess. `09:00–09:00` could mean "all day" or "never",
 * and picking one silently would be a different way to be wrong about what he asked for.
 */
internal fun timeScheduleCanFire(daysMask: Int, startMinutes: Int, endMinutes: Int): Boolean =
    (daysMask and 0b1111111) != 0 && startMinutes != endMinutes

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
