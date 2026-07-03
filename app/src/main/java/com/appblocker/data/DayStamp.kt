package com.appblocker.data

import java.util.Calendar

/** A stable integer for the current calendar day (yyyy * 1000 + dayOfYear). */
internal fun todayStamp(): Int {
    val c = Calendar.getInstance()
    return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
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
