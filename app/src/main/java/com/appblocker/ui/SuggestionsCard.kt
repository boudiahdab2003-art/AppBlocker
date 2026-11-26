package com.appblocker.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.appblocker.R
import com.appblocker.data.AiSuggestions
import com.appblocker.data.BlockerDatabase
import com.appblocker.data.CoachActions
import com.appblocker.data.InstalledAppsRepository
import com.appblocker.data.ItemLocks
import com.appblocker.data.LaunchCounter
import com.appblocker.data.ScheduleType
import com.appblocker.data.SmartSuggestions
import com.appblocker.data.SmartSuggestions.Action
import com.appblocker.service.InstagramBan
import com.appblocker.service.LowBatteryLock
import com.appblocker.service.UsageTracker
import com.appblocker.service.currentImePackage
import com.appblocker.service.findEssentialPackages
import com.appblocker.service.findRealBrowserPackages
import com.appblocker.ui.theme.AppShapes
import com.appblocker.ui.theme.softGlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * **"Suggestions for you"** — up to three one-tap buttons at the top of the Blocking tab, each with
 * the app's own logo and an emoji for what it does.
 *
 * **v2 (invariant 115). The verdict on v1 was "very very dumb"** — it suggested apps that are
 * needed, always "1 h", missed the real problem and didn't target social media. Now:
 *  - **the AI coach picks them** ([AiSuggestions]) from the week, the streak, the risky hours and
 *    what the coach has been told, with a real reason on each, refreshed every couple of hours;
 *  - **only distracting apps** are ever candidates ([SmartSuggestions.distracting]): social media,
 *    video, games and browsers — never a messenger, maps, banking, school or work;
 *  - while there is no fresh coach answer (offline, first open, quota) the rules stand in, now
 *    sized to the use instead of a flat hour.
 *
 * Still the v1 rules: **no undo** — a tap applies at once and nothing here removes anything — and
 * every action only ever makes blocking stronger ([CoachActions] / QuickActions).
 */
@Composable
fun SuggestionsCard(resumeTick: Int) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val apps by InstalledAppsRepository.apps.collectAsState()
    var rows by remember { mutableStateOf<List<SuggestRow>>(emptyList()) }
    // What was tapped this visit, so the button says what it did instead of vanishing.
    val done = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(resumeTick, apps.size) {
        // Again every few minutes while the tab stays open: the night starts at 22:00 and today's
        // minutes keep growing, neither of which brings a resume. Only while the app is on screen —
        // a stopped activity keeps its composition, and this must not poll from behind.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val g = withContext(Dispatchers.IO) { gather(context) }
                val now = System.currentTimeMillis()
                val cached = AiSuggestions.cached(context)
                val fresh = cached != null && AiSuggestions.isFresh(cached.first, now)
                val fromCoach = if (cached != null && fresh) coachRows(cached.second, cached.first, g) else emptyList()
                rows = fromCoach.ifEmpty { g.ruleRows }
                // Ask the coach when its answer is stale — not more than every half hour, so an
                // offline evening does not mean a request every five minutes.
                if (!fresh && g.candidates.isNotEmpty() && now - lastAiAttemptAt > AI_RETRY_MS) {
                    lastAiAttemptAt = now
                    val raw = withContext(Dispatchers.IO) { AiSuggestions.refresh(context, g.candidates) }
                    if (raw != null) {
                        val picked = coachRows(raw, System.currentTimeMillis(), g)
                        if (picked.isNotEmpty()) rows = picked
                    }
                }
                delay(REFRESH_MS)
            }
        }
    }
    if (rows.isEmpty()) return
    val byPkg = apps.associateBy { it.packageName }

    Card(
        Modifier.fillMaxWidth().softGlow(AppShapes.card, elevation = 4.dp),
        shape = AppShapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column {
                Text(
                    stringResource(R.string.suggest_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
                )
                if (rows.any { it.fromCoach }) {
                    Text(
                        stringResource(R.string.suggest_by_coach),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            for (r in rows) {
                val app = r.action.pkg?.let { byPkg[it] }
                if (r.action.pkg != null && app == null) continue
                SuggestionRow(
                    icon = app?.icon,
                    title = r.title,
                    reason = r.reason,
                    button = done[r.key] ?: buttonFor(context, r.action),
                    applied = r.key in done,
                    onTap = {
                        if (r.key in done) return@SuggestionRow
                        done[r.key] = doneFor(context, r)
                        // Not the screen's scope: leaving the tab the moment after the tap disposes
                        // this card and cancels that scope, and the button would have said ✅ for a
                        // lock that was never saved. If saving fails, the ✅ is taken back.
                        val label = app?.label ?: r.action.appLabel.orEmpty()
                        applyScope.launch {
                            val ok = runCatching {
                                CoachActions.apply(
                                    context.applicationContext, r.action,
                                    context.getString(R.string.suggest_limit_schedule_name, label), r.key,
                                )
                            }.isSuccess
                            if (!ok) withContext(Dispatchers.Main) { done.remove(r.key) }
                        }
                    },
                )
            }
        }
    }
    Spacer(Modifier.padding(top = 24.dp))
}

/** One row: from the coach (v2) or from the rules while there is no fresh coach answer. */
private data class SuggestRow(
    val key: String,
    val title: String,
    val reason: String,
    val action: CoachActions.Action,
    /** Today's minutes in the app, for the "done for today" wording of a limit. */
    val minutesToday: Int,
    val fromCoach: Boolean,
)

@Composable
private fun SuggestionRow(
    icon: android.graphics.Bitmap?,
    title: String,
    reason: String,
    button: String,
    applied: Boolean,
    onTap: () -> Unit,
) {
    // The button sits UNDER the words, not beside them: beside them it squeezed the coach's
    // reason into a narrow column and cut it off after three lines.
    Column {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Image(icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(40.dp))
            } else {
                // Strict Mode has no app: its own shield.
                Text("🛡", style = MaterialTheme.typography.titleLarge)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (reason.isNotBlank()) {
                Text(
                    reason, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4,
                )
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.clip(RoundedCornerShape(50))
                .background(
                    if (applied) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.primaryContainer,
                )
                .clickable(enabled = !applied, onClick = onTap)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                button, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                color = if (applied) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
    }
}

/** How often the card re-reads usage while it stays on screen. */
private const val REFRESH_MS = 5 * 60_000L

/** The least time between two coach requests from this card, whatever happened to the last. */
private const val AI_RETRY_MS = 30 * 60_000L

@Volatile private var lastAiAttemptAt = 0L

/** Outlives the screen, so a tap is always saved; see the tap above. */
private val applyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private fun minutesText(m: Int): String = fmtHoursMinutes(m * 60_000L)

private fun buttonFor(c: Context, a: CoachActions.Action): String = when (a.type) {
    CoachActions.Type.LOCK_APP -> c.getString(R.string.suggest_btn_lock, CoachActions.lengthLabel(a.minutes))
    CoachActions.Type.LOCK_TONIGHT -> c.getString(R.string.suggest_btn_tonight, morningLabel())
    CoachActions.Type.DAILY_LIMIT -> c.getString(R.string.suggest_btn_limit, a.minutes)
    CoachActions.Type.STRICT -> c.getString(R.string.suggest_btn_strict, CoachActions.lengthLabel(a.minutes))
}

private fun doneFor(c: Context, r: SuggestRow): String = when (r.action.type) {
    CoachActions.Type.LOCK_TONIGHT -> c.getString(R.string.suggest_done_locked, morningLabel())
    CoachActions.Type.LOCK_APP, CoachActions.Type.STRICT -> c.getString(
        R.string.suggest_done_locked,
        clockLabel(System.currentTimeMillis() + r.action.minutes * 60_000L),
    )
    // Already past the new limit today: it blocks from now until tomorrow, so say that.
    CoachActions.Type.DAILY_LIMIT -> if (r.minutesToday >= r.action.minutes) {
        c.getString(R.string.suggest_done_limit_now)
    } else {
        c.getString(R.string.suggest_done_limit, r.action.minutes)
    }
}

private fun morningLabel(): String = "%02d:00".format(SmartSuggestions.MORNING_HOUR)

private fun clockLabel(at: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = at }
    return "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
}

/** What the card knows about the phone right now: the candidates and the rules' own picks. */
private class Gathered(
    val candidates: List<AiSuggestions.Candidate>,
    val ruleRows: List<SuggestRow>,
    val apps: List<CoachActions.App>,
    val today: Map<String, Int>,
)

/** The coach's picks in [raw], re-checked against [g] — an app locked or blocked since is dropped. */
private fun coachRows(raw: String, fetchedAt: Long, g: Gathered): List<SuggestRow> {
    val allowed = g.candidates.map { it.pkg }.toSet()
    val browsers = g.candidates.filter { it.isBrowser }.map { it.pkg }.toSet()
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val byPkg = g.candidates.associateBy { it.pkg }
    return AiSuggestions.parse(raw, g.apps) { it in allowed }
        .filter { AiSuggestions.suitableNow(it.action, hour, browsers) }
        .filter { AiSuggestions.worthIt(it.action, it.action.pkg?.let { p -> byPkg[p] }) }
        .map { pick ->
        SuggestRow(
            key = "ai|" + fetchedAt + "|" + pick.action.id,
            title = "${pick.emoji} ${pick.title}",
            reason = pick.reason,
            action = pick.action,
            minutesToday = pick.action.pkg?.let { g.today[it] } ?: 0,
            fromCoach = true,
        )
    }
}

/** Everything the card needs, read off the phone. */
private suspend fun gather(context: Context): Gathered = runCatching {
    InstalledAppsRepository.ensureLoaded(context)
    val installed = InstalledAppsRepository.apps.value
    val db = BlockerDatabase.get(context)
    val rules = db.appRuleDao().getAll().first()
    val schedules = db.scheduleDao().getAll().first()
    val blocked = rules.filter { it.isBlocked }.map { it.packageName }.toSet()
    // The strictest daily limit each app already has (schedules; AppRule limits are blocked rules
    // and excluded below with the rest of the block list).
    val limits = HashMap<String, Int>()
    for (sch in schedules) {
        if (!sch.enabled || sch.type != ScheduleType.USAGE_LIMIT) continue
        for (p in sch.packages) limits[p] = minOf(limits[p] ?: Int.MAX_VALUE, sch.limitMinutes)
    }
    val locked = ItemLocks.locked(context, ItemLocks.Kind.APP)
    // The phone, messages, keyboard and home screen are never suggested — the same set the low-
    // battery lock keeps open, so nothing here can lock anyone out of a call or a text.
    val essential = findEssentialPackages(context) +
        listOfNotNull(currentImePackage(context), LowBatteryLock.smsPackage(context)) +
        launcherPackages(context) + context.packageName
    val browsers = runCatching { findRealBrowserPackages(context) }.getOrDefault(emptySet())
    val today = UsageTracker.minutesByPackageToday(context)
    val week = UsageTracker.pastWeekAppMinutes(context)
    val weekBefore = UsageTracker.lastWeekAppMinutes(context)
    val opens = runCatching { LaunchCounter.opensTodayByApp(context) }.getOrDefault(emptyMap())
    // v2: only distracting apps, and only those not already handled.
    val usable = installed.filter { a ->
        val p = a.packageName
        p !in essential && p !in blocked && p !in locked && !InstagramBan.isBannedApp(p) &&
            SmartSuggestions.distracting(p, a.category, p in browsers) &&
            (limits[p]?.let { (today[p] ?: 0) < it } ?: true)
    }
    val candidates = usable
        .map { a ->
            val p = a.packageName
            val wk = week[p] ?: 0
            val before = weekBefore[p] ?: 0
            AiSuggestions.Candidate(
                pkg = p, label = a.label,
                todayMinutes = today[p] ?: 0,
                weekAvgMinutes = wk / 7,
                trendPct = if (before > 0) ((wk - before) * 100) / before else null,
                opensToday = opens[p] ?: 0,
                isBrowser = p in browsers,
            )
        }
        .filter { it.todayMinutes > 0 || it.weekAvgMinutes >= 5 }
        .sortedByDescending { it.todayMinutes * 2 + it.weekAvgMinutes }
        .take(12)
    val usableSet = usable.map { it.packageName }.toSet()
    val labels = installed.associate { it.packageName to it.label }
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val ruleRows = SmartSuggestions.suggest(
        today = today, weekTotal = week, hour = hour,
        eligible = { it in usableSet }, limits = limits, browsers = browsers,
    ).map { s -> ruleRow(context, s, labels[s.pkg] ?: s.pkg) }
    Gathered(candidates, ruleRows, installed.map { CoachActions.App(it.packageName, it.label) }, today)
}.getOrDefault(Gathered(emptyList(), emptyList(), emptyList(), emptyMap()))

/** A rule pick as a row — the rules stand in while there is no fresh coach answer. */
private fun ruleRow(c: Context, s: SmartSuggestions.Suggestion, label: String): SuggestRow {
    val action = when (s.action) {
        Action.LOCK_HOUR -> CoachActions.Action(CoachActions.Type.LOCK_APP, s.pkg, label, s.lockMinutes)
        Action.LOCK_TONIGHT -> CoachActions.Action(CoachActions.Type.LOCK_TONIGHT, s.pkg, label)
        Action.DAILY_LIMIT -> CoachActions.Action(CoachActions.Type.DAILY_LIMIT, s.pkg, label, s.limitMinutes)
    }
    val reason = when (s.action) {
        Action.LOCK_TONIGHT -> c.getString(R.string.suggest_reason_night, minutesText(s.minutesToday))
        Action.DAILY_LIMIT -> c.getString(R.string.suggest_reason_week, minutesText(s.weekAvgMinutes))
        Action.LOCK_HOUR -> c.getString(R.string.suggest_reason_today, minutesText(s.minutesToday))
    }
    return SuggestRow("rule|${action.id}", label, reason, action, s.minutesToday, fromCoach = false)
}

private fun launcherPackages(context: Context): Set<String> = runCatching {
    context.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
    ).map { it.activityInfo.packageName }.toSet()
}.getOrDefault(emptySet())
