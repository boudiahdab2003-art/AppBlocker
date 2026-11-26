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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.appblocker.R
import com.appblocker.data.BlockerDatabase
import com.appblocker.data.InstalledAppsRepository
import com.appblocker.data.ItemLocks
import com.appblocker.data.Schedule
import com.appblocker.data.ScheduleType
import com.appblocker.data.SmartSuggestions
import com.appblocker.data.SmartSuggestions.Action
import com.appblocker.service.InstagramBan
import com.appblocker.service.UsageTracker
import com.appblocker.service.currentImePackage
import com.appblocker.service.findEssentialPackages
import com.appblocker.ui.theme.AppShapes
import com.appblocker.ui.theme.softGlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * **"Suggestions for you"** — up to three one-tap buttons at the top of the Blocking tab, each with
 * the app's own logo and an emoji for what it does (invariant 113; the rules are in
 * [SmartSuggestions]). His picks: Smart mix, **no undo**, top of the Blocking tab.
 *
 * No undo means exactly that: a tap applies at once, with no confirm and no Undo, and nothing on
 * this card removes a lock or a limit. A locked app then shows on the Locks screen with its time.
 * Hidden when there is nothing worth suggesting (or no usage access to judge by).
 */
@Composable
fun SuggestionsCard(resumeTick: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apps by InstalledAppsRepository.apps.collectAsState()
    var suggestions by remember { mutableStateOf<List<SmartSuggestions.Suggestion>>(emptyList()) }
    // What was tapped this visit, so the button says what it did instead of vanishing.
    val done = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(resumeTick, apps.size) {
        suggestions = withContext(Dispatchers.IO) { gather(context) }
    }
    if (suggestions.isEmpty()) return
    val byPkg = apps.associateBy { it.packageName }

    Card(
        Modifier.fillMaxWidth().softGlow(AppShapes.card, elevation = 4.dp),
        shape = AppShapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                stringResource(R.string.suggest_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
            )
            for (s in suggestions) {
                val app = byPkg[s.pkg] ?: continue
                SuggestionRow(
                    icon = app.icon,
                    label = app.label,
                    reason = reasonFor(context, s),
                    button = done[s.pkg] ?: buttonFor(context, s),
                    applied = s.pkg in done,
                    onTap = {
                        if (s.pkg in done) return@SuggestionRow
                        done[s.pkg] = doneFor(context, s)
                        scope.launch { apply(context, s, app.label) }
                    },
                )
            }
        }
    }
    Spacer(Modifier.padding(top = 24.dp))
}

@Composable
private fun SuggestionRow(
    icon: android.graphics.Bitmap?,
    label: String,
    reason: String,
    button: String,
    applied: Boolean,
    onTap: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Image(icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(40.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                reason, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
            )
        }
        Spacer(Modifier.width(10.dp))
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

private fun minutesText(m: Int): String = fmtHoursMinutes(m * 60_000L)

private fun reasonFor(c: Context, s: SmartSuggestions.Suggestion): String = when (s.action) {
    Action.LOCK_TONIGHT -> c.getString(R.string.suggest_reason_night, minutesText(s.minutesToday))
    Action.DAILY_LIMIT -> c.getString(R.string.suggest_reason_week, minutesText(s.weekAvgMinutes))
    Action.LOCK_HOUR -> c.getString(R.string.suggest_reason_today, minutesText(s.minutesToday))
}

private fun buttonFor(c: Context, s: SmartSuggestions.Suggestion): String = when (s.action) {
    Action.LOCK_TONIGHT -> c.getString(R.string.suggest_btn_tonight, morningLabel())
    Action.DAILY_LIMIT -> c.getString(R.string.suggest_btn_limit, s.limitMinutes)
    Action.LOCK_HOUR -> c.getString(R.string.suggest_btn_hour)
}

private fun doneFor(c: Context, s: SmartSuggestions.Suggestion): String = when (s.action) {
    Action.LOCK_TONIGHT -> c.getString(R.string.suggest_done_locked, morningLabel())
    Action.LOCK_HOUR -> c.getString(
        R.string.suggest_done_locked,
        clockLabel(System.currentTimeMillis() + SmartSuggestions.LOCK_HOUR_MS),
    )
    Action.DAILY_LIMIT -> c.getString(R.string.suggest_done_limit, s.limitMinutes)
}

private fun morningLabel(): String = "%02d:00".format(SmartSuggestions.MORNING_HOUR)

private fun clockLabel(at: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = at }
    return "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
}

/** Everything [SmartSuggestions.suggest] needs, read off the phone. */
private suspend fun gather(context: Context): List<SmartSuggestions.Suggestion> = runCatching {
    InstalledAppsRepository.ensureLoaded(context)
    val launchable = InstalledAppsRepository.apps.value.map { it.packageName }.toSet()
    val db = BlockerDatabase.get(context)
    val rules = db.appRuleDao().getAll().first()
    val schedules = db.scheduleDao().getAll().first()
    val blocked = rules.filter { it.isBlocked }.map { it.packageName }.toSet()
    val limited = schedules.filter { it.enabled && it.type == ScheduleType.USAGE_LIMIT }
        .flatMap { it.packages }.toSet()
    val locked = ItemLocks.locked(context, ItemLocks.Kind.APP)
    val essential = findEssentialPackages(context) + listOfNotNull(currentImePackage(context)) +
        launcherPackages(context) + context.packageName
    val now = Calendar.getInstance()
    SmartSuggestions.suggest(
        today = UsageTracker.minutesByPackageToday(context),
        weekTotal = UsageTracker.lastWeekAppMinutes(context),
        hour = now.get(Calendar.HOUR_OF_DAY),
        eligible = { p ->
            p in launchable && p !in essential && p !in blocked && p !in locked &&
                !InstagramBan.isBannedApp(p)
        },
        limited = limited,
    )
}.getOrDefault(emptyList())

private fun launcherPackages(context: Context): Set<String> = runCatching {
    context.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
    ).map { it.activityInfo.packageName }.toSet()
}.getOrDefault(emptySet())

/**
 * The tap. Only ever strengthens: a lock can be lengthened and never shortened ([ItemLocks]), and a
 * limit already there is only ever lowered, never raised.
 */
private suspend fun apply(context: Context, s: SmartSuggestions.Suggestion, label: String) {
    withContext(Dispatchers.IO) {
        when (s.action) {
            Action.LOCK_HOUR ->
                ItemLocks.lockFor(context, ItemLocks.Kind.APP, s.pkg, SmartSuggestions.LOCK_HOUR_MS)
            Action.LOCK_TONIGHT -> {
                val now = Calendar.getInstance()
                ItemLocks.lockFor(
                    context, ItemLocks.Kind.APP, s.pkg,
                    SmartSuggestions.msUntilMorning(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE)),
                )
            }
            Action.DAILY_LIMIT -> {
                val dao = BlockerDatabase.get(context).scheduleDao()
                val name = context.getString(R.string.suggest_limit_schedule_name, label)
                val existing = dao.findByName(name)
                dao.upsert(
                    Schedule(
                        id = existing?.id ?: 0L,
                        name = name,
                        type = ScheduleType.USAGE_LIMIT,
                        enabled = true,
                        limitMinutes = minOf(existing?.limitMinutes ?: Int.MAX_VALUE, s.limitMinutes),
                        packages = listOf(s.pkg),
                    ),
                )
            }
        }
    }
}
