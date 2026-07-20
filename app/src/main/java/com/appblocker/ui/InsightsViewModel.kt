package com.appblocker.ui

import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appblocker.data.AiCoach
import com.appblocker.data.AppCategories
import com.appblocker.data.Goal
import com.appblocker.data.GoalProgress
import com.appblocker.data.Goals
import com.appblocker.data.AppCategory
import com.appblocker.data.AttemptCounter
import com.appblocker.data.InstalledApp
import com.appblocker.data.InstalledAppsRepository
import com.appblocker.data.LaunchCounter
import com.appblocker.data.MoodStore
import com.appblocker.data.NotificationCounter
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

/** One row in an Insights list: a labelled app/site with an icon and value.
 *  [pkg] is the app's package when the row represents a real app (so it can open a detail).
 *  [fraction] (0..1, relative to the section's largest row) draws the comparison bar, tinted
 *  [dotColor]. [delta] is an optional trailing change ("▲40%"), coloured by [deltaGood]
 *  (true = green, false = red, null = neutral). */
data class StatRow(
    val label: String,
    val icon: Bitmap?,
    val value: String,
    val dotColor: Color? = null,
    val pkg: String? = null,
    val fraction: Float? = null,
    val delta: String? = null,
    val deltaGood: Boolean? = null,
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

/** The AI Coach panel's state. */
sealed interface CoachState {
    data object NoKey : CoachState
    data object Loading : CoachState
    data class Tips(val tips: List<String>) : CoachState
    data object Unavailable : CoachState
}

data class InsightsState(
    val loaded: Boolean = false,
    val usageAccess: Boolean = false,
    val screenMinutes: Int = 0,
    val weekMinutes: Int = 0,
    val strictMinutes: Int = 0,
    val hourly: IntArray = IntArray(24),
    val weekly: IntArray = IntArray(7),
    val attempts: List<StatRow> = emptyList(),
    val attemptsTodayTotal: Int = 0,
    val attemptsAllTotal: Int = 0,
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
    val biggestDrops: List<StatRow> = emptyList(),
    val biggestIncreases: List<StatRow> = emptyList(),
    val unlocksToday: Int = 0,
    val notificationsToday: Int = 0,
    val notificationAccess: Boolean = false,
    val continuousUseMin: Int = 0,
    val longestFocusMin: Int = 0,
    val moodRating: Int = -1,
    val moodNote: String = "",
    val goals: List<GoalProgress> = emptyList(),
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

    private suspend fun buildDetail(pkg: String): AppDetail {
        val ctx = getApplication<Application>()
        val installed = installedByPackage(ctx)
        val attempt = AttemptCounter.summary(ctx).find { it.key == pkg }
        val cat = AppCategories.categoryOf(pkg)
        return AppDetail(
            label = label(installed, pkg),
            icon = icon(installed, pkg),
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
            val built = withContext(Dispatchers.IO) { build() }
            _state.value = built
            refreshCoach(force = false)
        }
    }

    // --- AI Coach ---

    private val _coach = MutableStateFlow<CoachState>(CoachState.Loading)
    val coach: StateFlow<CoachState> = _coach

    /** Saves the user's Gemini key (device-local only) and fetches tips with it. */
    fun setApiKey(key: String) {
        AiCoach.setApiKey(getApplication(), key)
        viewModelScope.launch { refreshCoach(force = false) }
    }

    /** Forces a fresh Gemini call instead of today's cached tips. */
    fun newTips() {
        viewModelScope.launch { refreshCoach(force = true) }
    }

    private suspend fun refreshCoach(force: Boolean) {
        val ctx = getApplication<Application>()
        // NoKey only when there's neither the server proxy nor a user-entered key. With the
        // proxy on, the coach just works (Loading -> Tips).
        if (!AiCoach.coachAvailable(ctx)) { _coach.value = CoachState.NoKey; return }
        _coach.value = CoachState.Loading
        // The usage summary is built inside AiCoach (shared with the chat) from day-cached data.
        val summary = withContext(Dispatchers.IO) { AiCoach.usageSummary(ctx) }
        val tips = AiCoach.dailyTips(ctx, summary, force)
        _coach.value = if (tips.isNullOrEmpty()) CoachState.Unavailable else CoachState.Tips(tips)
    }

    private suspend fun build(): InsightsState {
        val ctx = getApplication<Application>()
        // Labels/icons come from the launch-warmed installed-apps cache — decoding ~20 icon
        // bitmaps per refresh via PackageManager was most of the remaining build time.
        val installed = installedByPackage(ctx)
        // One system query for everything derived from "today's stats" (total, top apps,
        // categories) instead of three identical ones.
        val snapshot = UsageTracker.todaySnapshot(ctx)
        val attemptSummary = AttemptCounter.summary(ctx)
        val attemptRows = attemptSummary.take(6)
        val maxAttempts = attemptRows.maxOfOrNull { it.total } ?: 0
        val attempts = attemptRows.map { a ->
            val frac = if (maxAttempts > 0) a.total.toFloat() / maxAttempts else null
            if (a.key == "web") {
                StatRow("Websites", null, "${a.today}× today · ${a.total}× total", fraction = frac)
            } else {
                StatRow(label(installed, a.key), icon(installed, a.key),
                    "${a.today}× today · ${a.total}× total", dotColor(a.key), pkg = a.key,
                    fraction = frac)
            }
        }
        val opensByApp = LaunchCounter.opensTodayByApp(ctx)
        val topUsage = UsageTracker.topAppsToday(snapshot, 6)
        val maxUsage = topUsage.maxOfOrNull { it.minutes } ?: 0
        val topApps = topUsage.map { u ->
            val opens = opensByApp[u.packageName] ?: 0
            val detail = if (opens > 0) "${fmt(u.minutes)} · ${opensText(opens)}" else fmt(u.minutes)
            StatRow(label(installed, u.packageName), icon(installed, u.packageName), detail,
                dotColor(u.packageName), pkg = u.packageName,
                fraction = if (maxUsage > 0) u.minutes.toFloat() / maxUsage else null)
        }
        val topOpenEntries = opensByApp.entries.sortedByDescending { it.value }.take(6)
        val maxOpens = topOpenEntries.maxOfOrNull { it.value } ?: 0
        val topOpens = topOpenEntries.map { (pkg, n) ->
            StatRow(label(installed, pkg), icon(installed, pkg), opensText(n), dotColor(pkg), pkg = pkg,
                fraction = if (maxOpens > 0) n.toFloat() / maxOpens else null)
        }
        // parse() maps legacy slice names (VIDEO/CHAT/PRODUCTIVE) onto the new categories;
        // group in case a legacy and a new name land on the same category.
        val categories = UsageTracker.categoryMinutesToday(snapshot)
            .entries.groupBy { AppCategories.parse(it.key) }
            .map { (cat, slices) -> CatSlice(cat.label, Color(cat.color), slices.sumOf { it.value }) }
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
        val trendEntries = thisWeekApps.entries
            .filter { it.value >= 5 } // ignore trivially-small apps
            .sortedByDescending { it.value }
            .take(5)
        val maxTrend = trendEntries.maxOfOrNull { it.value } ?: 0
        val appTrends = trendEntries.map { (pkg, mins) ->
            val prev = lastWeekApps[pkg] ?: 0
            // deltaGood: less use than last week = green; more = red; "new" = neutral.
            val (delta, good) = if (prev > 0) {
                val pct = ((mins - prev) * 100f / prev).roundToInt()
                (if (pct >= 0) "▲$pct%" else "▼${-pct}%") to (pct < 0)
            } else "new" to null
            StatRow(label(installed, pkg), icon(installed, pkg), fmt(mins),
                dotColor(pkg), pkg = pkg,
                fraction = if (maxTrend > 0) mins.toFloat() / maxTrend else null,
                delta = delta, deltaGood = good)
        }

        // Trend podiums: which apps gained or lost the most time this week vs last week.
        val trendDeltas = (thisWeekApps.keys + lastWeekApps.keys).map { pkg ->
            pkg to ((thisWeekApps[pkg] ?: 0) - (lastWeekApps[pkg] ?: 0))
        }.filter { kotlin.math.abs(it.second) >= 15 } // ignore small wobble
        val biggestDrops = trendDeltas.filter { it.second < 0 }
            .sortedBy { it.second }.take(3).map { (pkg, delta) ->
                StatRow(label(installed, pkg), icon(installed, pkg), "-${fmt(-delta)}",
                    dotColor(pkg), pkg = pkg, delta = "▼", deltaGood = true)
            }
        val biggestIncreases = trendDeltas.filter { it.second > 0 }
            .sortedByDescending { it.second }.take(3).map { (pkg, delta) ->
                StatRow(label(installed, pkg), icon(installed, pkg), "+${fmt(delta)}",
                    dotColor(pkg), pkg = pkg, delta = "▲", deltaGood = false)
            }

        val (continuousUse, longestFocus) = UsageTracker.sessionStatsToday(ctx)

        return InsightsState(
            loaded = true,
            usageAccess = hasUsageAccess(ctx),
            screenMinutes = UsageTracker.totalMinutesToday(snapshot),
            weekMinutes = weekly.sum(),
            strictMinutes = StatsStore.strictMinutesToday(ctx),
            hourly = UsageTracker.hourlyMinutesToday(ctx),
            weekly = weekly,
            attempts = attempts,
            attemptsTodayTotal = attemptSummary.sumOf { it.today },
            attemptsAllTotal = attemptSummary.sumOf { it.total },
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
            biggestDrops = biggestDrops,
            biggestIncreases = biggestIncreases,
            unlocksToday = UnlockCounter.unlocksToday(ctx),
            notificationsToday = NotificationCounter.notificationsToday(ctx),
            notificationAccess = isNotificationListenerEnabled(ctx),
            continuousUseMin = continuousUse,
            longestFocusMin = longestFocus,
            moodRating = MoodStore.todayRating(ctx),
            moodNote = MoodStore.todayNote(ctx),
            // Snapshot today's per-goal usage so finished days get judged hit/miss — the
            // day's last snapshot becomes its final record.
            goals = runCatching {
                Goals.recordToday(ctx)
                Goals.progress(ctx)
            }.getOrDefault(emptyList()),
        )
    }

    /** Adds a hand-made goal and refreshes so its live bar appears immediately. */
    fun addGoal(goal: Goal) {
        Goals.add(getApplication(), goal)
        refresh()
    }

    fun removeGoal(goal: Goal) {
        Goals.remove(getApplication(), goal.id)
        refresh()
    }

    /** Save today's mood check-in and refresh so the card updates immediately. */
    fun saveMood(rating: Int, note: String) {
        MoodStore.setToday(getApplication(), rating, note)
        refresh()
    }

    /** The launch-warmed installed-apps cache, keyed by package. */
    private suspend fun installedByPackage(ctx: Application): Map<String, InstalledApp> {
        InstalledAppsRepository.ensureLoaded(ctx)
        return InstalledAppsRepository.apps.value.associateBy { it.packageName }
    }

    // Cache hit for launchable apps; PackageManager fallback for the rest (e.g. system UI
    // packages that show up in usage stats but aren't in the launcher).
    private fun label(cache: Map<String, InstalledApp>, pkg: String): String =
        cache[pkg]?.label ?: runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

    private fun icon(cache: Map<String, InstalledApp>, pkg: String): Bitmap? =
        cache[pkg]?.icon ?: runCatching {
            pm.getApplicationIcon(pkg).toBitmap(96, 96)
        }.getOrNull()

    private fun dotColor(pkg: String): Color = Color(AppCategories.categoryOf(pkg).color)

    companion object {
        fun fmt(minutes: Int): String =
            if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"

        private fun opensText(n: Int): String = if (n == 1) "1 open" else "$n opens"
    }
}
