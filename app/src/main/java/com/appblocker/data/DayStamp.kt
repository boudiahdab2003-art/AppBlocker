package com.appblocker.data

import java.util.Calendar

/** A stable integer for the current calendar day (yyyy * 1000 + dayOfYear). */
internal fun todayStamp(): Int {
    val c = Calendar.getInstance()
    return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
}
