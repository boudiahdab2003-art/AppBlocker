package com.appblocker.data

import android.content.Context

/** Counts how many times each app was opened today (for LAUNCH_COUNT schedules). */
object LaunchCounter {
    private const val PREFS = "launch_counts"
    private const val KEEP_DAYS = 35

    /** Record one open of [pkg] and return today's count. */
    fun recordOpen(context: Context, pkg: String): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = todayStamp()
        val storedDay = prefs.getInt("day_$pkg", -1)
        val count = if (storedDay == today) prefs.getInt("count_$pkg", 0) + 1 else 1
        val editor = prefs.edit().putInt("count_$pkg", count).putInt("day_$pkg", today)
        // On this app's first open of a new day (~once/day), drop apps not opened in
        // ~KEEP_DAYS so the file doesn't grow forever (e.g. long-uninstalled apps).
        if (storedDay != today) {
            prefs.all.forEach { (key, value) ->
                if (key.startsWith("day_") && value is Int && today - value > KEEP_DAYS) {
                    val stale = key.removePrefix("day_")
                    editor.remove("day_$stale").remove("count_$stale")
                }
            }
        }
        editor.apply()
        return count
    }

    fun opensToday(context: Context, pkg: String): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.getInt("day_$pkg", -1) == todayStamp()) prefs.getInt("count_$pkg", 0) else 0
    }

    /** Today's open count for every app that has been opened today (package -> count). */
    fun opensTodayByApp(context: Context): Map<String, Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = todayStamp()
        val result = HashMap<String, Int>()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("count_") && value is Int) {
                val pkg = key.removePrefix("count_")
                if (prefs.getInt("day_$pkg", -1) == today && value > 0) result[pkg] = value
            }
        }
        return result
    }
}
