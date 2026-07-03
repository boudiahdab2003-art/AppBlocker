package com.appblocker.data

import android.content.Context

/**
 * Small stats not covered by UsageStats — currently how many Strict Mode minutes were
 * started today. Stored in SharedPreferences and reset when the day changes.
 */
object StatsStore {
    private const val PREFS = "stats"

    fun addStrictMinutes(context: Context, minutes: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = todayStamp()
        val current = if (prefs.getInt("strict_day", -1) == today) prefs.getInt("strict_minutes", 0) else 0
        prefs.edit()
            .putInt("strict_minutes", current + minutes)
            .putInt("strict_day", today)
            // Lifetime total feeds the "Deep worker" achievement (counts from v1.49 on).
            .putInt("strict_total", prefs.getInt("strict_total", 0) + minutes)
            .apply()
    }

    /** All focus-session minutes ever recorded (accumulates from v1.49 onward). */
    fun strictMinutesTotal(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("strict_total", 0)

    fun strictMinutesToday(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.getInt("strict_day", -1) == todayStamp()) prefs.getInt("strict_minutes", 0) else 0
    }
}
