package com.appblocker.ui

/**
 * User-facing formatting for schedule times, windows and durations.
 * Shared by the schedule editor, the schedule list and template cards so they all read
 * the same friendly way. Stored values stay as minutes/counts — this is display only.
 */

/** A minutes-since-midnight value as a 12-hour clock time, e.g. 540 -> "9:00 AM". */
fun fmtClock12(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    val period = if (h < 12) "AM" else "PM"
    val h12 = if (h % 12 == 0) 12 else h % 12
    return "%d:%02d %s".format(h12, m, period)
}

/** A start–end window, e.g. "9:00 AM – 5:00 PM". */
fun fmtWindow(start: Int, end: Int): String = "${fmtClock12(start)} – ${fmtClock12(end)}"

// Duration formatting lives in QuickBlockCard.kt (internal fun fmtDuration) and is reused
// here for the usage-limit chips and the schedule-list summary.
