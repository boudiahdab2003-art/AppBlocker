package com.appblocker.ui

import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appblocker.data.AppCategories
import com.appblocker.data.AppCategory
import com.appblocker.data.AttemptCounter
import com.appblocker.data.LaunchCounter
import com.appblocker.data.StatsStore
import com.appblocker.data.UnlockCounter
import com.appblocker.service.UsageTracker
import java.util.Calendar
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row in an Insights list: a labelled app/site with an icon, value, and category dot.
 *  [pkg] is the app's package when the row represents a real app (so it can open a detail). */
data class StatRow(
    val label: String,
    val icon: Bitmap?,
    val value: String,
    val dotColor: Color? = null,
    val pkg: String? = null,
)

/** Everything shown in the per-app detail sheet, gathered on demand. */
data class AppDetail(
    val label: String,
    val icon: Bitmap?,
    val categoryLabel: String,
    val categoryColor: Color,
    val minutes: Int,
    val opens: Int,
    val attemptsToday: Int,
    val attemptsTotal: Int,
)

/** A colored slice of the category-breakdown bar. */
data class CatSlice(val label: String, val color: Color, val minutes: Int)

data class InsightsState(
    val loaded: Boolean = false,
    val usageAccess: Boolean = false,
    val screenMinutes: Int = 0,
    val weekMinutes: Int = 0,
    val strictMinutes: Int = 0,
    val hourly: IntArray = IntArray(24),
    val weekly: IntArray = IntArray(7),
    val attempts: List<StatRow> = emptyList(),
    val topApps: List<StatRow> = emptyList(),
    val topOpens: List<StatRow> = emptyList(),
    val totalOpens: Int = 0,
    val categories: List<CatSlice> = emptyList(),
    // Deeper stats
    val monthly: IntArray = IntArray(30),
    val monthAvg: Int = 0,
    val thisWeekMin: Int = 0,
    val lastWeekMin: Int = 0,
    val weekdayAvg: Int = 0,
    val weekendAvg: Int = 0,
    val appTrends: List<StatRow> = emptyList(),
    val unlocksToday: Int = 0,
)

class InsightsViewModel(app: Application) : AndroidViewModel(app) {
    private val pm: PackageManager = app.packageManager

    private val _state = MutableStateFlow(InsightsState())
    val state: StateFlow<InsightsState> = _state

    private val _detail = MutableStateFlow<AppDetail?>(null)
    val detail: StateFlow<AppDetail?> = _detail

    // No init-time refresh: InsightsScreen calls refresh() every time the tab is opened,
    // so the numbers are current on each visit (cheap now — the history is cached per day).

    /** Loads the per-app detail sheet for [pkg] (screen time + opens + block attempts). */
    fun selectApp(pkg: String) {
        viewModelScope.launch {
            _detail.value = withContext(Dispatchers.IO) { buildDetail(pkg) }
        }
    }

    fun clearDetail() { _detail.value = null }

    private fun buildDetail(pkg: String): AppDetail {
        val ctx = getApplication<Application>()
        val attempt = AttemptCounter.summary(ctx).find { it.key == pkg }
        val cat = AppCategories.categoryOf(pkg)
        return AppDetail(
            label = label(pkg),
            icon = icon(pkg),
            categoryLabel = cat.label,
            categoryColor = Color(cat.color),
            minutes = UsageTracker.usedMinutesToday(ctx, pkg),
            opens = LaunchCounter.opensToday(ctx, pkg),
            attemptsToday = attempt?.today ?: 0,
            attemptsTotal = attempt?.total ?: 0,
        )
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) { build() }
        }
    }

    private fun build(): InsightsState {
        val ctx = getApplication<Application>()
        // One system query for everything derived from "today's stats" (total, top apps,
        // categories) instead of three identical ones.
        val snapshot = UsageTracker.todaySnapshot(ctx)
        val attempts = AttemptCounter.summary(ctx).take(6).map { a ->
            if (a.key == "web") {
                StatRow("Websites", null, "${a.today}× today · ${a.total}× total")
            } else {
                StatRow(label(a.key), icon(a.key), "${a.today}× today · ${a.total}× total",
                    dotColor(a.key), pkg = a.key)
            }
        }
        val opensByApp = LaunchCounter.opensTodayByApp(ctx)
        val topApps = UsageTracker.topAppsToday(snapshot, 6).map { u ->
            val opens = opensByApp[u.packageName] ?: 0
            val detail = if (opens > 0) "${fmt(u.minutes)} · $opens opens" else fmt(u.minutes)
            StatRow(label(u.packageName), icon(u.packageName), detail, dotColor(u.packageName),
                pkg = u.packageName)
        }
        val topOpens = opensByApp.entries
            .sortedByDescending { it.value }
            .take(6)
            .map { (pkg, n) -> StatRow(label(pkg), icon(pkg), "$n opens", dotColor(pkg), pkg = pkg) }
        val categories = UsageTracker.categoryMinutesToday(snapshot).mapNotNull { (name, mins) ->
            runCatching { AppCategory.valueOf(name) }.getOrNull()?.let {
                CatSlice(it.label, Color(it.color), mins)
            }
        }
        // 30-day history powers the Trend tab, week-vs-week and weekday/weekend patterns.
        val monthly = UsageTracker.dailyMinutes(ctx, 30)
        val weekly = monthly.copyOfRange(23, 30) // last 7 days
        val thisWeekMin = monthly.copyOfRange(23, 30).sum()
        val lastWeekMin = monthly.copyOfRange(16, 23).sum()
        // Split the last 30 days into weekday vs weekend (Sat/Sun) and average each.
        val weekdayVals = ArrayList<Int>(); val weekendVals = ArrayList<Int>()
        val cal = Calendar.getInstance()
        for (i in monthly.indices) {
            cal.timeInMillis = System.currentTimeMillis()
            cal.add(Calendar.DAY_OF_YEAR, -(monthly.size - 1 - i))
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) weekendVals.add(monthly[i])
            else weekdayVals.add(monthly[i])
        }

        // Per-app week-over-week trends ("YouTube +40%").
        val now = System.currentTimeMillis()
        val thisWeekApps = UsageTracker.appMinutesInRange(ctx, UsageTracker.startOfDayAgo(6), now)
        val lastWeekApps = UsageTracker.lastWeekAppMinutes(ctx) // fixed range in the past — cached per day
        val appTrends = thisWeekApps.entries
            .filter { it.value >= 5 } // ignore trivially-small apps
            .sortedByDescending { it.value }
            .take(5)
            .map { (pkg, mins) ->
                val prev = lastWeekApps[pkg] ?: 0
                val delta = if (prev > 0) {
                    val pct = ((mins - prev) * 100f / prev).roundToInt()
                    if (pct >= 0) "▲$pct%" else "▼${-pct}%"
                } else "new"
                StatRow(label(pkg), icon(pkg), "${fmt(mins)} · $delta", dotColor(pkg), pkg = pkg)
            }

        return InsightsState(
            loaded = true,
            usageAccess = hasUsageAccess(ctx),
            screenMinutes = UsageTracker.totalMinutesToday(snapshot),
            weekMinutes = weekly.sum(),
            strictMinutes = StatsStore.strictMinutesToday(ctx),
            hourly = UsageTracker.hourlyMinutesToday(ctx),
            weekly = weekly,
            attempts = attempts,
            topApps = topApps,
            topOpens = topOpens,
            totalOpens = opensByApp.values.sum(),
            categories = categories,
            monthly = monthly,
            monthAvg = if (monthly.isNotEmpty()) monthly.sum() / monthly.size else 0,
            thisWeekMin = thisWeekMin,
            lastWeekMin = lastWeekMin,
            weekdayAvg = if (weekdayVals.isNotEmpty()) weekdayVals.average().roundToInt() else 0,
            weekendAvg = if (weekendVals.isNotEmpty()) weekendVals.average().roundToInt() else 0,
            appTrends = appTrends,
            unlocksToday = UnlockCounter.unlocksToday(ctx),
        )
    }

    private fun label(pkg: String): String = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun icon(pkg: String): Bitmap? = runCatching {
        pm.getApplicationIcon(pkg).toBitmap(96, 96)
    }.getOrNull()

    private fun dotColor(pkg: String): Color = Color(AppCategories.categoryOf(pkg).color)

    companion object {
        fun fmt(minutes: Int): String =
            if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    }
}
