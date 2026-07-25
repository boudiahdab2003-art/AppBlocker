package com.appblocker.data

import android.content.Context
import android.util.Log
import com.appblocker.BuildConfig
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
    private const val TAG = "AiCoach"
    private const val PREFS = "ai_coach"
    private const val MODEL_REPROBE_MS = 7L * 24 * 60 * 60 * 1000
    // Sent per request. The coach model has a large context window and the system prompt carries
    // the durable context, but continuity is most of what makes the coach feel like it knows the
    // user — a 16-turn window was the main reason it seemed to forget mid-conversation.
    private const val MAX_HISTORY_STORED = 120 // persisted chat turns
    private const val MAX_HISTORY_SENT = 40 // turns sent per request
    private const val TIPS_TTL_MS = 60 * 60 * 1000L // tips refresh every hour
    private const val READ_TIMEOUT_MS = 30_000
    // Deep-thinking replies are expected to take tens of seconds; the old 30s ceiling would have
    // failed exactly the answers this upgrade is for. The chat screen shows a typing indicator
    // throughout, and tips serve the cached list while refreshing, so a long wait is visible
    // rather than a freeze.
    //
    // 90s, not the 120s first written: a reply is expected within ~30s, so this is already 3x
    // headroom, and the ceiling is what the user actually waits through when a request hangs.
    private const val DEEP_READ_TIMEOUT_MS = 90_000

    /**
     * Whether a failed attempt is worth repeating.
     *
     * The retry exists for transient blips — a 5xx, a dropped connection, a truncated response
     * that wouldn't parse. A **timeout** is none of those: it means the far end already had the
     * full read window and didn't answer, so a second go is the least likely to help and by far
     * the most expensive. With deep thinking the read ceiling is [DEEP_READ_TIMEOUT_MS], so
     * retrying one doubled the worst case to minutes of "Thinking…" before the user saw anything.
     *
     * Note the null case must answer **true**: a call can succeed and still yield nothing usable
     * (an unparsable tips array, a blank reply), and that is precisely the blip the retry is for.
     * Only a timeout is excluded.
     */
    private fun retryWorthwhile(t: Throwable?): Boolean =
        t !is java.io.InterruptedIOException // SocketTimeoutException extends this; null => true

    /** How hard the model should think before answering. The coach wants depth over speed; bulk
     *  jobs like app categorisation want neither. Mapped per model generation in [postWithThinking]
     *  because 2.x takes a numeric `thinkingBudget` and 3.x a `thinkingLevel` enum. */
    internal enum class Thinking { OFF, HIGH }

    /**
     * An ordered list of models to try, best first, plus how hard to think.
     *
     * Two things make this a chain rather than a constant. **One:** the same pipe serves the coach
     * and [AiCategorizer], and they want opposite trade-offs — deep reasoning over the user's data
     * versus a cheap, fast label for a package name. **Two:** Gemini model ids churn, and this app
     * is built in an environment that cannot reach the API to check which ids are live. So rather
     * than betting on one id, the chain is tried in order and the first that answers is remembered
     * for [MODEL_REPROBE_MS] — an id that does not exist costs one 404, once a week.
     *
     * That means the ids below are a *preference order*, not a verified list. To pin an exact
     * model, put it first; the rest stay as the safety net.
     */
    internal data class ModelChain(
        val name: String,
        val models: List<String>,
        val thinking: Thinking,
    )

    /** The coach: best available Pro model, thinking hard. Flash entries are the last resort so a
     *  wrong guess degrades to today's behaviour instead of to no coach at all. */
    internal val COACH_CHAIN = ModelChain(
        name = "coach",
        models = listOf(
            "gemini-3.5-pro",
            "gemini-3-pro",
            "gemini-2.5-pro",
            "gemini-3.5-flash",
            "gemini-2.5-flash",
        ),
        thinking = Thinking.HIGH,
    )

    /** Bulk/utility work (app categorisation): fast tier, no thinking — unchanged behaviour. */
    internal val UTILITY_CHAIN = ModelChain(
        name = "utility",
        models = listOf("gemini-3.5-flash", "gemini-2.5-flash"),
        thinking = Thinking.OFF,
    )

    /** The model that last answered for [chain] — so the owner can confirm the upgrade actually
     *  landed on a Pro model rather than silently falling through to Flash. */
    internal fun lastModelUsed(ctx: Context, chain: ModelChain = COACH_CHAIN): String? =
        p(ctx).getString("model_ok_${chain.name}", null)

    // --- Server proxy (docs/SERVER.md #1) ---
    // When a proxy URL is baked in (BuildConfig, from gradle.properties), the coach routes
    // through our VM — which holds the Gemini key — so it works with no on-device key. Empty =
    // off (fall back to the user's own key). The proxy takes the SAME request path and body;
    // it just swaps our auth for Google's key server-side.
    private fun proxyOn() = BuildConfig.COACH_PROXY_URL.isNotBlank()

    /** True when the coach can run at all: either the server proxy is configured, or the user
     *  has entered their own Gemini key. */
    fun coachAvailable(ctx: Context) = proxyOn() || apiKey(ctx).isNotBlank()

    private fun endpoint(model: String, viaProxy: Boolean): String {
        val base = if (viaProxy) BuildConfig.COACH_PROXY_URL.trimEnd('/')
        else "https://generativelanguage.googleapis.com"
        return "$base/v1beta/models/$model:generateContent"
    }

    /** What the app can do, so the coach recommends real features with concrete settings
     *  instead of generic advice. */
    private val FEATURE_CATALOG = """
        AppBlocker's features (recommend these BY NAME with concrete settings):
        - Quick Block: instantly blocks a chosen set of apps; also auto-blocks those apps' websites in browsers. Has a "Shorts" sub-option that blocks only YouTube Shorts while the rest of YouTube keeps working.
        - Schedules (Blocking tab > New schedule): Time (block apps during a daily time window, e.g. 22:00-07:00, with chosen weekdays), Usage limit (allow N minutes per day per schedule, then block, e.g. 45 min), Launch count (allow N opens per day, then block), Wi-Fi (block when on a chosen network), Location (block within a radius of a saved place).
        - Strict tab: Pomodoro focus sessions (work/break cycles that block apps during work) and unstoppable strict sessions; "Prevent uninstall" protection.
        - Blocked words (Blocking tab > Blocked words): blocks any chosen word or site in browsers — or in EVERY app with one toggle; includes a built-in adult-content word pack (English + Arabic, on by default) that is deliberately hard to switch off.
        - Web filter also blocks in-app purchases in browsers.
        - App lists are grouped into 12 categories (Social media, Entertainment, Games...) — a whole category can be blocked with ONE tap on its checkbox in Quick Block or any schedule.
        - Hypothetical apps: pre-block popular social apps (TikTok, Instagram...) even before they are installed.
        - The block screen itself motivates: every blocked open shows a giant "minutes reclaimed today" counter (3 min per dodged open) and a motivational quote.
        - Insights tab: screen time charts, balance vs awake time, focus/distraction stats, a daily mood check-in, and the user's goals with live progress bars and streaks.
    """.trimIndent()

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Vestigial: the coach is served by the VM proxy now, so no key is entered on-device. Kept
    // only because callGemini's transient-failure fallback still reads it (always blank now).
    fun apiKey(ctx: Context): String = p(ctx).getString("key", "") ?: ""

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
        val keep = msgs.filter { it.role == "user" || it.role == "model" }.takeLast(MAX_HISTORY_STORED)
        val arr = JSONArray()
        keep.forEach { arr.put(JSONObject().put("r", it.role).put("t", it.text)) }
        p(ctx).edit().putString("chat", arr.toString()).apply()
    }

    fun clearChat(ctx: Context) = p(ctx).edit().remove("chat").apply()

    // --- What the coach has advised (so it can follow its own advice up) ---

    private const val KEY_ADVICE = "advice_log"
    private const val MAX_ADVICE = 10

    /**
     * The last few things the coach recommended, each stamped with the day it said it.
     *
     * Without this the coach had no idea whether its own advice was ever taken: it could re-suggest
     * a limit the user set days ago, and it could never say "that 45-minute cap you added on Monday
     * is holding". A real coach remembers what they told you — this is the smallest thing that makes
     * it one. Kept deliberately short and dated so it stays a memory, not a transcript.
     *
     * Stored as `dayStamp|text` lines, newest last, in the same device-only prefs as the rest of the
     * coach's data. An unparsable line is dropped rather than breaking the list.
     */
    internal fun adviceLog(ctx: Context): List<Pair<Int, String>> =
        p(ctx).getStringSet(KEY_ADVICE, emptySet()).orEmpty()
            .mapNotNull { entry ->
                val sep = entry.indexOf('|')
                val day = entry.take(sep.coerceAtLeast(0)).toIntOrNull() ?: return@mapNotNull null
                val text = entry.substring(sep + 1).takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                day to text
            }
            .sortedBy { it.first }

    private fun recordAdvice(ctx: Context, text: String) {
        val trimmed = text.trim().take(200)
        if (trimmed.isBlank()) return
        val kept = (adviceLog(ctx) + (todayStamp() to trimmed)).takeLast(MAX_ADVICE)
        p(ctx).edit().putStringSet(
            KEY_ADVICE, kept.map { (day, t) -> "$day|$t" }.toSet(),
        ).apply()
    }

    /** The ledger as prompt lines ("- 3 days ago: …"), or "" when the coach hasn't advised yet. */
    private fun advicePromptText(ctx: Context): String {
        val today = todayStamp()
        return adviceLog(ctx).joinToString("\n") { (day, text) ->
            val ago = dayGap(today, day)
            val when_ = when {
                ago <= 0 -> "today"
                ago == 1 -> "yesterday"
                else -> "$ago days ago"
            }
            "- $when_: $text"
        }
    }

    fun clearAdviceLog(ctx: Context) = p(ctx).edit().remove(KEY_ADVICE).apply()

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
            if (key.isBlank() && !proxyOn()) return@withContext null
            val setup = runCatching { setupSnapshot(ctx) }.getOrDefault("")
            val goals = Goals.all(ctx).map { it.label() }
            val profile = runCatching { CoachProfile.promptText(ctx) }.getOrDefault("")
            val name = SettingsStore.userName(ctx).substringBefore(' ')
            // One retry to ride out transient blips, same as the updater — but never after a
            // timeout (see retryWorthwhile). A while loop, not repeat(2): `return@repeat` would
            // continue to the next attempt rather than abandon it, which is the opposite.
            var triesLeft = 2
            while (triesLeft-- > 0) {
                val attempt = runCatching {
                    fetchTips(ctx, key, summary, setup, goals, profile, name)
                }
                attempt.getOrNull()?.let { tips ->
                    prefs.edit().putString(
                        "tips", "$today|${System.currentTimeMillis()}|${JSONArray(tips)}").apply()
                    return@withContext tips
                }
                if (!retryWorthwhile(attempt.exceptionOrNull())) break
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
            if (key.isBlank() && !proxyOn()) return@withContext null
            val today = SimpleDateFormat("EEEE, MMM d, yyyy", Locale.US).format(Date())
            val nowTime = SimpleDateFormat("H:mm", Locale.US).format(Date())
            val first = SettingsStore.userName(ctx).substringBefore(' ')
            val system = buildString {
                appendLine("You are the AI Coach inside AppBlocker, a screen-time app on the phone of ${SettingsStore.userName(ctx)}. Today is $today, and the time right now is $nowTime — the day is still in progress.")
                appendLine("Personality: warm, celebratory, direct — a coach who is genuinely proud when $first makes progress. Lead with the most positive TRUE thing in the data (a streak alive, a goal hit, a number down vs last week) before advice or bad news. Use $first's first name naturally, not in every message.")
                appendLine("How to write: like a person talking, not like a form. Short natural paragraphs. Match the length to what was asked — a sentence or two for a small question, more when $first asks for a plan, a report or an explanation, and don't pad to fill space. Use a list ONLY when you are genuinely enumerating steps or options; never break an ordinary thought into bullets. **Bold** at most one thing that really matters (usually a number) — not by rule, and not in every reply. No headings unless the reply is long enough to genuinely need them. No other markdown (#, backticks, tables). At most 2 fitting emojis, often zero. Ask at most one question per reply. Vary how you open — never start consecutive replies the same way.")
                appendLine("Your job: help the user understand their usage, agree on goals together, and track progress against those goals using the data below. Suggest specific app features with concrete settings when they would help.")
                appendLine("Today's numbers are PARTIAL — the day is not over. Never declare a daily goal hit today or call today a win; the strongest claim allowed is \"on track\", tied to the clock (like \"only 40m by 14:00\"). Judge today against \"by this same time yesterday\" rather than full-day averages, and treat a phone-free stretch that includes the night as sleep, not willpower. Finished days (yesterday, streaks, weekly trends) are fair game to celebrate.")
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
                val advice = runCatching { advicePromptText(ctx) }.getOrDefault("")
                if (advice.isBlank()) {
                    appendLine("You have not given $first any concrete advice yet.")
                } else {
                    appendLine("What YOU advised recently, and when you said it:")
                    appendLine(advice)
                    appendLine("Check your own advice against what actually happened since — the per-day numbers above cover the last 7 days. If a suggestion was taken and worked, say so with the number. If it was taken and did NOT help, admit that and change the plan rather than repeating it. If it was clearly never acted on, ask once, without nagging. Never re-suggest something you already recommended as if it were a new idea.")
                }
                appendLine()
                val goalsText = runCatching { Goals.promptSummary(ctx) }.getOrDefault("")
                appendLine(
                    if (goalsText.isBlank())
                        "Goals: none yet — consider helping the user agree on one measurable daily target."
                    else "The user's goals, tracked live by the app (hit = the day FINISHES under the target — today's value is live and today is not finished):\n$goalsText"
                )
                appendLine()
                appendLine("Reply ONLY with a JSON object: {\"reply\": string, \"goals\": array (optional), \"suggestions\": array of up to 3 short strings (optional), \"profile\": object (optional), \"advice\": string (optional)}. \"advice\" is ONE short line recording what you just recommended, written ONLY when this reply contained a concrete recommendation or plan — it is how you remember your own advice and follow it up later. Never write \"advice\" for a reply that was only conversation, encouragement or an answer to a question. \"goals\" REPLACES the user's whole goal list and must contain OBJECTS: {\"kind\": \"screen_time\" or \"app\" or \"unlocks\", \"minutes\": integer daily target (for unlocks, the unlock count), \"app\": the exact app name, only for kind app}. Include \"goals\" ONLY when the user agreed to add, change, complete or drop a goal — and when you do, also include the existing goals being kept. Goals must be measurable daily targets; never invent one the user didn't agree to. \"suggestions\" are follow-up messages the user might want to send next, phrased in the user's own voice (like \"Show me my weekly report\" or \"Which app should I limit first?\"), each under 40 characters, relevant to where the conversation is. \"profile\" is a flat object of short plain-text facts to remember about the user forever. Include ONLY keys you are adding or changing — existing keys are kept automatically; set a key to \"\" to forget it. Use snake_case keys, preferring: why_blocking, temptation_apps, temptation_times, replacement_activities, work_study_rhythm, motivation_style, wins, personal_notes. Values under 200 characters. Never store anything the user asks you to forget.")
            }

            // Gemini wants contents to start with a user turn; drop any leading model greeting.
            // Only the most recent turns are sent — the system prompt carries the durable
            // context (profile, goals, setup, usage), so older turns just add latency.
            val turns = (history + ChatMsg("user", userMsg))
                .filter { it.role == "user" || it.role == "model" }
                .takeLast(MAX_HISTORY_SENT)
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

            var triesLeft = 2
            while (triesLeft-- > 0) {
                val attempt = runCatching {
                    val obj = JSONObject(callGemini(ctx, key, body, COACH_CHAIN))
                    val reply = obj.getString("reply").trim()
                    obj.optJSONArray("goals")?.let { g -> applyGoalUpdate(ctx, g) }
                    obj.optJSONObject("profile")?.let { pr ->
                        runCatching { CoachProfile.merge(ctx, pr) }
                    }
                    obj.optString("advice").takeIf { it.isNotBlank() }?.let { a ->
                        runCatching { recordAdvice(ctx, a) }
                    }
                    val suggestions = obj.optJSONArray("suggestions")?.let { s ->
                        (0 until s.length()).map { s.getString(it).trim() }
                            .filter { it.isNotBlank() }.take(3)
                    } ?: emptyList()
                    if (reply.isBlank()) null else CoachReply(reply, suggestions)
                }
                attempt.getOrNull()?.let { return@withContext it }
                if (!retryWorthwhile(attempt.exceptionOrNull())) break
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

        val nowCal = Calendar.getInstance()
        val minuteOfDay = nowCal.get(Calendar.HOUR_OF_DAY) * 60 + nowCal.get(Calendar.MINUTE)
        return buildString {
            appendLine("Time right now: ${hm(minuteOfDay)} — the day is ${minuteOfDay * 100 / 1440}% over. Every \"today\" number below is a running count for this UNFINISHED day, not a final daily total.")
            appendLine("Screen time so far today: ${fmt(UsageTracker.totalMinutesToday(snapshot))}")
            runCatching {
                val yStart = UsageTracker.startOfDayAgo(1)
                val byNow = UsageTracker.totalMinutesInRange(ctx, yStart, yStart + minuteOfDay * 60_000L)
                if (byNow > 0) appendLine("By this same time yesterday: ${fmt(byNow)}")
            }
            appendLine("7-day daily average: ${fmt(if (weekly.isNotEmpty()) weekly.sum() / weekly.size else 0)}")
            appendLine("30-day daily average: ${fmt(avg(monthly.toList()))}")
            appendLine("Yesterday: ${fmt(weekly.getOrElse(5) { 0 })}")
            appendLine("Last 7 days (oldest first, ending today): $last7")
            appendLine("Weekday average: ${fmt(avg(weekdayVals))}, weekend average: ${fmt(avg(weekendVals))}")
            val top = UsageTracker.topAppsToday(snapshot, 3)
            if (top.isNotEmpty()) {
                appendLine("Top apps today: " +
                    top.joinToString { "${label(it.packageName)} (${fmt(it.minutes)})" })
            }
            if (trends.isNotBlank()) appendLine("This week vs last week: $trends")

            // --- Rich context: patterns, behavior and how the user FEELS about it. Every
            // block is wrapped so one missing permission never breaks the whole summary. ---
            runCatching {
                val hourly = UsageTracker.hourlyMinutesToday(ctx)
                val peak = hourly.indices.maxByOrNull { hourly[it] } ?: return@runCatching
                if (hourly[peak] >= 10) {
                    appendLine("Busiest hour today: $peak:00-${peak + 1}:00 (${fmt(hourly[peak])})")
                }
            }
            runCatching {
                val cats = UsageTracker.categoryMinutesToday(snapshot)
                    .entries.sortedByDescending { it.value }.take(4)
                if (cats.isNotEmpty()) {
                    appendLine("Time by category today: " +
                        cats.joinToString { "${AppCategories.parse(it.key).label} ${fmt(it.value)}" })
                }
            }
            runCatching {
                val opens = LaunchCounter.opensTodayByApp(ctx)
                    .entries.sortedByDescending { it.value }.take(3)
                if (opens.isNotEmpty()) {
                    appendLine("Most-opened apps today: " +
                        opens.joinToString { "${label(it.key)} (${it.value} opens)" })
                }
            }
            runCatching {
                val (longestUse, longestGap) = UsageTracker.sessionStatsToday(ctx)
                if (longestUse > 0) appendLine(
                    "Longest continuous phone use today: ${fmt(longestUse)}; " +
                        "longest phone-free stretch: ${fmt(longestGap)} " +
                        "(a stretch that includes the night or early morning is mostly sleep, not willpower)")
            }
            val attempts = AttemptCounter.summary(ctx).filter { it.today > 0 }
            appendLine("Blocked-app open attempts today: ${attempts.sumOf { it.today }}" +
                if (attempts.isEmpty()) "" else " (" + attempts.take(4).joinToString {
                    val name = when (it.key) {
                        "web" -> "websites"; "shorts" -> "YouTube Shorts"
                        "strict_guard" -> "settings during Strict"
                        else -> label(it.key)
                    }
                    "$name ${it.today}x"
                } + ")")
            appendLine("Estimated minutes reclaimed today (3 min per blocked open): " +
                attempts.sumOf { it.today } * 3)
            appendLine("Phone unlocks today: ${UnlockCounter.unlocksToday(ctx)}")
            runCatching {
                val n = NotificationCounter.notificationsToday(ctx)
                if (n > 0) appendLine("Notifications received today: $n")
            }
            runCatching {
                val moods = MoodStore.history(ctx, 7)
                if (moods.isNotEmpty()) {
                    appendLine("The user's own daily check-ins, 0=very distracted 100=in control (IMPORTANT: this is how they FEEL — acknowledge it):")
                    moods.take(4).forEach { (ago, rating, note) ->
                        val day = if (ago == 0) "today" else if (ago == 1) "yesterday" else "$ago days ago"
                        appendLine("- $day: $rating/100" + if (note.isBlank()) "" else " — \"$note\"")
                    }
                }
            }
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
            runCatching {
                val words = db.blockedKeywordDao().getAll().first().size
                appendLine("Blocked words: $words custom" +
                    ", adult word pack ${onOff(SettingsStore.adultWordsPack(ctx))}" +
                    ", blocked ${if (SettingsStore.keywordsEverywhere(ctx)) "in every app" else "in browsers only"}")
            }
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
        ctx: Context,
        key: String,
        summary: String,
        setup: String,
        goals: List<String>,
        profile: String,
        name: String,
    ): List<String>? {
        val prompt = buildString {
            appendLine("You are $name's personal screen-time coach inside an app-blocker app. Based on their usage data, reply with ONLY a JSON array of 2 or 3 short tips (strings, max 120 characters each). Tone: celebratory and progress-first — if any number improved, a goal was hit, or a streak is alive, the FIRST tip must celebrate it with the real number. Each tip must be specific to THIS data and sound like a person who has actually looked at it — name the app, the hour or the number that prompted it. Never write a tip that would be true for any user; if a tip would still make sense with different numbers, it is too generic to send. Each tip is ONE plain sentence: no markdown, no ** marks, no headings. At most ONE emoji per tip, and only when it fits. Recommend the app's features BY NAME with concrete settings where they'd help, but never suggest something already set up. Use what you know about $name to make tips feel personal.")
            appendLine("The time right now is ${SimpleDateFormat("H:mm", Locale.US).format(Date())} — today's numbers are partial, the day is not over. Celebrate only FINISHED wins (yesterday's goal hit, a streak, a week-over-week drop); for today say \"on track\" at most, never \"goal hit\", and never praise a phone-free stretch that was just the night's sleep.")
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
        return parseTips(callGemini(ctx, key, body, COACH_CHAIN))
    }

    /**
     * Sends [body] (WITHOUT a thinkingConfig — it's injected per model here) to the best model
     * [chain] offers and returns the text part.
     *
     * [chain] decides both which models to try and how hard they think, because this same pipe
     * serves the coach and [AiCategorizer] and they want opposite things — pass [COACH_CHAIN] for
     * anything the user reads, [UTILITY_CHAIN] (the default) for bulk work. Throws on total failure.
     */
    internal fun callGemini(
        ctx: Context,
        key: String,
        body: JSONObject,
        chain: ModelChain = UTILITY_CHAIN,
    ): String {
        return try {
            attemptWithModelFallback(ctx, key, body, viaProxy = proxyOn(), chain = chain)
        } catch (e: Exception) {
            // Proxy had a transient problem (VM down, timeout, 5xx) but the user also set their
            // own key → try Google directly once so the coach still answers. A 4xx (auth/bad
            // request) is not transient, so we don't retry those.
            val transient = !(e.message ?: "").contains("HTTP 4")
            if (proxyOn() && transient && apiKey(ctx).isNotBlank()) {
                attemptWithModelFallback(ctx, apiKey(ctx), body, viaProxy = false, chain = chain)
            } else throw e
        }
    }

    /**
     * Walks [chain] in preference order and returns the first model's answer, over either the proxy
     * or a direct Google call.
     *
     * The winner is remembered per chain for [MODEL_REPROBE_MS] and tried first next time, so the
     * cost of listing an id that this key doesn't serve is one 404 a week — not one per request.
     * Only a *model-unavailable* error walks the chain: anything else (auth, quota, a bad request,
     * the proxy being down) is the caller's problem and is rethrown immediately, because retrying
     * it on four more models would just multiply the same failure.
     */
    private fun attemptWithModelFallback(
        ctx: Context, key: String, body: JSONObject, viaProxy: Boolean, chain: ModelChain,
    ): String {
        val prefs = p(ctx)
        val okKey = "model_ok_${chain.name}"
        val okAtKey = "model_ok_at_${chain.name}"
        val remembered = prefs.getString(okKey, null)
            ?.takeIf { System.currentTimeMillis() - prefs.getLong(okAtKey, 0) < MODEL_REPROBE_MS }
            ?.takeIf { it in chain.models } // a chain edit retires the old winner
        val order =
            if (remembered == null) chain.models
            else listOf(remembered) + chain.models.filter { it != remembered }

        var last: Exception? = null
        for (model in order) {
            try {
                val out = postWithThinking(key, body, model, viaProxy, chain.thinking)
                if (model != remembered) {
                    prefs.edit().putString(okKey, model)
                        .putLong(okAtKey, System.currentTimeMillis()).apply()
                }
                if (BuildConfig.DEBUG) Log.d(TAG, "${chain.name} answered by $model")
                return out
            } catch (e: Exception) {
                last = e
                if (!modelUnavailable(e)) throw e
                if (BuildConfig.DEBUG) Log.d(TAG, "${chain.name}: $model unavailable, trying next")
            }
        }
        throw last ?: java.io.IOException("no models configured for ${chain.name}")
    }

    /** "This key/endpoint doesn't serve that model" — the only error worth trying another one for. */
    private fun modelUnavailable(e: Exception): Boolean = (e.message ?: "").let {
        it.contains("HTTP 404") || it.contains("NOT_FOUND") || it.contains("not found")
    }

    /** Adds the thinking config for [thinking], mapped per model generation: 2.x takes a numeric
     *  `thinkingBudget` (0 = off, -1 = dynamic), 3.x a `thinkingLevel` enum. If the server rejects
     *  the field (400 — an unknown level, or a model that doesn't take one), retries once without
     *  it, which leaves that model's own default thinking in place. */
    private fun postWithThinking(
        key: String, body: JSONObject, model: String, viaProxy: Boolean, thinking: Thinking,
    ): String {
        val tuned = JSONObject(body.toString())
        val gen = tuned.optJSONObject("generationConfig")
            ?: JSONObject().also { tuned.put("generationConfig", it) }
        val legacy = model.startsWith("gemini-2.")
        gen.put(
            "thinkingConfig",
            when {
                legacy && thinking == Thinking.HIGH -> JSONObject().put("thinkingBudget", -1)
                legacy -> JSONObject().put("thinkingBudget", 0)
                thinking == Thinking.HIGH -> JSONObject().put("thinkingLevel", "high")
                else -> JSONObject().put("thinkingLevel", "low")
            },
        )
        // A model thinking hard takes far longer than the old always-low setting did, and a read
        // timeout shorter than the answer would turn every good reply into a failure.
        val readMs = if (thinking == Thinking.HIGH) DEEP_READ_TIMEOUT_MS else READ_TIMEOUT_MS
        return try {
            post(key, tuned.toString(), model, viaProxy, readMs)
        } catch (e: Exception) {
            if ((e.message ?: "").contains("HTTP 400"))
                post(key, body.toString(), model, viaProxy, readMs)
            else throw e
        }
    }

    /** POSTs [body] to one Gemini [model] and returns the text part (throws with the HTTP
     *  code + error body in the message on failure, so callers can route fallbacks). */
    private fun post(
        key: String, body: String, model: String, viaProxy: Boolean, readMs: Int,
    ): String {
        val conn = (URL(endpoint(model, viaProxy)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            // Through the proxy: our shared secret (the VM injects the real Gemini key).
            // Direct: the user's own Gemini key.
            if (viaProxy) setRequestProperty("Authorization", "Bearer ${BuildConfig.COACH_PROXY_SECRET}")
            else setRequestProperty("x-goog-api-key", key)
            connectTimeout = 15_000
            readTimeout = readMs
            doOutput = true
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw java.io.IOException("HTTP $code: ${err.take(300)}")
        }
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
