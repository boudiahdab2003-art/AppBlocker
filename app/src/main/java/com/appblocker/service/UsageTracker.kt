package com.appblocker.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import com.appblocker.data.AppCategories
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min

/**
 * Reads how long an app has been used *today* via Android's UsageStats.
 * Querying from midnight means the count naturally resets each day — no
 * separate reset job needed. Requires the PACKAGE_USAGE_STATS special access.
 */
object UsageTracker {

    private const val OWN_PACKAGE = "com.appblocker"

    /** A package's foreground time today, in minutes. */
    data class AppUsage(val packageName: String, val minutes: Int)

    private fun usageStatsManager(context: Context): UsageStatsManager? =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    /** Today's raw UsageStats entries, or null when unavailable (e.g. no usage access). */
    private fun statsToday(context: Context): List<UsageStats>? =
        usageStatsManager(context)
            ?.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfToday(), System.currentTimeMillis())

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Midnight (start of day) [daysAgo] days before today, in epoch millis. */
    fun startOfDayAgo(daysAgo: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, -daysAgo)
    }.timeInMillis

    // ---- Today, per app ----

    fun usedMinutesToday(context: Context, packageName: String): Int {
        val stats = statsToday(context) ?: return 0
        val totalMs = stats.filter { it.packageName == packageName }
            .sumOf { it.totalTimeInForeground }
        return (totalMs / 60_000L).toInt()
    }

    /** Minutes used per package today, summed across entries, biggest first. */
    fun topAppsToday(context: Context, limit: Int = 5): List<AppUsage> {
        val stats = statsToday(context) ?: return emptyList()
        return stats.groupBy { it.packageName }
            .mapValues { (_, list) -> list.sumOf { it.totalTimeInForeground } / 60_000L }
            .filter { it.value > 0 && it.key != OWN_PACKAGE }
            .map { AppUsage(it.key, it.value.toInt()) }
            .sortedByDescending { it.minutes }
            .take(limit)
    }

    /** Foreground minutes today keyed by package (only apps used > 0 min). */
    fun minutesByPackageToday(context: Context): Map<String, Int> {
        val stats = statsToday(context) ?: return emptyMap()
        return stats.groupBy { it.packageName }
            .mapValues { (_, list) -> (list.sumOf { it.totalTimeInForeground } / 60_000L).toInt() }
            .filter { it.value > 0 }
    }

    // ---- Today, totals & breakdowns ----

    /** Total foreground time across all apps today, in minutes. */
    fun totalMinutesToday(context: Context): Int {
        val stats = statsToday(context) ?: return 0
        return (stats.sumOf { it.totalTimeInForeground } / 60_000L).toInt()
    }

    /** Minutes per app category today (keyed by AppCategory.name), biggest first. */
    fun categoryMinutesToday(context: Context): Map<String, Int> {
        val stats = statsToday(context) ?: return emptyMap()
        val byCat = HashMap<String, Long>()
        stats.forEach { s ->
            if (s.packageName == OWN_PACKAGE) return@forEach
            val cat = AppCategories.categoryOf(s.packageName).name
            byCat[cat] = (byCat[cat] ?: 0L) + s.totalTimeInForeground
        }
        return byCat.mapValues { (it.value / 60_000L).toInt() }
            .filter { it.value > 0 }
            .toList().sortedByDescending { it.second }.toMap()
    }

    /** Foreground minutes bucketed into the 24 hours of today (for the Day chart). */
    fun hourlyMinutesToday(context: Context): IntArray {
        val usm = usageStatsManager(context) ?: return IntArray(24)
        val dayStart = startOfToday()
        val now = System.currentTimeMillis()
        val buckets = LongArray(24)
        val fgStart = HashMap<String, Long>()
        val events = usm.queryEvents(dayStart, now)
        val e = UsageEvents.Event()
        while (events.getNextEvent(e)) {
            when (e.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> fgStart[e.packageName] = e.timeStamp
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val s = fgStart.remove(e.packageName) ?: continue
                    addInterval(buckets, s, e.timeStamp, dayStart)
                }
            }
        }
        for ((_, s) in fgStart) addInterval(buckets, s, now, dayStart)
        return IntArray(24) { (buckets[it] / 60_000L).toInt() }
    }

    private fun addInterval(buckets: LongArray, from: Long, to: Long, dayStart: Long) {
        var s = max(from, dayStart)
        while (s < to) {
            val hour = ((s - dayStart) / 3_600_000L).toInt().coerceIn(0, 23)
            val hourEnd = dayStart + (hour + 1) * 3_600_000L
            val seg = min(to, hourEnd) - s
            buckets[hour] += seg
            s += seg
        }
    }

    // ---- History (multi-day) ----

    /** Total foreground minutes for each of the last [days] days (last index = today). */
    fun dailyMinutes(context: Context, days: Int): IntArray {
        val usm = usageStatsManager(context) ?: return IntArray(days)
        val result = IntArray(days)
        for (i in 0 until days) {
            val start = startOfDayAgo(days - 1 - i)
            val end = start + 24 * 3_600_000L
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end) ?: continue
            result[i] = (stats.sumOf { it.totalTimeInForeground } / 60_000L).toInt()
        }
        return result
    }

    /** Foreground minutes per package across [start]..[end] (skips our own app). */
    fun appMinutesInRange(context: Context, start: Long, end: Long): Map<String, Int> {
        val usm = usageStatsManager(context) ?: return emptyMap()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end) ?: return emptyMap()
        val byPkg = HashMap<String, Long>()
        stats.forEach { s ->
            if (s.packageName == OWN_PACKAGE) return@forEach
            byPkg[s.packageName] = (byPkg[s.packageName] ?: 0L) + s.totalTimeInForeground
        }
        return byPkg.mapValues { (it.value / 60_000L).toInt() }.filter { it.value > 0 }
    }
}
