package com.appblocker.data

import android.content.Context
import com.appblocker.service.UsageTracker
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One turn of the coach conversation. Role is "user", "model", or "local" — "local" bubbles
 *  (greeting, error notes) are shown in the UI but never sent to Gemini or persisted. */
data class ChatMsg(val role: String, val text: String)

/** One chat answer: the coach's reply plus up to 3 suggested follow-up messages the user
 *  can tap instead of typing. */
data class CoachReply(val reply: String, val suggestions: List<String>)

/**
 * The AI Coach: Gemini-powered daily tips AND a two-way chat, both grounded in the user's real
 * usage data, their current AppBlocker setup, and their long-term goals. The user's API key
 * lives ONLY in this device's SharedPreferences (pasted once in the app) — it is never baked
 * into the APK or the public repo. Tips are cached for a few hours at a time; chat costs one
 * request per message.
 */
object AiCoach {
    private const val PREFS = "ai_coach"
    private const val MODEL = "gemini-2.5-flash"
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
    private const val MAX_HISTORY = 40 // persisted chat turns; also caps the prompt size
    private const val TIPS_TTL_MS = 3 * 60 * 60 * 1000L // tips refresh every ~3 hours

    /** What the app can do, so the coach recommends real features with concrete settings
     *  instead of generic advice. */
    private val FEATURE_CATALOG = """
        AppBlocker's features (recommend these BY NAME with concrete settings):
        - Quick Block: instantly blocks a chosen set of apps; also auto-blocks those apps' websites in browsers. Has a "Shorts" sub-option that blocks only YouTube Shorts while the rest of YouTube keeps working.
        - Schedules (Blocking tab > New schedule): Time (block apps during a daily time window, e.g. 22:00-07:00, with chosen weekdays), Usage limit (allow N minutes per day per schedule, then block, e.g. 45 min), Launch count (allow N opens per day, then block), Wi-Fi (block when on a chosen network), Location (block within a radius of a saved place).
        - Strict tab: Pomodoro focus sessions (work/break cycles that block apps during work) and unstoppable strict sessions; "Prevent uninstall" protection.
        - Web filter: blocks adult content, custom keywords, and in-app purchases in browsers.
        - Hypothetical apps: pre-block popular social apps (TikTok, Instagram...) even before they are installed.
    """.trimIndent()

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun apiKey(ctx: Context): String = p(ctx).getString("key", "") ?: ""

    fun setApiKey(ctx: Context, key: String) {
        // A new key should trigger a fresh fetch rather than serving another key's cache.
        p(ctx).edit().putString("key", key.trim()).remove("tips").apply()
    }

    // --- Chat history (persisted so the conversation survives restarts) ---

    fun chatHistory(ctx: Context): List<ChatMsg> =
        p(ctx).getString("chat", null)?.let { raw ->
            runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    ChatMsg(o.getString("r"), o.getString("t"))
                }
            }.getOrNull()
        } ?: emptyList()

    fun saveChat(ctx: Context, msgs: List<ChatMsg>) {
        val keep = msgs.filter { it.role == "user" || it.role == "model" }.takeLast(MAX_HISTORY)
        val arr = JSONArray()
        keep.forEach { arr.put(JSONObject().put("r", it.role).put("t", it.text)) }
        p(ctx).edit().putString("chat", arr.toString()).apply()
    }

    fun clearChat(ctx: Context) = p(ctx).edit().remove("chat").apply()

    /** Parsed tips cache entry: the day it was fetched, when, and the raw JSON array. */
    private data class TipsCache(val day: Int, val fetchedAt: Long, val json: String)

    /** Cache format is "<day>|<fetchedAtMillis>|<json>"; the old two-part "<day>|<json>"
     *  format still parses (fetchedAt 0 = expired) so pre-1.52 tips survive as the stale
     *  fallback. Split on the first two '|' only — tip text may contain '|'. */
    private fun parseTipsCache(raw: String): TipsCache? {
        val s1 = raw.indexOf('|')
        if (s1 <= 0) return null
        val day = raw.substring(0, s1).toIntOrNull() ?: return null
        val s2 = raw.indexOf('|', s1 + 1)
        val fetchedAt = if (s2 > s1) raw.substring(s1 + 1, s2).toLongOrNull() else null
        return if (fetchedAt != null) TipsCache(day, fetchedAt, raw.substring(s2 + 1))
        else TipsCache(day, 0L, raw.substring(s1 + 1))
    }

    /**
     * The current tips: cached while fresh — fetched today AND within the last few hours
     * (unless [force]) — else one Gemini call, so tips follow the day as it changes.
     * On failure returns the last cached tips from any day, or null (caller hides/downgrades).
     */
    suspend fun dailyTips(ctx: Context, summary: String, force: Boolean = false): List<String>? =
        withContext(Dispatchers.IO) {
            val prefs = p(ctx)
            val today = todayStamp()
            val cached = prefs.getString("tips", null)?.let { parseTipsCache(it) }
            val fresh = cached != null && cached.day == today &&
                System.currentTimeMillis() - cached.fetchedAt < TIPS_TTL_MS
            if (!force && fresh) return@withContext parseTips(cached!!.json)

            val key = apiKey(ctx)
            if (key.isBlank()) return@withContext null
            val setup = runCatching { setupSnapshot(ctx) }.getOrDefault("")
            val goals = Goals.all(ctx).map { it.label() }
            val profile = runCatching { CoachProfile.promptText(ctx) }.getOrDefault("")
            val name = SettingsStore.userName(ctx).substringBefore(' ')
            // One retry to ride out transient blips, same as the updater.
            repeat(2) {
                runCatching {
                    fetchTips(key, summary, setup, goals, profile, name)
                }.getOrNull()?.let { tips ->
                    prefs.edit().putString(
                        "tips", "$today|${System.currentTimeMillis()}|${JSONArray(tips)}").apply()
                    return@withContext tips
                }
            }
            cached?.let { parseTips(it.json) } // stale tips beat no tips
        }

    /**
     * One chat turn: sends the conversation + fresh usage/setup/goal context, returns the
     * coach's reply (or null on failure). If Gemini includes an updated goal list, it is
     * saved here so both chat and daily tips see it.
     */
    suspend fun chat(ctx: Context, history: List<ChatMsg>, userMsg: String): CoachReply? =
        withContext(Dispatchers.IO) {
            val key = apiKey(ctx)
            if (key.isBlank()) return@withContext null
            val today = SimpleDateFormat("EEEE, MMM d, yyyy", Locale.US).format(Date())
            val first = SettingsStore.userName(ctx).substringBefore(' ')
            val system = buildString {
                appendLine("You are the AI Coach inside AppBlocker, a screen-time app on the phone of ${SettingsStore.userName(ctx)}. Today is $today.")
                appendLine("Personality: warm, celebratory, direct — a coach who is genuinely proud when $first makes progress. Lead with the most positive TRUE thing in the data (a streak alive, a goal hit, a number down vs last week) before advice or bad news. Use $first's first name naturally, not in every message. You may use at most 2 fitting emojis per reply (like a party popper, flame, flexed arm, check mark, or chart-down) — never more, and never inside heading lines. Structure any reply longer than two sentences: short heading lines ending in ':', bullet lines starting with '- ', numbered step lines starting with '1. ' '2. ' for ordered plans, blank lines between sections, and **bold** around the key numbers, app names and the single most important phrase. No other markdown (#, backticks, tables). Keep replies under 80 words — EXCEPT when the user asks for a report, a summary, or a plan: then reply up to 200 words using that structure so it reads like a clean report. Ask at most one question per reply.")
                appendLine("Your job: help the user understand their usage, agree on goals together, and track progress against those goals using the data below. Suggest specific app features with concrete settings when they would help.")
                appendLine("When the user wants a goal or plan for the week: propose ONE specific, measurable weekly goal grounded in the data (for example a daily-average target around 10-20% below their current average — realistic, not drastic), then give a concrete plan: which apps to limit with which feature and what setting, and what to check each day. Save the goal via the goals field prefixed 'This week: '. When a weekly goal already exists, report progress against it using the per-day numbers.")
                appendLine()
                val profileText = runCatching { CoachProfile.promptText(ctx) }.getOrDefault("")
                appendLine(
                    if (profileText.isBlank())
                        "What you know about $first personally: nothing yet — you're still getting to know them."
                    else "What you know about $first personally (learned in past chats — use it like a friend would):\n$profileText"
                )
                appendLine("Getting to know the user: you remember facts permanently through the \"profile\" field described below — chat history gets trimmed, the profile does not. When it fits the moment, weave in AT MOST ONE natural get-to-know-you question per reply — never interrogate, and skip it when the user asked a direct question that deserves a direct answer. Worth learning over time: why they want to block apps, which app and time of day tempts them most, what they'd rather do with the time they win back, their work or study rhythm, and what kind of encouragement lands with them. Whenever the user reveals something personal in ANY message, save it via \"profile\" and reference it naturally from then on.")
                appendLine()
                appendLine(FEATURE_CATALOG)
                appendLine()
                appendLine("The user's current setup:")
                appendLine(runCatching { setupSnapshot(ctx) }.getOrDefault("(unavailable)"))
                appendLine()
                appendLine("The user's usage data:")
                appendLine(runCatching { usageSummary(ctx) }.getOrDefault("(unavailable)"))
                appendLine()
                val goalsText = runCatching { Goals.promptSummary(ctx) }.getOrDefault("")
                appendLine(
                    if (goalsText.isBlank())
                        "Goals: none yet — consider helping the user agree on one measurable daily target."
                    else "The user's goals, tracked live by the app (hit = the day finishes under the target):\n$goalsText"
                )
                appendLine()
                appendLine("Reply ONLY with a JSON object: {\"reply\": string, \"goals\": array (optional), \"suggestions\": array of up to 3 short strings (optional), \"profile\": object (optional)}. \"goals\" REPLACES the user's whole goal list and must contain OBJECTS: {\"kind\": \"screen_time\" or \"app\" or \"unlocks\", \"minutes\": integer daily target (for unlocks, the unlock count), \"app\": the exact app name, only for kind app}. Include \"goals\" ONLY when the user agreed to add, change, complete or drop a goal — and when you do, also include the existing goals being kept. Goals must be measurable daily targets; never invent one the user didn't agree to. \"suggestions\" are follow-up messages the user might want to send next, phrased in the user's own voice (like \"Show me my weekly report\" or \"Which app should I limit first?\"), each under 40 characters, relevant to where the conversation is. \"profile\" is a flat object of short plain-text facts to remember about the user forever. Include ONLY keys you are adding or changing — existing keys are kept automatically; set a key to \"\" to forget it. Use snake_case keys, preferring: why_blocking, temptation_apps, temptation_times, replacement_activities, work_study_rhythm, motivation_style, wins, personal_notes. Values under 200 characters. Never store anything the user asks you to forget.")
            }

            // Gemini wants contents to start with a user turn; drop any leading model greeting.
            val turns = (history + ChatMsg("user", userMsg))
                .filter { it.role == "user" || it.role == "model" }
                .dropWhile { it.role != "user" }
            val contents = JSONArray()
            turns.forEach { m ->
                contents.put(JSONObject()
                    .put("role", m.role)
                    .put("parts", JSONArray().put(JSONObject().put("text", m.text))))
            }
            val body = JSONObject()
                .put("system_instruction", JSONObject()
                    .put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", contents)
                .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
                .toString()

            repeat(2) {
                runCatching {
                    val obj = JSONObject(post(key, body))
                    val reply = obj.getString("reply").trim()
                    obj.optJSONArray("goals")?.let { g -> applyGoalUpdate(ctx, g) }
                    obj.optJSONObject("profile")?.let { pr ->
                        runCatching { CoachProfile.merge(ctx, pr) }
                    }
                    val suggestions = obj.optJSONArray("suggestions")?.let { s ->
                        (0 until s.length()).map { s.getString(it).trim() }
                            .filter { it.isNotBlank() }.take(3)
                    } ?: emptyList()
                    if (reply.isBlank()) null else CoachReply(reply, suggestions)
                }.getOrNull()?.let { return@withContext it }
            }
            null
        }

    /** Turns the coach's goal objects into real [Goals], keeping the id (and so the whole
     *  hit history) of any goal that survives the update unchanged. */
    private suspend fun applyGoalUpdate(ctx: Context, arr: JSONArray) {
        InstalledAppsRepository.ensureLoaded(ctx)
        val apps = InstalledAppsRepository.apps.value
        val parsed = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val target = o.optInt("minutes", 0)
            if (target <= 0) return@mapNotNull null
            when (o.optString("kind")) {
                "screen_time" -> Goal(Goals.newId(), GoalKind.SCREEN_TIME, target)
                "unlocks" -> Goal(Goals.newId(), GoalKind.UNLOCKS, target)
                "app" -> {
                    val name = o.optString("app")
                    val match = apps.firstOrNull { it.label.equals(name, ignoreCase = true) }
                        ?: apps.firstOrNull { name.isNotBlank() && it.label.contains(name, true) }
                    match?.let {
                        Goal(Goals.newId(), GoalKind.APP_LIMIT, target, it.packageName, it.label)
                    }
                }
                else -> null
            }
        }
        val existing = Goals.all(ctx)
        val merged = parsed.map { new ->
            existing.firstOrNull {
                it.kind == new.kind && it.target == new.target && it.pkg == new.pkg
            } ?: new
        }
        Goals.replaceAll(ctx, merged)
    }

    /** Compact plain-text usage summary — aggregate numbers and app names only, nothing
     *  sensitive. Shared by daily tips and chat; everything reads from day-cached sources. */
    suspend fun usageSummary(ctx: Context): String {
        val snapshot = UsageTracker.todaySnapshot(ctx)
        val monthly = UsageTracker.dailyMinutes(ctx, 30)
        val weekly = monthly.copyOfRange(23, 30)
        InstalledAppsRepository.ensureLoaded(ctx)
        val labels = InstalledAppsRepository.apps.value.associate { it.packageName to it.label }
        fun label(pkg: String) = labels[pkg] ?: pkg.substringAfterLast('.')

        // Weekday vs weekend averages over the last 30 days.
        val weekdayVals = ArrayList<Int>(); val weekendVals = ArrayList<Int>()
        val cal = Calendar.getInstance()
        for (i in monthly.indices) {
            cal.timeInMillis = System.currentTimeMillis()
            cal.add(Calendar.DAY_OF_YEAR, -(monthly.size - 1 - i))
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) weekendVals.add(monthly[i])
            else weekdayVals.add(monthly[i])
        }

        // Per-app week-over-week deltas for the top apps of this week.
        val now = System.currentTimeMillis()
        val thisWeekApps = UsageTracker.appMinutesInRange(ctx, UsageTracker.startOfDayAgo(6), now)
        val lastWeekApps = UsageTracker.lastWeekAppMinutes(ctx)
        val trends = thisWeekApps.entries.filter { it.value >= 5 }
            .sortedByDescending { it.value }.take(5)
            .joinToString { (pkg, mins) ->
                val prev = lastWeekApps[pkg] ?: 0
                val delta = if (prev > 0) {
                    val pct = ((mins - prev) * 100f / prev).roundToInt()
                    if (pct >= 0) "up $pct%" else "down ${-pct}%"
                } else "new"
                "${label(pkg)} ${fmt(mins)} ($delta)"
            }

        // Per-day view of the last 7 days ("Mon 3h 2m, Tue 45m, …", oldest first, ends today)
        // so weekly reports and weekly-goal progress have real numbers to stand on.
        val dayName = SimpleDateFormat("EEE", Locale.US)
        val last7 = weekly.indices.joinToString {
            val dayCal = Calendar.getInstance()
            dayCal.add(Calendar.DAY_OF_YEAR, -(weekly.size - 1 - it))
            "${dayName.format(dayCal.time)} ${fmt(weekly[it])}"
        }

        return buildString {
            appendLine("Screen time today: ${fmt(UsageTracker.totalMinutesToday(snapshot))}")
            appendLine("7-day daily average: ${fmt(if (weekly.isNotEmpty()) weekly.sum() / weekly.size else 0)}")
            appendLine("Yesterday: ${fmt(weekly.getOrElse(5) { 0 })}")
            appendLine("Last 7 days (oldest first, ending today): $last7")
            appendLine("Weekday average: ${fmt(avg(weekdayVals))}, weekend average: ${fmt(avg(weekendVals))}")
            val top = UsageTracker.topAppsToday(snapshot, 3)
            if (top.isNotEmpty()) {
                appendLine("Top apps today: " +
                    top.joinToString { "${label(it.packageName)} (${fmt(it.minutes)})" })
            }
            if (trends.isNotBlank()) appendLine("This week vs last week: $trends")
            appendLine("Blocked-app open attempts today: ${AttemptCounter.summary(ctx).sumOf { it.today }}")
            appendLine("Phone unlocks today: ${UnlockCounter.unlocksToday(ctx)}")
        }
    }

    /** The user's current blocking setup, so advice never suggests what's already in place. */
    suspend fun setupSnapshot(ctx: Context): String {
        val db = BlockerDatabase.get(ctx)
        val rules = db.appRuleDao().getAll().first().filter { it.isBlocked }
        val schedules = db.scheduleDao().getAll().first()
        val session = QuickSession.state(ctx)
        return buildString {
            appendLine("Quick Block: " +
                when {
                    rules.isEmpty() -> "no apps selected"
                    SettingsStore.quickBlockPaused(ctx) -> "paused, ${rules.size} apps selected"
                    else -> "active on ${rules.size} apps (" +
                        rules.take(8).joinToString { it.appLabel } +
                        (if (rules.size > 8) ", ..." else "") + ")"
                })
            if (session.active) appendLine("Focus session running now: ${session.label}")
            if (SettingsStore.blockYoutubeShorts(ctx)) appendLine("YouTube Shorts blocking: on")
            appendLine("Web filter: adult content ${onOff(SettingsStore.blockAdult(ctx))}, " +
                "purchases ${onOff(SettingsStore.blockPurchases(ctx))}")
            if (schedules.isEmpty()) {
                appendLine("Schedules: none set up yet")
            } else {
                appendLine("Schedules:")
                schedules.forEach { s ->
                    val detail = when (s.type) {
                        ScheduleType.TIME -> "daily ${hm(s.startMinutes)}-${hm(s.endMinutes)}"
                        ScheduleType.USAGE_LIMIT -> "${s.limitMinutes} min/day limit"
                        ScheduleType.LAUNCH_COUNT -> "${s.limitCount} opens/day limit"
                        ScheduleType.WIFI -> "on Wi-Fi ${s.wifiSsid.ifBlank { "(any)" }}"
                        ScheduleType.LOCATION -> "at a saved location"
                    }
                    appendLine("- \"${s.name}\" (${s.type.name.lowercase().replace('_', ' ')}, " +
                        "$detail, ${s.packages.size} apps, " +
                        (if (s.enabled) "enabled" else "disabled") + ")")
                }
            }
        }
    }

    private fun parseTips(jsonArray: String): List<String>? = runCatching {
        val arr = JSONArray(jsonArray)
        (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun fetchTips(
        key: String,
        summary: String,
        setup: String,
        goals: List<String>,
        profile: String,
        name: String,
    ): List<String>? {
        val prompt = buildString {
            appendLine("You are $name's personal screen-time coach inside an app-blocker app. Based on their usage data, reply with ONLY a JSON array of 2 or 3 short tips (strings, max 120 characters each). Tone: celebratory and progress-first — if any number improved, a goal was hit, or a streak is alive, the FIRST tip must celebrate it with the real number. Each tip must be specific to the data, actionable, encouraging, plain language. You may use at most ONE emoji per tip (like a party popper, flame, flexed arm, check mark, or chart-down). Each tip is ONE plain sentence: no markdown, no ** marks, no headings. Recommend the app's features BY NAME with concrete settings where they'd help, but never suggest something already set up. Use what you know about $name to make tips feel personal.")
            appendLine()
            appendLine(FEATURE_CATALOG)
            appendLine()
            if (setup.isNotBlank()) { appendLine("Current setup:"); appendLine(setup); appendLine() }
            if (goals.isNotEmpty()) {
                appendLine("Long-term goals:"); goals.forEach { appendLine("- $it") }; appendLine()
            }
            if (profile.isNotBlank()) {
                appendLine("What you know about $name:"); appendLine(profile); appendLine()
            }
            appendLine("Data:")
            append(summary)
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
            .toString()
        return parseTips(post(key, body))
    }

    /** POSTs [body] to Gemini and returns the model's text part (throws on any failure).
     *  Internal so other Gemini-backed features (e.g. [AiCategorizer]) reuse the same pipe. */
    internal fun post(key: String, body: String): String {
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", key)
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val response = conn.inputStream.bufferedReader().use { it.readText() }
        return JSONObject(response)
            .getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
            .getString("text")
    }

    private fun onOff(b: Boolean) = if (b) "on" else "off"
    private fun avg(v: List<Int>) = if (v.isEmpty()) 0 else v.average().roundToInt()
    private fun hm(m: Int) = "%d:%02d".format(m / 60, m % 60)
    private fun fmt(minutes: Int): String =
        if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}
