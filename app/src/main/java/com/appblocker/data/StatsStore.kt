package com.appblocker.data

import android.content.Context
import java.util.Calendar

/**
 * Small stats not covered by UsageStats — currently how many Strict Mode minutes were
 * started today. Stored in SharedPreferences and reset when the day changes.
 */
object StatsStore {
    private const val PREFS = "stats"

    fun addStrictMinutes(context: Context, minutes: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = dayStamp()
        val current = if (prefs.getInt("strict_day", -1) == today) prefs.getInt("strict_minutes", 0) else 0
        prefs.edit()
            .putInt("strict_minutes", current + minutes)
            .putInt("strict_day", today)
            .apply()
    }

    fun strictMinutesToday(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.getInt("strict_day", -1) == dayStamp()) prefs.getInt("strict_minutes", 0) else 0
    }

    private fun dayStamp(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }
}
