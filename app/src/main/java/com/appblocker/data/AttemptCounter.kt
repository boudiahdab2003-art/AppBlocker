package com.appblocker.data

import android.content.Context

/**
 * Counts how many times each blocked target was opened — "N× today | M× total" on the
 * block screen. Stored in SharedPreferences, keyed by package (or a synthetic key like
 * "web" for keyword/site blocks). "Today" resets when the stored day-stamp changes, the
 * same midnight-rollover idea used in [com.appblocker.service.UsageTracker].
 */
object AttemptCounter {

    private const val PREFS = "attempt_counts"

    /** One blocked target's attempt counts. [key] is a package name or "web". */
    data class Attempt(val key: String, val today: Int, val total: Int)

    /** All recorded targets, most-attempted first. Powers the Insights page. */
    fun summary(context: Context): List<Attempt> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = todayStamp()
        return prefs.all.keys
            .filter { it.startsWith("total_") }
            .map { totalKey ->
                val key = totalKey.removePrefix("total_")
                val total = prefs.getInt(totalKey, 0)
                val todayCount =
                    if (prefs.getInt("day_$key", -1) == today) prefs.getInt("today_$key", 0) else 0
                Attempt(key, todayCount, total)
            }
            .sortedByDescending { it.total }
    }

    /** Sum of today's attempts across every blocked target — the block screen's
     *  "minutes reclaimed" masthead multiplies this by an average session estimate. */
    fun totalToday(context: Context): Int = summary(context).sumOf { it.today }

    /** Records one open attempt for [key] and returns (todayCount, totalCount). */
    fun record(context: Context, key: String): Pair<Int, Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = todayStamp()

        val total = prefs.getInt("total_$key", 0) + 1
        val storedDay = prefs.getInt("day_$key", -1)
        val todayCount = if (storedDay == today) prefs.getInt("today_$key", 0) + 1 else 1

        prefs.edit()
            .putInt("total_$key", total)
            .putInt("today_$key", todayCount)
            .putInt("day_$key", today)
            .apply()

        return todayCount to total
    }
}
