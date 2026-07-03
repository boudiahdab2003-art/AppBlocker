package com.appblocker.data

import android.content.Context
import com.appblocker.service.AccessibilityUtil
import com.appblocker.service.UsageTracker
import java.util.Calendar
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

/** One milestone badge. Icons are mapped in the UI layer (data stays compose-free). */
data class Achievement(val id: String, val title: String, val desc: String, val xp: Int)

/** A level on the XP ladder. */
data class Level(val name: String, val threshold: Int)

/** Everything the Score card and Achievements screen show. */
data class GamifyState(
    val score: Int,                    // today's live Focus Score 0..100
    val band: String,                  // "Excellent" / "Good" / "Fair" / "Rough day"
    val xp: Int,
    val level: Level,
    val levelIndex: Int,               // 0-based
    val nextLevel: Level?,             // null at max level
    val levelProgress: Float,          // 0..1 toward next level
    val streak: Int,                   // consecutive good days (score >= 60), incl. today if good
    val unlocked: Map<String, Int>,    // achievement id -> dayStamp unlocked
    val newlyUnlocked: List<Achievement>, // unlocked during this evaluation (celebrate!)
    val progress: Map<String, String>, // locked id -> progress hint ("134/250 blocks")
)

/**
 * The Focus Score + XP + achievements engine. Everything is recomputed deterministically
 * from data the app already tracks; this object only persists what can't be recomputed:
 * per-day final scores (for banking/streaks), banked XP, and unlocked badges. Lives in
 * SharedPreferences "gamify".
 */
object Gamification {
    private const val PREFS = "gamify"
    private const val GOOD_DAY = 60
    private const val KEEP_DAYS = 400

    val LEVELS = listOf(
        Level("Starter", 0),
        Level("Aware", 250),
        Level("Focused", 600),
        Level("Disciplined", 1100),
        Level("Guardian", 1800),
        Level("Master", 2800),
        Level("Legend", 4200),
    )

    val ACHIEVEMENTS = listOf(
        Achievement("first_block", "First stand", "Block your first app attempt.", 25),
        Achievement("blocks_50", "Gatekeeper", "Stop 50 urges at the door.", 50),
        Achievement("blocks_250", "The Wall", "250 blocked attempts. Nothing gets through.", 100),
        Achievement("blocks_1000", "Fortress", "1,000 blocked attempts. Untouchable.", 200),
        Achievement("first_schedule", "Planner", "Create your first schedule.", 25),
        Achievement("schedules_3", "Architect", "Run 3 schedules at once.", 75),
        Achievement("strict_first", "Iron will", "Complete your first focus session.", 25),
        Achievement("strict_10h", "Deep worker", "10 hours of focus sessions, total.", 150),
        Achievement("coach_chat", "Open up", "Have your first chat with the coach.", 25),
        Achievement("goal_set", "Direction", "Set a measurable goal.", 50),
        Achievement("goal_hit", "On target", "Finish a day under one of your goals.", 25),
        Achievement("goal_streak7", "Promise kept", "Hit a goal 7 days in a row.", 150),
        Achievement("score_80", "Great day", "Finish a day with a Focus Score of 80+.", 50),
        Achievement("under_2h", "Featherlight", "Finish a day under 2 hours of screen time.", 75),
        Achievement("low_unlocks", "Present", "Finish a day with fewer than 30 unlocks.", 50),
        Achievement("streak_3", "Momentum", "3 good days in a row.", 75),
        Achievement("streak_7", "Unbreakable", "A full week of good days.", 150),
        Achievement("streak_30", "Transformed", "30 good days in a row. A new person.", 500),
        Achievement("full_armor", "Full armor", "Quick Block, adult filter and a schedule — all on at once.", 50),
    )

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun bandOf(score: Int): String = when {
        score >= 80 -> "Excellent"
        score >= 60 -> "Good"
        score >= 40 -> "Fair"
        else -> "Rough day"
    }

    /**
     * Recomputes the live score, persists today's snapshot, banks finished days into XP,
     * checks every achievement, and returns the full state. Called on each Insights refresh.
     */
    suspend fun evaluate(ctx: Context): GamifyState {
        val prefs = p(ctx)
        val today = todayStamp()
        val db = BlockerDatabase.get(ctx)
        val rules = db.appRuleDao().getAll().first()
        val schedules = db.scheduleDao().getAll().first()
        val attemptsTotal = AttemptCounter.summary(ctx).sumOf { it.total }
        val quickBlockActive =
            rules.any { it.isBlocked } && !SettingsStore.quickBlockPaused(ctx)
        val protectionOn = AccessibilityUtil.isEnabled(ctx) &&
            (quickBlockActive || schedules.any { it.enabled })

        val score = liveScore(ctx, protectionOn)
        // Snapshot today's per-goal usage too, so finished days can be judged hit/miss.
        Goals.recordToday(ctx)

        // Persist today's running numbers — the day's LAST write becomes its final record.
        val editor = prefs.edit()
            .putInt("score_$today", score)
            .putInt("min_$today", UsageTracker.totalMinutesToday(UsageTracker.todaySnapshot(ctx)))
            .putInt("unl_$today", UnlockCounter.unlocksToday(ctx))

        // Bank every stored day before today that hasn't been banked yet.
        var xp = prefs.getInt("xp_total", 0)
        val lastBanked = prefs.getInt("last_banked_day", 0)
        val bankedDays = prefs.all.keys
            .filter { it.startsWith("score_") }
            .mapNotNull { it.removePrefix("score_").toIntOrNull() }
            .filter { it in (lastBanked + 1) until today }
            .sorted()
        val bankedFlags = HashSet<String>() // banked-day achievements earned this pass
        bankedDays.forEach { day ->
            val s = prefs.getInt("score_$day", 0)
            xp += s
            // Every goal hit on a finished day pays a bonus on top of the day's score.
            xp += 15 * Goals.hitCountOn(ctx, day)
            if (s >= 80) bankedFlags.add("score_80")
            if (prefs.getInt("min_$day", Int.MAX_VALUE) < 120) bankedFlags.add("under_2h")
            if (prefs.getInt("unl_$day", Int.MAX_VALUE) < 30) bankedFlags.add("low_unlocks")
        }
        if (bankedDays.isNotEmpty()) editor.putInt("last_banked_day", bankedDays.last())

        // Prune ancient entries so the file stays small.
        prefs.all.keys.forEach { k ->
            val day = k.substringAfter('_').toIntOrNull() ?: return@forEach
            if ((k.startsWith("score_") || k.startsWith("min_") || k.startsWith("unl_")) &&
                today - day > KEEP_DAYS) editor.remove(k)
        }

        // Streak: walk back from yesterday over stored finals; today extends it live.
        var streak = 0
        var day = prevDayStamp(today)
        while (prefs.getInt("score_$day", -1) >= GOOD_DAY) { streak++; day = prevDayStamp(day) }
        if (score >= GOOD_DAY) streak++

        // Achievement conditions (booleans first; progress hints for the locked ones below).
        val strictTotal = StatsStore.strictMinutesTotal(ctx)
        val conditions = mapOf(
            "first_block" to (attemptsTotal >= 1),
            "blocks_50" to (attemptsTotal >= 50),
            "blocks_250" to (attemptsTotal >= 250),
            "blocks_1000" to (attemptsTotal >= 1000),
            "first_schedule" to schedules.isNotEmpty(),
            "schedules_3" to (schedules.count { it.enabled } >= 3),
            "strict_first" to (strictTotal > 0 || StatsStore.strictMinutesToday(ctx) > 0),
            "strict_10h" to (strictTotal >= 600),
            "coach_chat" to AiCoach.chatHistory(ctx).any { it.role == "user" },
            "goal_set" to Goals.all(ctx).isNotEmpty(),
            "goal_hit" to Goals.anyHitEver(ctx),
            "goal_streak7" to (Goals.bestStreak(ctx) >= 7),
            "score_80" to bankedFlags.contains("score_80"),
            "under_2h" to bankedFlags.contains("under_2h"),
            "low_unlocks" to bankedFlags.contains("low_unlocks"),
            "streak_3" to (streak >= 3),
            "streak_7" to (streak >= 7),
            "streak_30" to (streak >= 30),
            "full_armor" to (quickBlockActive && SettingsStore.blockAdult(ctx) &&
                schedules.any { it.enabled } && AccessibilityUtil.isEnabled(ctx)),
        )

        val unlocked = HashMap<String, Int>()
        val newly = ArrayList<Achievement>()
        ACHIEVEMENTS.forEach { a ->
            val storedDay = prefs.getInt("ach_${a.id}", -1)
            if (storedDay > 0) {
                unlocked[a.id] = storedDay
            } else if (conditions[a.id] == true) {
                unlocked[a.id] = today
                newly.add(a)
                xp += a.xp
                editor.putInt("ach_${a.id}", today)
            }
        }
        editor.putInt("xp_total", xp)
        editor.apply()

        val levelIndex = LEVELS.indexOfLast { xp >= it.threshold }.coerceAtLeast(0)
        val next = LEVELS.getOrNull(levelIndex + 1)
        val progress = if (next == null) 1f
        else (xp - LEVELS[levelIndex].threshold).toFloat() /
            (next.threshold - LEVELS[levelIndex].threshold)

        val hints = buildMap {
            if ("blocks_50" !in unlocked) put("blocks_50", "$attemptsTotal/50 blocks")
            if ("blocks_250" !in unlocked) put("blocks_250", "$attemptsTotal/250 blocks")
            if ("blocks_1000" !in unlocked) put("blocks_1000", "$attemptsTotal/1000 blocks")
            if ("schedules_3" !in unlocked)
                put("schedules_3", "${schedules.count { it.enabled }}/3 schedules on")
            if ("strict_10h" !in unlocked) put("strict_10h", "${strictTotal / 60}h/10h focused")
            if ("streak_3" !in unlocked) put("streak_3", "$streak/3 days")
            if ("streak_7" !in unlocked) put("streak_7", "$streak/7 days")
            if ("streak_30" !in unlocked) put("streak_30", "$streak/30 days")
            if ("goal_streak7" !in unlocked)
                put("goal_streak7", "${Goals.bestStreak(ctx)}/7 days")
        }

        return GamifyState(
            score = score,
            band = bandOf(score),
            xp = xp,
            level = LEVELS[levelIndex],
            levelIndex = levelIndex,
            nextLevel = next,
            levelProgress = progress.coerceIn(0f, 1f),
            streak = streak,
            unlocked = unlocked,
            newlyUnlocked = newly,
            progress = hints,
        )
    }

    /** Today's live Focus Score — see the component breakdown in the class doc. */
    private fun liveScore(ctx: Context, protectionOn: Boolean): Int {
        var score = 50f
        val cal = Calendar.getInstance()
        val elapsedMin = (cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE))
            .coerceAtLeast(1)
        val dayFraction = elapsedMin / 1440f

        // Screen time vs your own 30-day baseline, prorated to the time of day (±35).
        val monthly = UsageTracker.dailyMinutes(ctx, 30)
        val pastDays = monthly.dropLast(1).filter { it > 0 }
        val todayMin = monthly.lastOrNull() ?: 0
        if (pastDays.isNotEmpty()) {
            val baseline = pastDays.average().toFloat()
            val expected = baseline * dayFraction
            val delta = (expected - todayMin) / maxOf(expected, 30f)
            score += (delta * 35f).coerceIn(-35f, 35f)
        }

        // Unlocks vs your average, same proration (±15).
        val avgUnlocks = UnlockCounter.averageUnlocksPerDay(ctx)
        if (avgUnlocks > 0) {
            val expected = avgUnlocks * dayFraction
            val delta = (expected - UnlockCounter.unlocksToday(ctx)) / maxOf(expected, 5f)
            score += (delta * 15f).coerceIn(-15f, 15f)
        }

        // Urges stopped today: every block is a win (+2 each, max +10).
        val blocksToday = AttemptCounter.summary(ctx).sumOf { it.today }
        score += minOf(blocksToday * 2, 10).toFloat()

        // Focus sessions today: full credit at an hour (+15).
        score += (StatsStore.strictMinutesToday(ctx).coerceAtMost(60) / 60f) * 15f

        // Armed and protected (+10).
        if (protectionOn) score += 10f

        return score.roundToInt().coerceIn(0, 100)
    }

}
