package com.appblocker.data

import android.content.Context

/**
 * Counts how many notifications arrived each day, for the Insights "Distractions" view.
 * Stored in SharedPreferences keyed by day-stamp (a short rolling history, pruned after ~35
 * days). Recording only happens while the optional Notification Access permission is granted
 * and [com.appblocker.service.NotificationCountListener] is bound.
 */
object NotificationCounter {
    private const val PREFS = "notification_counts"
    private const val KEEP_DAYS = 35

    /** Record one notification and return today's running count. */
    fun recordNotification(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = todayStamp()
        val count = prefs.getInt(key(today), 0) + 1
        val editor = prefs.edit().putInt(key(today), count)
        prefs.all.keys.forEach { k ->
            val day = k.removePrefix("day_").toIntOrNull() ?: return@forEach
            if (today - day > KEEP_DAYS) editor.remove(k)
        }
        editor.apply()
        return count
    }

    fun notificationsToday(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(key(todayStamp()), 0)
    }

    private fun key(dayStamp: Int) = "day_$dayStamp"
}
