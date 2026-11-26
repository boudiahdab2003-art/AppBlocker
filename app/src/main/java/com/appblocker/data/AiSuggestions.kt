package com.appblocker.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * **Suggestions v2: picked by the AI coach** (invariant 115).
 *
 * 26 Nov 2026, his verdict on v1: "very very dumb" — it suggested apps he needs, always "1 h",
 * missed his real problem, and "doesn't target social media and not creative". His picks: **the AI
 * picks them**, and **only distracting apps** (social media, video, games, browsers — never a
 * messenger, maps, banking, school or work).
 *
 * The coach is shown the candidates (distracting apps only, with today's minutes, the week's
 * average, the trend and today's opens), his streak, relapse times and urge presses (his 26 Nov
 * choice for the coach), what he told the coach about his temptations, and what already protects
 * him — and asked for 1-3 varied, specific buttons that go after the real problem, especially the
 * next risky hour. Every button is a [CoachActions] action, so it can only make blocking stronger,
 * and each is re-checked against the candidates on this phone before it is shown.
 *
 * Cached for [TTL_MS]; the rule-based [SmartSuggestions] stand in while there is no fresh answer.
 */
object AiSuggestions {

    data class Pick(
        val emoji: String,
        val title: String,
        val reason: String,
        val action: CoachActions.Action,
    )

    /** One app the coach may suggest something for. */
    data class Candidate(
        val pkg: String,
        val label: String,
        val todayMinutes: Int,
        val weekAvgMinutes: Int,
        /** This week's minutes against the week before, in percent; null when there is no before. */
        val trendPct: Int?,
        val opensToday: Int,
        val isBrowser: Boolean,
    )

    const val TTL_MS = 2 * 60 * 60_000L
    const val MAX = 3

    private const val PREFS = "ai_coach"
    private const val KEY = "suggest_cache"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The cached answer: when it was fetched and the raw JSON, or null. */
    fun cached(ctx: Context): Pair<Long, String>? {
        val raw = p(ctx).getString(KEY, null) ?: return null
        val sep = raw.indexOf('|')
        if (sep <= 0) return null
        val at = raw.substring(0, sep).toLongOrNull() ?: return null
        return at to raw.substring(sep + 1)
    }

    fun isFresh(fetchedAt: Long, now: Long): Boolean = now - fetchedAt in 0 until TTL_MS

    /**
     * Asks the coach for fresh picks and caches the raw answer. Null on any failure — the card
     * keeps showing what it had. Network and model choice are the coach's own ([AiCoach.callGemini]).
     */
    suspend fun refresh(ctx: Context, candidates: List<Candidate>): String? {
        if (candidates.isEmpty()) return null
        if (!AiCoach.coachAvailable(ctx)) return null
        val prompt = prompt(
            name = DisplayName.possessiveSubject(SettingsStore.userName(ctx)),
            now = System.currentTimeMillis(),
            candidates = candidates,
            recovery = runCatching {
                AiCoach.recoveryPromptText(
                    CleanStreak.startedAt(ctx), CleanStreak.bestMs(ctx), CleanStreak.resets(ctx),
                    AiCoach.urges(ctx), System.currentTimeMillis(),
                )
            }.getOrDefault(""),
            profile = runCatching { CoachProfile.promptText(ctx) }.getOrDefault(""),
            setup = runCatching { AiCoach.setupSnapshot(ctx) }.getOrDefault(""),
        )
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject()
                .put("responseMimeType", "application/json")
                .put("maxOutputTokens", AiCoach.CHAT_MAX_OUTPUT_TOKENS))
        val raw = runCatching {
            AiCoach.callGemini(ctx, AiCoach.apiKey(ctx), body, AiCoach.COACH_CHAIN)
        }.getOrNull() ?: return null
        // Only an answer that yields at least one usable pick replaces the cache.
        if (parse(raw, candidates.map { CoachActions.App(it.pkg, it.label) }) { true }.isEmpty()) return null
        p(ctx).edit().putString(KEY, "${System.currentTimeMillis()}|$raw").apply()
        return raw
    }

    /**
     * The picks in [raw] that can be shown: each action resolved to one of [apps] and allowed by
     * [eligible] (Strict needs no app). Pure. Titles and reasons are trimmed to fit a row.
     */
    fun parse(raw: String, apps: List<CoachActions.App>, eligible: (String) -> Boolean): List<Pick> {
        val arr = runCatching { JSONObject(raw).optJSONArray("suggestions") }.getOrNull() ?: return emptyList()
        val out = ArrayList<Pick>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val action = CoachActions.parse(
                JSONArray().put(o.optJSONObject("action") ?: continue), apps, eligible,
            ).firstOrNull() ?: continue
            if (out.any { it.action.id == action.id }) continue
            val title = o.optString("title").trim().take(48)
            val reason = o.optString("reason").trim().take(110)
            if (title.isBlank()) continue
            out += Pick(o.optString("emoji").trim().take(4).ifBlank { "✨" }, title, reason, action)
            if (out.size == MAX) break
        }
        return out
    }

    /** From this hour a browser lock or a lock until morning makes sense (measured 26 Nov 2026: at
     *  14:10 the model offered to lock Chrome until 06:00 — sixteen hours of a study tool). */
    const val EVENING_HOUR = 20

    /**
     * Whether [a] suits [hour] — enforced, not only asked for: no lock until morning before
     * [EVENING_HOUR], and no browser lock before it either. Pure.
     */
    fun suitableNow(a: CoachActions.Action, hour: Int, browsers: Set<String>): Boolean {
        val evening = hour >= EVENING_HOUR || hour < SmartSuggestions.NIGHT_UNTIL_HOUR
        if (a.type == CoachActions.Type.LOCK_TONIGHT && !evening) return false
        if (a.pkg != null && a.pkg in browsers && !evening) return false
        return true
    }

    /** A daily limit is only worth a button for an app averaging at least this many minutes a
     *  day (measured 26 Nov 2026: the model offered "15 min/day" for an app averaging 0). */
    const val LIMIT_MIN_AVG = 30

    /**
     * Whether [a] is worth a button given what [c] (its app, or null for Strict) shows — enforced
     * here, not only asked for: a limit needs a real average to cut, and must cut it. Pure.
     */
    fun worthIt(a: CoachActions.Action, c: Candidate?): Boolean = when (a.type) {
        CoachActions.Type.DAILY_LIMIT ->
            c != null && c.weekAvgMinutes >= LIMIT_MIN_AVG && a.minutes < c.weekAvgMinutes
        else -> true
    }

    /** The question, built from plain values so the test can read exactly what is asked. */
    internal fun prompt(
        name: String,
        now: Long,
        candidates: List<Candidate>,
        recovery: String,
        profile: String,
        setup: String,
    ): String = buildString {
        val clock = SimpleDateFormat("EEEE HH:mm", Locale.US).format(Date(now))
        appendLine("You choose 1 to 3 one-tap blocking suggestions for $name, shown as buttons at the top of AppBlocker. It is $clock now.")
        appendLine("Their real problem is social media, short videos and endless scrolling — and late-night browsing that ends in a relapse. Go after THAT: prefer social media and video apps, and the hours that are risky for them. Never suggest anything for an app they need; you may only use the apps listed under CANDIDATES.")
        appendLine("A daily limit only makes sense for an app used at least $LIMIT_MIN_AVG minutes a day on average, and it must be well below that average; never suggest a limit for an app barely used.")
        appendLine("Browsers are often needed for study and work: suggest locking a browser only from $EVENING_HOUR:00 on, and use \"lock_until_morning\" only from $EVENING_HOUR:00 on — never a lock until morning in the middle of the day.")
        appendLine("Be creative and specific, not generic: vary the action AND the length between suggestions (never three \"lock for 1 hour\"), size each one to the data (a 3-hour binge deserves more than an hour; a nightly pattern deserves a lock until morning, started BEFORE the risky hour), and use Strict Mode when several apps are the problem at once. Every reason must cite something real from the data below — an app's minutes, its rise this week, how often they open it, or the time their relapses or urges happen. Never mention adult content or name any site. Encouraging tone, no shaming.")
        appendLine()
        appendLine("Reply ONLY with JSON: {\"suggestions\": [{\"emoji\": one emoji, \"title\": under 40 characters, what the button does in plain words, \"reason\": under 90 characters, why now, citing the data, \"action\": one of {\"type\": \"lock_app\", \"app\": exact candidate name, \"minutes\": 15-720} | {\"type\": \"lock_until_morning\", \"app\": exact candidate name} | {\"type\": \"daily_limit\", \"app\": exact candidate name, \"minutes\": 5-240} | {\"type\": \"strict\", \"minutes\": 15-1440}}]}. If nothing is worth suggesting right now, return an empty list.")
        appendLine()
        appendLine("CANDIDATES (distracting apps on their phone, most used first):")
        candidates.forEach { c ->
            val trend = c.trendPct?.let { if (it >= 0) ", up $it% on last week" else ", down ${-it}% on last week" }.orEmpty()
            val kind = if (c.isBrowser) " [browser]" else ""
            appendLine("- ${c.label}$kind: ${c.todayMinutes} min today, ${c.weekAvgMinutes} min/day this week$trend, opened ${c.opensToday}x today")
        }
        if (recovery.isNotBlank()) {
            appendLine()
            appendLine("Their recovery (handle with care):")
            appendLine(recovery)
        }
        if (profile.isNotBlank()) {
            appendLine()
            appendLine("What they told their coach:")
            appendLine(profile)
        }
        if (setup.isNotBlank()) {
            appendLine()
            appendLine("What already protects them (never suggest what is already in place):")
            appendLine(setup)
        }
    }
}
