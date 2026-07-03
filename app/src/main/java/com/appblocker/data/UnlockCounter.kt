package com.appblocker.data

import android.content.Context

/**
 * Counts how many times the phone was unlocked (screen on + user present) each day, for
 * Insights "pickups". Stored in SharedPreferences keyed by day-stamp, so a short history is
 * kept; entries older than ~35 days are pruned. Recording starts the first time the service
 * registers its unlock receiver, so history builds up from then on.
 */
object UnlockCounter {
    private const val PREFS = "unlock_counts"
    private const val KEEP_DAYS = 35

    /** Record one unlock and return today's running count. */
    fun recordUnlock(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = todayStamp()
        val count = prefs.getInt(key(today), 0) + 1
        val editor = prefs.edit().putInt(key(today), count)
        // Prune old days so the file doesn't grow forever.
        prefs.all.keys.forEach { k ->
            val day = k.removePrefix("day_").toIntOrNull() ?: return@forEach
            if (today - day > KEEP_DAYS) editor.remove(k)
        }
        editor.apply()
        return count
    }

    fun unlocksToday(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(key(todayStamp()), 0)
    }

    /** Mean unlocks per recorded day, excluding today — the Focus Score's baseline. */
    fun averageUnlocksPerDay(context: Context): Float {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val todayKey = key(todayStamp())
        val past = prefs.all.filterKeys { it.startsWith("day_") && it != todayKey }
            .values.mapNotNull { it as? Int }
        return if (past.isEmpty()) 0f else past.average().toFloat()
    }

    private fun key(dayStamp: Int) = "day_$dayStamp"
}
