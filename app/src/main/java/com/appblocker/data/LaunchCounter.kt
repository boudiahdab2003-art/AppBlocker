package com.appblocker.data

import android.content.Context

/** Counts how many times each app was opened today (for LAUNCH_COUNT schedules). */
object LaunchCounter {
    private const val PREFS = "launch_counts"

    /** Record one open of [pkg] and return today's count. */
    fun recordOpen(context: Context, pkg: String): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = todayStamp()
        val storedDay = prefs.getInt("day_$pkg", -1)
        val count = if (storedDay == today) prefs.getInt("count_$pkg", 0) + 1 else 1
        prefs.edit().putInt("count_$pkg", count).putInt("day_$pkg", today).apply()
        return count
    }

    fun opensToday(context: Context, pkg: String): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.getInt("day_$pkg", -1) == todayStamp()) prefs.getInt("count_$pkg", 0) else 0
    }
}
