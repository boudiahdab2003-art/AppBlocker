package com.appblocker.data

import java.util.Calendar

/** A stable integer for the current calendar day (yyyy * 1000 + dayOfYear). */
internal fun todayStamp(): Int {
    val c = Calendar.getInstance()
    return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
}

/**
 * How many calendar days [older] falls before [newer].
 *
 * A day-stamp is NOT a linear day count, and subtracting two of them is only meaningful inside
 * one year: 31 Dec 2026 is 2026365 and 1 Jan 2027 is 2027001, a difference of 636 for one day
 * apart. Several stores used `today - stamp > KEEP_DAYS` to prune old data, which silently meant
 * "delete everything" for the whole of January, and [MoodStore] used `today - ago` to *read*
 * history, which pointed at day-stamps that never existed.
 *
 * (A DST change can move the answer by a day either way, since the stamps are anchored to local
 * midnight. That is irrelevant at the ~35-day scale these are used for.)
 */
internal fun dayGap(newer: Int, older: Int): Int = dayOrdinal(newer) - dayOrdinal(older)

/** The day-stamp [ago] days before [day], crossing the year boundary correctly. */
internal fun stampDaysAgo(day: Int, ago: Int): Int {
    if (ago <= 0) return day
    val c = dayCalendar(day)
    c.add(Calendar.DAY_OF_YEAR, -ago)
    return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
}

/** A day-stamp as an absolute day number, so two of them can be compared or subtracted.
 *  Kotlin's floorDiv rather than Math's, so it doesn't depend on an Android API level. */
private fun dayOrdinal(day: Int): Int =
    dayCalendar(day).timeInMillis.floorDiv(86_400_000L).toInt()

/** Local midnight of the day a stamp names. */
private fun dayCalendar(day: Int): Calendar = Calendar.getInstance().apply {
    clear()
    set(Calendar.YEAR, day / 1000)
    set(Calendar.DAY_OF_YEAR, day % 1000)
}

/** The day-stamp immediately before [day], handling the year boundary. */
internal fun prevDayStamp(day: Int): Int {
    val year = day / 1000
    val doy = day % 1000
    if (doy > 1) return year * 1000 + (doy - 1)
    val c = Calendar.getInstance()
    c.set(Calendar.YEAR, year - 1)
    c.set(Calendar.MONTH, Calendar.DECEMBER)
    c.set(Calendar.DAY_OF_MONTH, 31)
    return (year - 1) * 1000 + c.get(Calendar.DAY_OF_YEAR)
}

/**
 * The day-stamp an absolute time falls on — for anything recorded with a wall-clock timestamp
 * rather than on the day it happened, which is what the journal and [CleanStreak] both need.
 */
internal fun dayStampOf(millis: Long): Int {
    val c = Calendar.getInstance()
    c.timeInMillis = millis
    return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
}

/** Local midnight of the day a stamp names, as epoch millis — what a date formatter wants. */
internal fun dayStartMillis(day: Int): Long = dayCalendar(day).timeInMillis
