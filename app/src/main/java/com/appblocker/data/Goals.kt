package com.appblocker.data

import android.content.Context
import com.appblocker.service.UsageTracker
import org.json.JSONArray
import org.json.JSONObject

/** What a goal measures. UNLOCKS reuses [Goal.target] as a count instead of minutes. */
enum class GoalKind { SCREEN_TIME, APP_LIMIT, UNLOCKS }

/** A measurable daily target the app itself tracks — hit when the day finishes UNDER it. */
data class Goal(
    val id: String,
    val kind: GoalKind,
    val target: Int,            // minutes (SCREEN_TIME/APP_LIMIT) or count (UNLOCKS)
    val pkg: String? = null,    // APP_LIMIT only
    val appLabel: String? = null,
    val createdDay: Int = todayStamp(),
) {
    fun label(): String = when (kind) {
        GoalKind.SCREEN_TIME -> "Screen time under ${fmtMin(target)} a day"
        GoalKind.APP_LIMIT -> "${appLabel ?: pkg} under ${fmtMin(target)} a day"
        GoalKind.UNLOCKS -> "Under $target unlocks a day"
    }

    private fun fmtMin(m: Int) =
        if (m >= 60) (if (m % 60 == 0) "${m / 60}h" else "${m / 60}h ${m % 60}m") else "${m}m"
}

/** A goal plus everything the UI shows about it. [hits7] covers the last 7 FINISHED days,
 *  oldest first; null = no data recorded that day. [streak] = consecutive hit days ending
 *  yesterday. */
data class GoalProgress(val goal: Goal, val usedToday: Int, val hits7: List<Boolean?>, val streak: Int)

/**
 * Structured goals the app can actually measure. Each evaluation snapshots today's used
 * value per goal (`gused_<id>_<day>`, last write of the day = final), which powers hit/miss
 * history, streaks, and the +XP-per-hit reward in [Gamification]'s banking loop.
 */
object Goals {
    private const val PREFS = "goals"
    private const val KEEP_DAYS = 60

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Goal> {
        migrateLegacy(ctx)
        val raw = p(ctx).getString("list", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Goal(
                    id = o.getString("id"),
                    kind = GoalKind.valueOf(o.getString("kind")),
                    target = o.getInt("target"),
                    pkg = o.optString("pkg").ifBlank { null },
                    appLabel = o.optString("label").ifBlank { null },
                    createdDay = o.optInt("created", todayStamp()),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun add(ctx: Context, goal: Goal) = save(ctx, all(ctx).filter { it.id != goal.id } + goal)

    fun remove(ctx: Context, id: String) {
        save(ctx, all(ctx).filter { it.id != id })
        // Drop the removed goal's history too.
        val editor = p(ctx).edit()
        p(ctx).all.keys.filter { it.startsWith("gused_${id}_") }.forEach { editor.remove(it) }
        editor.apply()
    }

    /** Replaces the whole list (the coach's goal updates work this way). */
    fun replaceAll(ctx: Context, goals: List<Goal>) = save(ctx, goals)

    fun newId(): String = "g${System.currentTimeMillis().toString(36)}"

    /** Today's live value against the goal's target. */
    fun usedToday(ctx: Context, goal: Goal): Int = when (goal.kind) {
        GoalKind.SCREEN_TIME -> UsageTracker.totalMinutesToday(UsageTracker.todaySnapshot(ctx))
        GoalKind.APP_LIMIT -> goal.pkg?.let { UsageTracker.usedMinutesToday(ctx, it) } ?: 0
        GoalKind.UNLOCKS -> UnlockCounter.unlocksToday(ctx)
    }

    /** Snapshots today's used value for every goal (called from Gamification.evaluate) and
     *  prunes old history. The day's last snapshot is its final record. */
    fun recordToday(ctx: Context) {
        val goals = all(ctx)
        if (goals.isEmpty()) return
        val today = todayStamp()
        val prefs = p(ctx)
        val editor = prefs.edit()
        goals.forEach { g -> editor.putInt("gused_${g.id}_$today", usedToday(ctx, g)) }
        prefs.all.keys.forEach { k ->
            if (!k.startsWith("gused_")) return@forEach
            val day = k.substringAfterLast('_').toIntOrNull() ?: return@forEach
            if (today - day > KEEP_DAYS) editor.remove(k)
        }
        editor.apply()
    }

    /** Hit/miss for the last 7 finished days (oldest first; null = not recorded). */
    fun hits7(ctx: Context, goal: Goal): List<Boolean?> {
        val prefs = p(ctx)
        val days = ArrayList<Int>(7)
        var d = prevDayStamp(todayStamp())
        repeat(7) { days.add(d); d = prevDayStamp(d) }
        return days.reversed().map { day ->
            val used = prefs.getInt("gused_${goal.id}_$day", -1)
            if (used < 0) null else used < goal.target
        }
    }

    /** Consecutive hit days ending yesterday. */
    fun streak(ctx: Context, goal: Goal): Int {
        val prefs = p(ctx)
        var d = prevDayStamp(todayStamp())
        var n = 0
        while (true) {
            val used = prefs.getInt("gused_${goal.id}_$d", -1)
            if (used < 0 || used >= goal.target) return n
            n++; d = prevDayStamp(d)
        }
    }

    /** How many goals were hit on [day] (used by XP banking). */
    fun hitCountOn(ctx: Context, day: Int): Int {
        val prefs = p(ctx)
        return all(ctx).count { g ->
            val used = prefs.getInt("gused_${g.id}_$day", -1)
            used in 0 until g.target
        }
    }

    /** True once any goal has ever been hit on a finished day (the "On target" badge). */
    fun anyHitEver(ctx: Context): Boolean {
        val prefs = p(ctx)
        val today = todayStamp()
        val targets = all(ctx).associate { it.id to it.target }
        return prefs.all.any { (k, v) ->
            if (!k.startsWith("gused_")) return@any false
            val id = k.removePrefix("gused_").substringBeforeLast('_')
            val day = k.substringAfterLast('_').toIntOrNull() ?: return@any false
            val target = targets[id] ?: return@any false
            day < today && (v as? Int ?: Int.MAX_VALUE) < target
        }
    }

    /** Longest current streak across goals (the "Promise kept" badge checks >= 7). */
    fun bestStreak(ctx: Context): Int = all(ctx).maxOfOrNull { streak(ctx, it) } ?: 0

    /** Full progress view for the UI. */
    fun progress(ctx: Context): List<GoalProgress> =
        all(ctx).map { g -> GoalProgress(g, usedToday(ctx, g), hits7(ctx, g), streak(ctx, g)) }

    /** One-line-per-goal text with live numbers, for the coach's prompts. */
    fun promptSummary(ctx: Context): String = all(ctx).joinToString("\n") { g ->
        val unit = if (g.kind == GoalKind.UNLOCKS) "" else "m"
        "- ${g.label()} — at ${usedToday(ctx, g)}$unit today; " +
            "hit ${hits7(ctx, g).count { it == true }} of the last 7 days; " +
            "streak ${streak(ctx, g)}"
    }

    private fun save(ctx: Context, goals: List<Goal>) {
        val arr = JSONArray()
        goals.forEach { g ->
            arr.put(JSONObject()
                .put("id", g.id).put("kind", g.kind.name).put("target", g.target)
                .put("pkg", g.pkg ?: "").put("label", g.appLabel ?: "")
                .put("created", g.createdDay))
        }
        p(ctx).edit().putString("list", arr.toString()).apply()
    }

    /** One-time upgrade from the old free-text goals: salvage "under N hour(s)" as a
     *  screen-time goal, drop the rest. */
    private fun migrateLegacy(ctx: Context) {
        val old = ctx.getSharedPreferences("ai_coach", Context.MODE_PRIVATE)
        val raw = old.getString("goals", null) ?: return
        old.edit().remove("goals").apply()
        val texts = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
        val hours = texts.firstNotNullOfOrNull {
            Regex("under\\s+(\\d+)\\s*hour", RegexOption.IGNORE_CASE)
                .find(it)?.groupValues?.get(1)?.toIntOrNull()
        } ?: return
        val migrated = Goal(newId(), GoalKind.SCREEN_TIME, hours * 60)
        // Don't recurse through all(): write directly.
        save(ctx, listOf(migrated))
    }
}
