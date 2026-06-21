package com.appblocker.service

import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Calendar

/**
 * Reads how long an app has been used *today* via Android's UsageStats.
 * Querying from midnight means the count naturally resets each day — no
 * separate reset job needed. Requires the PACKAGE_USAGE_STATS special access.
 */
object UsageTracker {

    fun usedMinutesToday(context: Context, packageName: String): Int {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return 0
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val now = System.currentTimeMillis()

        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now)
            ?: return 0
        val totalMs = stats.filter { it.packageName == packageName }
            .sumOf { it.totalTimeInForeground }
        return (totalMs / 60_000L).toInt()
    }
}
