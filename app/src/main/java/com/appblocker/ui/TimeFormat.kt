package com.appblocker.ui

/**
 * Every user-facing time string in the app: schedule times and windows, durations and
 * countdowns. Stored values stay as minutes/millis — this is display only.
 *
 * Keeping them together is what stops the same "23h 40m" from being written four slightly
 * different ways across four screens.
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

/**
 * A day-of-week bitmask (Sunday = bit 0) as "Every day", "Mon–Fri" or "Mo We Fr".
 * Used by both the schedule list and template cards — they used to carry their own copies,
 * and only one of them knew about "Mon–Fri".
 */
internal fun daysText(mask: Int): String {
    if (mask and 0b1111111 == 0b1111111) return "Every day"
    if (mask == 0b0111110) return "Mon–Fri"
    val labels = listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")
    return (0..6).filter { (mask shr it) and 1 == 1 }.joinToString(" ") { labels[it] }
        .ifEmpty { "No days" }
}

/** A whole-minute duration as e.g. "25 min" or "1 hr 30 min". */
internal fun fmtDuration(minutes: Int): String {
    val h = minutes / 60; val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "$h hr $m min"
        h > 0 -> "$h hr"
        else -> "$m min"
    }
}

/**
 * A running countdown: **`24d 15h` from a day up**, `H:MM:SS` from an hour, else `M:SS`.
 * [padMinutes] pads the minutes to two digits (00:59), which is what the Strict Mode countdown
 * uses so its big timer doesn't change width as it ticks.
 *
 * **The day band is not a nicety — without it this had no upper bound.** The duration wheel offers
 * up to 30 days, and the header pill spent a 24-day session reading `35507:29`: eight glyphs of
 * minutes, wrapped onto two lines and spilling out of a rounded pill the owner then reported as
 * "very ugly". The same string is the Strict screen's headline at **56sp**, where it would have
 * read `591:47:29`. Both were the same missing branch.
 *
 * Seconds are dropped past a day deliberately. They are noise at that scale — nobody watches the
 * last second of three weeks — and dropping them is what keeps the output to seven glyphs so it
 * fits the pill and the 56sp number alike. The widest this can now return is eight characters
 * (`23:59:59`, `30d 23h`), which is the property `TimeFormatTest` pins.
 */
internal fun fmtCountdown(ms: Long, padMinutes: Boolean = false): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    val d = total / 86_400
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return when {
        d > 0 -> "%dd %dh".format(d, h % 24)
        h > 0 -> "%d:%02d:%02d".format(h, m, s)
        padMinutes -> "%02d:%02d".format(m, s)
        else -> "%d:%02d".format(m, s)
    }
}

/** A coarse countdown for long waits: "23h 40m" / "35m", rounded up so it never reads 0m. */
internal fun fmtHoursMinutes(ms: Long): String {
    val m = (ms.coerceAtLeast(0L) + 59_999) / 60_000
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}
