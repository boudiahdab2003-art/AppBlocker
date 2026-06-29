package com.appblocker.ui

import android.app.Application
import android.content.Context
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
import com.appblocker.service.UsageTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row in an Insights list: a labelled app/site with an icon, value, and category dot. */
data class StatRow(val label: String, val icon: Bitmap?, val value: String, val dotColor: Color? = null)

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
    val categories: List<CatSlice> = emptyList(),
)

class InsightsViewModel(app: Application) : AndroidViewModel(app) {
    private val pm: PackageManager = app.packageManager

    private val _state = MutableStateFlow(InsightsState())
    val state: StateFlow<InsightsState> = _state

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) { build() }
        }
    }

    private fun build(): InsightsState {
        val ctx = getApplication<Application>()
        val attempts = AttemptCounter.summary(ctx).take(6).map { a ->
            if (a.key == "web") {
                StatRow("Websites", null, "${a.today}× today · ${a.total}× total")
            } else {
                StatRow(label(a.key), icon(a.key), "${a.today}× today · ${a.total}× total", dotColor(a.key))
            }
        }
        val opensByApp = LaunchCounter.opensTodayByApp(ctx)
        val topApps = UsageTracker.topAppsToday(ctx, 6).map { u ->
            val opens = opensByApp[u.packageName] ?: 0
            val detail = if (opens > 0) "${fmt(u.minutes)} · $opens opens" else fmt(u.minutes)
            StatRow(label(u.packageName), icon(u.packageName), detail, dotColor(u.packageName))
        }
        val topOpens = opensByApp.entries
            .sortedByDescending { it.value }
            .take(6)
            .map { (pkg, n) -> StatRow(label(pkg), icon(pkg), "$n opens", dotColor(pkg)) }
        val categories = UsageTracker.categoryMinutesToday(ctx).mapNotNull { (name, mins) ->
            runCatching { AppCategory.valueOf(name) }.getOrNull()?.let {
                CatSlice(it.label, Color(it.color), mins)
            }
        }
        val weekly = UsageTracker.weeklyMinutes(ctx)
        return InsightsState(
            loaded = true,
            usageAccess = hasUsageAccess(ctx),
            screenMinutes = UsageTracker.totalMinutesToday(ctx),
            weekMinutes = weekly.sum(),
            strictMinutes = StatsStore.strictMinutesToday(ctx),
            hourly = UsageTracker.hourlyMinutesToday(ctx),
            weekly = weekly,
            attempts = attempts,
            topApps = topApps,
            topOpens = topOpens,
            categories = categories,
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
