package com.appblocker.ui

import android.app.AppOpsManager
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Process
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appblocker.data.AttemptCounter
import com.appblocker.data.StatsStore
import com.appblocker.service.UsageTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row in an Insights list: a labelled app/site with an icon and a value string. */
data class StatRow(val label: String, val icon: Bitmap?, val value: String)

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
                StatRow(label(a.key), icon(a.key), "${a.today}× today · ${a.total}× total")
            }
        }
        val topApps = UsageTracker.topAppsToday(ctx, 6).map { u ->
            StatRow(label(u.packageName), icon(u.packageName), fmt(u.minutes))
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
        )
    }

    private fun label(pkg: String): String = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun icon(pkg: String): Bitmap? = runCatching {
        pm.getApplicationIcon(pkg).toBitmap(96, 96)
    }.getOrNull()

    companion object {
        fun fmt(minutes: Int): String =
            if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"

        @Suppress("DEPRECATION")
        fun hasUsageAccess(ctx: Context): Boolean {
            val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
            )
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
