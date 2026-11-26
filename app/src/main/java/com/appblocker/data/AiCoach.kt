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
 *  (greeting, error notes, the urge card) are shown in the UI but never sent to Gemini or
 *  persisted.
 *
 *  [actions] are the one-tap buttons a coach reply carries (invariant 114), [at] is when the
 *  message was made — also what keeps a tapped button ✅ for THAT reply only — and [kind] marks the
 *  special bubbles the screen draws differently ([KIND_URGE], [KIND_ERROR], [KIND_BLOCKED]). */
data class ChatMsg(
    val role: String,
    val text: String,
    val actions: List<CoachActions.Action> = emptyList(),
    val at: Long = 0L,
    val kind: String? = null,
) {
    companion object {
        /** The 🆘 message he sent, and the calm card that answers it at once. */
        const val KIND_URGE = "urge"

        /** A failed reply — the screen offers "Try again" under it. */
        const val KIND_ERROR = "error"

        /** Opened from the block screen's "Talk to coach". */
        const val KIND_BLOCKED = "blocked"
    }
}

/** One chat answer: the coach's reply, up to 3 suggested follow-up messages the user can tap
 *  instead of typing, and up to 3 one-tap action buttons. */
data class CoachReply(
    val reply: String,
    val suggestions: List<String>,
    val actions: List<CoachActions.Action> = emptyList(),
)

/** How the coach should answer this turn. */
enum class ChatMode {
    NORMAL,

    /** The 🆘 "I have an urge" button (invariant 114): immediate, calm, short, no numbers. */
    URGE,
}

/** A reply the provider refused to give (its own safety filter), told apart from a broken one. */
internal class CoachBlockedException(reason: String) : java.io.IOException("BLOCKED: $reason")

/**
 * Why the coach couldn't answer — the *category*, never the error itself.
 *
 * **Why a category and not the message.** Every failure used to end as `null`, which the chat
 * screen rendered as one grey line: "Couldn't reach Gemini — check your connection and try again."
 * No internet, an exhausted quota, a dead VM, a rejected key and a reply the app couldn't parse
 * all looked identical, so the only thing the owner could report was "it's not working" — and on
 * the day this was written that cost a full round of investigation against a server that turned
 * out to be perfectly healthy, to discover he simply hadn't installed the update. A diagnostic
 * that cannot distinguish "your phone is offline" from "Google says we're out of quota" is not a
 * diagnostic.
 *
 * **And why not just show the error text**, which is the obvious next change someone will want to
 * make: Gemini's error bodies quote the request back, and this app's requests carry the owner's
 * usage figures, his goals and the coach conversation. The same rule as [BugReport] applies —
 * a named category may leave this file, a message may not.
 */
enum class CoachError {
    /** No proxy configured and no key entered — the coach can't run at all on this build. */
    NO_KEY,

    /** The phone has no working internet connection. */
    OFFLINE,

    /** We reached the network but not the coach's server (down, refused, timed out). */
    SERVER_DOWN,

    /** Google says the daily free allowance is used up, on every model we know. */
    QUOTA,

    /** The server turned us away — the key or the shared secret is wrong. */
    REJECTED,

    /** A 200 that the app couldn't make sense of. */
    BAD_REPLY,

    /** Google's own filter refused to answer this one (not a fault — a rephrase usually works). */
    BLOCKED,

    /** Anything unrecognised. Present so a new failure mode is visibly *new* rather than silent. */
    UNKNOWN,
}

/** What [AiCoach.chat] gives back: an answer, or a nameable reason there isn't one. */
sealed interface CoachOutcome {
    data class Ok(val reply: CoachReply) : CoachOutcome
    data class Failed(val error: CoachError) : CoachOutcome
}

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

    /** How long a *fallback* winner is trusted before the preferred model is tried again. Six
     *  hours, because the usual reason for falling back is quota and quota resets daily — see
     *  [attemptWithModelFallback]. One 404 every six hours is a cheap price for not spending a
     *  week on the second-best model after one exhausted afternoon. */
    private const val FALLBACK_REPROBE_MS = 6L * 60 * 60 * 1000
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
    // ⚠️ **Per MODEL, not per answer** (26 Nov 2026). Measured that day through his relay:
    // `gemini-3.5-flash` hung until Cloudflare gave up (524), while `gemini-3-flash-preview`
    // answered in 1-8 s. A timeout used to end the whole walk — the next, working model was never
    // tried, and the coach reported "server down" after 90 s. A timeout now walks on, so this is
    // how long ONE model gets: 60 s is still several times a deep answer's real length.
    private const val DEEP_READ_TIMEOUT_MS = 60_000

    /** How long a model that just timed out, overloaded or ran out of quota goes to the back of
     *  the line — so one hanging model costs one wait per few hours, not one per message. */
    private const val BAD_MODEL_COOLDOWN_MS = 6L * 60 * 60 * 1000

    /**
     * The output ceiling for a chat reply. ⚠️ **Thinking tokens count against it** (measured
     * 26 Nov 2026: with a 512 cap, 492 tokens of thinking left 6 for the answer and it stopped
     * mid-word). At 4096 a deep plan or weekly report could be cut off the same way, so this
     * leaves room for a long thought AND a long answer.
     */
    internal const val CHAT_MAX_OUTPUT_TOKENS = 16_384

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
        // SocketTimeoutException extends InterruptedIOException; null => true. A refusal would
        // only be refused again.
        t !is java.io.InterruptedIOException && t !is CoachBlockedException

    /**
     * Sorts a failure into one of the [CoachError] categories.
     *
     * Pure, and that is the point: the call it describes needs a network and a live Gemini key, so
     * it cannot be tested here — this can, and it is the part that decides what the owner is told.
     *
     * Reads the exception's message only to *match* it (the codes this file itself formats into
     * `"HTTP $code: …"`), and never returns any of it. See [CoachError] for why that line matters.
     */
    internal fun classify(t: Throwable?): CoachError {
        val msg = t?.message.orEmpty()
        return when {
            // Types first: they are unambiguous, where a message is whatever the far end wrote.
            t is CoachBlockedException -> CoachError.BLOCKED
            t is java.net.UnknownHostException -> CoachError.OFFLINE
            t is java.net.ConnectException -> CoachError.SERVER_DOWN
            t is java.io.InterruptedIOException -> CoachError.SERVER_DOWN // incl. socket timeouts
            t is org.json.JSONException -> CoachError.BAD_REPLY
            msg.contains("HTTP 429") || msg.contains("RESOURCE_EXHAUSTED") -> CoachError.QUOTA
            msg.contains("HTTP 401") || msg.contains("HTTP 403") -> CoachError.REJECTED
            msg.contains("HTTP 5") -> CoachError.SERVER_DOWN
            else -> CoachError.UNKNOWN
        }
    }

    private const val KEY_LAST_ERROR = "coach_last_error"
    private const val KEY_LAST_ERROR_AT = "coach_last_error_at"

    /** The last failure and when it happened, for the coach's own info dialog and for bug
     *  reports — so "it's not working" arrives with the reason already attached. */
    fun lastError(ctx: Context): Pair<CoachError, Long>? {
        val name = p(ctx).getString(KEY_LAST_ERROR, null) ?: return null
        val at = p(ctx).getLong(KEY_LAST_ERROR_AT, 0L)
        return runCatching { CoachError.valueOf(name) to at }.getOrNull()
    }

    private fun recordError(ctx: Context, e: CoachError) {
        p(ctx).edit()
            .putString(KEY_LAST_ERROR, e.name)
            .putLong(KEY_LAST_ERROR_AT, System.currentTimeMillis())
            .apply()
    }

    private fun clearError(ctx: Context) {
        p(ctx).edit().remove(KEY_LAST_ERROR).remove(KEY_LAST_ERROR_AT).apply()
    }

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
            // ⚠️ Re-verified against the owner's own key through his relay, 2 Sep 2026, because
            // the Jul 2026 note below had gone stale in the way that matters.
            //
            // `gemini-2.5-pro` now answers **404 - "no longer available to new users"**. Not
            // quota: gone, permanently. It sat first in this chain, so every coach request spent
            // a guaranteed round trip failing before falling through, and the owner has been
            // getting flash-quality coaching while the code believed it was temporarily degraded
            // from pro. The AI Coach is the intended paid feature, so "quietly second-best
            // forever" is the worst shape this could have taken.
            //
            // `gemini-3.1-pro-preview` — the id Google's own 404 body names as the replacement —
            // answers **429** on that key: exhausted free-tier quota, which means the model EXISTS
            // and is served. That is exactly the case this chain was built for, and the 429
            // fall-through below is what makes preferring it free: on a day with quota he gets
            // pro, on a day without he gets what he gets today.
            //
            // The other 3.x pro ids (3.1-pro, 3-pro, 3.5-pro) still 404 and stay out.
            //
            // ⚠️ Re-probed 26 Nov 2026 through the relay: 3.1-pro-preview 429 (quota, still
            // served); 3.5-pro and 3.5-pro-preview 404; 3-pro-preview 404 "no longer available";
            // **3.5-flash hung until Cloudflare's 524**; **3-flash-preview 200 in 1-8 s**, with
            // high thinking; 2.5-flash 200. So the working flash goes BEFORE the hanging one —
            // 3.5-flash stays as a later try in case it was only overloaded that day.
            "gemini-3.1-pro-preview",
            "gemini-3-flash-preview",
            "gemini-3.5-flash",
            "gemini-2.5-flash",
        ),
        thinking = Thinking.HIGH,
    )

    /** Bulk/utility work (app categorisation): fast tier, no thinking. The model that answered
     *  in about a second on 26 Nov 2026 first; the one that hung last. */
    internal val UTILITY_CHAIN = ModelChain(
        name = "utility",
        models = listOf("gemini-2.5-flash", "gemini-3-flash-preview", "gemini-3.5-flash"),
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
    /**
     * ⚠️ **Rewritten 26 Nov 2026 — the old list was months out of date.** It still described
     * Pomodoro as a Strict-tab feature and knew nothing of Locks, the counter, the off-switch waits,
     * the family filter or Instagram, so the coach recommended things that had moved and never the
     * ones that matter most to him. Every line here names what the screen actually says. Update it
     * in the same commit as any feature it describes.
     */
    private val FEATURE_CATALOG = """
        AppBlocker's features — recommend ONLY these, BY the names below, with concrete settings:
        - Quick Block (Blocking tab): blocks a chosen list of apps, or in Allowlist mode everything except the chosen apps; blocking an app also blocks its website in browsers. Timer (block for a set time) and Pomodoro (block during work, free during breaks) start from the Quick Block card. A "Shorts" option blocks only YouTube Shorts.
        - Suggestions for you (top of the Blocking tab): one-tap buttons for the most-used apps — lock for 1 hour, lock until 06:00, or a daily limit.
        - Schedules (Blocking tab > New schedule): Time (a daily window on chosen weekdays, e.g. 22:00-07:00), Usage limit (N minutes a day, then blocked), Launch count (N opens a day), Wi-Fi, Location.
        - Locks (Blocking tab > Locks): lock one blocked app or website for 1 to 365 days; it cannot be removed before then, even outside Strict Mode.
        - Blocked words (Blocking tab > Blocked words): block any word or site in browsers, or in every app; a built-in adult word pack is on by default, and switching it off takes a 7-day wait.
        - Strict Mode (Strict tab): an unstoppable session for a chosen time — nothing can be unblocked until it ends. "I relapsed" on that screen adds 24 hours of Strict.
        - Danger zone (automatic): three different adult words within 30 minutes lock every browser for one hour.
        - Instagram is blocked completely and permanently (the app, Threads and the website) — the user's own choice; there is no switch, so never suggest anything about unblocking it.
        - Protection (Profile): "Guard the off-switch" (lowering it takes 48 hours) and "Prevent uninstall" (turning it off takes 14 days); each wait starts only after typing a long, exact paragraph.
        - Family filter (Profile): the phone's Private DNS refuses adult sites in every app, even if AppBlocker stops.
        - Black & white screen (Profile > Appearance): grey screen, less rewarding to scroll.
        - Recovery (Profile): Your counter (days clean, best streak), Journal (private — the coach never sees it), Scenarios (guides for hard moments: relapse, focus, sleep), The Twelve Steps, Dopamine detox guide.
        - Insights tab: screen time charts, the daily mood check-in, goals with streaks, and this AI Coach.
        - Always blocked, no switch: Gemini's temporary chat and instructions page, Google's Discover news feed, Ultra battery saver, and at 5% battery or less (unplugged) every app except calls and messages.
        - Every block screen has "Talk to coach", which opens this chat.
    """.trimIndent()

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Vestigial: the coach is served by the VM proxy now, so no key is entered on-device. Kept
    // only because callGemini's transient-failure fallback still reads it (always blank now).
    fun apiKey(ctx: Context): String = p(ctx).getString("key", "") ?: ""

    // --- Chat history (persisted so the conversation survives restarts) ---

    fun chatHistory(ctx: Context): List<ChatMsg> =
        p(ctx).getString("chat", null)?.let { decodeChat(it) } ?: emptyList()

    fun saveChat(ctx: Context, msgs: List<ChatMsg>) {
        p(ctx).edit().putString("chat", encodeChat(msgs)).apply()
    }

    /**
     * The stored conversation: user and coach turns only, newest [MAX_HISTORY_STORED], each with
     * its buttons ("a"), time ("at") and kind ("k"). Old entries carry only "r"/"t" and still read —
     * with no buttons and time 0.
     */
    internal fun encodeChat(msgs: List<ChatMsg>): String {
        val keep = msgs.filter { it.role == "user" || it.role == "model" }.takeLast(MAX_HISTORY_STORED)
        val arr = JSONArray()
        keep.forEach { m ->
            val o = JSONObject().put("r", m.role).put("t", m.text)
            if (m.actions.isNotEmpty()) o.put("a", CoachActions.toJson(m.actions))
            if (m.at > 0L) o.put("at", m.at)
            m.kind?.let { o.put("k", it) }
            arr.put(o)
        }
        return arr.toString()
    }

    internal fun decodeChat(raw: String): List<ChatMsg>? = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            ChatMsg(
                role = o.getString("r"),
                text = o.getString("t"),
                actions = CoachActions.fromJson(o.optJSONArray("a")),
                at = o.optLong("at", 0L),
                kind = o.optString("k").ifBlank { null },
            )
        }
    }.getOrNull()

    // --- The 🆘 button: when he asked for help with an urge (times only) ---

    private const val KEY_URGES = "urge_log"

    /** Records one press of "I have an urge". Times only — never what he wrote. */
    fun recordUrge(ctx: Context, at: Long = System.currentTimeMillis()) {
        val kept = (urges(ctx) + at).sorted().takeLast(60)
        p(ctx).edit().putString(KEY_URGES, kept.joinToString(",")).apply()
    }

    fun urges(ctx: Context): List<Long> =
        p(ctx).getString(KEY_URGES, null)?.split(',')?.mapNotNull { it.trim().toLongOrNull() }
            ?: emptyList()

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
            // Every use of this in fetchTips is possessive third person ("$name's coach", "what
            // you know about $name"), so an unset name has to become a noun — "the user" — and
            // not the empty string, which turns the prompt into "You are 's personal coach".
            val name = DisplayName.possessiveSubject(SettingsStore.userName(ctx))
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
     * One chat turn: sends the conversation + fresh usage/setup/goal context, and returns either
     * the coach's reply or a **named** reason there isn't one ([CoachError]). If Gemini includes
     * an updated goal list, it is saved here so both chat and daily tips see it.
     *
     * This used to return `CoachReply?`, and the null threw the cause away — see [CoachError] for
     * what that cost. Only the chat path reports its reason: the daily tips fail invisibly by
     * design (they serve the cached list), so there is nothing there for a user to act on.
     */
    suspend fun chat(
        ctx: Context,
        history: List<ChatMsg>,
        userMsg: String,
        mode: ChatMode = ChatMode.NORMAL,
        /** What the block screen had just blocked when he tapped "Talk to coach" — an app's
         *  name or a plain category ("a website"), never a word or an address. */
        blockedFrom: String? = null,
    ): CoachOutcome =
        withContext(Dispatchers.IO) {
            val key = apiKey(ctx)
            if (key.isBlank() && !proxyOn()) {
                return@withContext CoachOutcome.Failed(CoachError.NO_KEY)
            }
            val today = SimpleDateFormat("EEEE, MMM d, yyyy", Locale.US).format(Date())
            val nowTime = SimpleDateFormat("H:mm", Locale.US).format(Date())
            // The name can be unset — the app no longer assumes one (see [DisplayName]). Both the
            // sentences that talk ABOUT the user and the instruction to USE their name have to
            // survive that, so the former take a noun ("the user") and the latter is swapped for
            // an explicit "you don't know it". Interpolating "" here read as "on the phone of ."
            // and asked the model to use the first name of nobody, which invites it to invent one.
            val storedName = SettingsStore.userName(ctx)
            val who = DisplayName.possessiveSubject(storedName)
            val first = DisplayName.first(storedName)
            val nameLine = if (DisplayName.isSet(storedName)) {
                "Use $first's first name naturally, not in every message."
            } else {
                "You have NOT been told the user's name — address them as \"you\", never invent " +
                    "a name, and don't ask for one."
            }
            val system = buildString {
                appendLine("You are the AI Coach inside AppBlocker, a screen-time app on the phone of $who. Today is $today, and the time right now is $nowTime — the day is still in progress.")
                appendLine("Personality: warm, celebratory, direct — a coach who is genuinely proud when $who makes progress. Lead with the most positive TRUE thing in the data (a streak alive, a goal hit, a number down vs last week) before advice or bad news. $nameLine")
                appendLine("How to write: like a person talking, not like a form. Short natural paragraphs. Match the length to what was asked — a sentence or two for a small question, more when $who asks for a plan, a report or an explanation, and don't pad to fill space. Use a list ONLY when you are genuinely enumerating steps or options; never break an ordinary thought into bullets. **Bold** at most one thing that really matters (usually a number) — not by rule, and not in every reply. No headings unless the reply is long enough to genuinely need them. No other markdown (#, backticks, tables). At most 2 fitting emojis, often zero. Ask at most one question per reply. Vary how you open — never start consecutive replies the same way.")
                appendLine("Your job: help the user understand their usage, agree on goals together, and track progress against those goals using the data below. Suggest specific app features with concrete settings when they would help.")
                appendLine("Language: reply in the language the user writes in.")
                // ⚠️ His standing preference (memory: "Support, Not Metrics"): when he is low,
                // numbers read as a judgement. The celebratory, number-first style above is for
                // ordinary days only.
                appendLine("When the user says they relapsed, slipped, feel ashamed, low, lonely or stuck, or are fighting an urge: switch completely — plain warm prose, NO numbers, NO statistics, NO goals talk, NO lists, NO lecture. Acknowledge what they feel, remind them a slip does not erase their progress, and offer ONE small, concrete next step. Never shame, never moralise.")
                appendLine("Safety: never describe sexual content, never name adult websites or explicit search terms, even if asked — talk about the urge, not the content. If the user mentions wanting to hurt themselves or not wanting to live, respond with care, encourage them to reach out now, and give TelefonSeelsorge (free, 24/7, Germany): 0800 111 0 111 or 0800 111 0 222, and 112 for an emergency.")
                appendLine("Never claim you changed a setting yourself. You change nothing directly: when a concrete blocking step would help, attach it as an action button (see \"actions\" below) and tell the user to tap it.")
                appendLine("Today's numbers are PARTIAL — the day is not over. Never declare a daily goal hit today or call today a win; the strongest claim allowed is \"on track\", tied to the clock (like \"only 40m by 14:00\"). Judge today against \"by this same time yesterday\" rather than full-day averages, and treat a phone-free stretch that includes the night as sleep, not willpower. Finished days (yesterday, streaks, weekly trends) are fair game to celebrate.")
                appendLine("When the user wants a goal or plan for the week: propose ONE specific, measurable weekly goal grounded in the data (for example a daily-average target around 10-20% below their current average — realistic, not drastic), then give a concrete plan: which apps to limit with which feature and what setting, and what to check each day. Save the goal via the goals field prefixed 'This week: '. When a weekly goal already exists, report progress against it using the per-day numbers.")
                appendLine()
                val profileText = runCatching { CoachProfile.promptText(ctx) }.getOrDefault("")
                appendLine(
                    if (profileText.isBlank())
                        "What you know about $who personally: nothing yet — you're still getting to know them."
                    else "What you know about $who personally (learned in past chats — use it like a friend would):\n$profileText"
                )
                appendLine("Getting to know the user: you remember facts permanently through the \"profile\" field described below — chat history gets trimmed, the profile does not. When it fits the moment, weave in AT MOST ONE natural get-to-know-you question per reply — never interrogate, and skip it when the user asked a direct question that deserves a direct answer. Worth learning over time: why they want to block apps, which app and time of day tempts them most, what they'd rather do with the time they win back, their work or study rhythm, and what kind of encouragement lands with them. Whenever the user reveals something personal in ANY message, save it via \"profile\" and reference it naturally from then on.")
                appendLine()
                // His pick, 26 Nov 2026: streak and relapse dates — never the journal's text.
                val recovery = runCatching {
                    recoveryPromptText(
                        startedAt = CleanStreak.startedAt(ctx),
                        bestMs = CleanStreak.bestMs(ctx),
                        resets = CleanStreak.resets(ctx),
                        urges = urges(ctx),
                        now = System.currentTimeMillis(),
                    )
                }.getOrDefault("")
                if (recovery.isNotBlank()) {
                    appendLine("Recovery — the user's own clean-streak counter (handle with great care; celebrate a streak, never use a relapse as a weapon; patterns in the days and hours are worth gently pointing out when the user asks how to prevent the next one):")
                    appendLine(recovery)
                    appendLine()
                }
                if (!blockedFrom.isNullOrBlank()) {
                    appendLine("Context: the user opened this chat from the block screen, seconds after AppBlocker blocked $blockedFrom. Being blocked was the app doing its job — treat the moment as a likely urge, not a failure.")
                    appendLine()
                }
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
                    appendLine("You have not given $who any concrete advice yet.")
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
                appendLine("\"actions\" (optional, at most 3) are one-tap buttons shown under your reply; nothing happens until the user taps one, and each can only make blocking STRONGER. Each is an object: {\"type\": \"lock_app\", \"app\": exact app name, \"minutes\": 15-720} locks one app; {\"type\": \"lock_until_morning\", \"app\": exact app name} locks one app until 06:00; {\"type\": \"daily_limit\", \"app\": exact app name, \"minutes\": 5-240} sets a daily time limit; {\"type\": \"strict\", \"minutes\": 15-1440} starts Strict Mode (or lengthens the running session). Offer an action whenever you recommend one of these concrete steps, so the user can act in one tap; offer none for ordinary conversation. Use app names exactly as they appear in the usage data. Never offer an action for Instagram (always blocked already) or for the phone, messages or keyboard.")
                if (mode == ChatMode.URGE) {
                    appendLine()
                    appendLine("URGE MODE — the user just pressed \"I have an urge\" and needs you NOW. Reply in under 120 words, plain warm prose, no numbers, no statistics, no lists, no headings. First: they did the right thing by opening this instead of giving in — say so. Then ONE concrete plan for the next 10 minutes, built from what you know about them (their replacement activities, their rhythm), e.g. stand up, leave the room, cold water, a walk, call someone. Remind them an urge rises, peaks and passes like a wave. Offer one or two actions (usually \"strict\" for 60-120 minutes, or locking the app that tempts them until morning). End with one gentle question about what set it off. No \"goals\", \"profile\" or \"advice\" fields in this reply.")
                }
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
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("responseMimeType", "application/json")
                        // The reply must be ONE valid JSON object, so hitting the output ceiling
                        // does not truncate an answer — it destroys it: the object never closes,
                        // the parse throws, and the whole reply is lost. The requests most likely
                        // to hit a ceiling are exactly the ones worth having (a weekly report, a
                        // plan), so the ceiling is set high enough that a long answer plus its
                        // goals, suggestions and profile fields fit inside it. Thinking tokens
                        // count against it too: see CHAT_MAX_OUTPUT_TOKENS.
                        .put("maxOutputTokens", CHAT_MAX_OUTPUT_TOKENS),
                )

            var triesLeft = 2
            var lastFailure: Throwable? = null
            var salvaged: String? = null
            while (triesLeft-- > 0) {
                val attempt = runCatching {
                    val raw = callGemini(ctx, key, body, COACH_CHAIN)
                    // Keep what he can still be shown if the strict parse below fails. A reply cut
                    // off by the output ceiling leaves an object that never closes, and throwing
                    // it away costs him the whole answer over a missing brace.
                    salvaged = salvageReply(raw)
                    val obj = JSONObject(raw)
                    val reply = obj.getString("reply").trim()
                    // An urge reply is a moment, not a planning session: whatever it says about
                    // goals, profile or advice is not applied.
                    if (mode != ChatMode.URGE) {
                        obj.optJSONArray("goals")?.let { g -> applyGoalUpdate(ctx, g) }
                        obj.optJSONObject("profile")?.let { pr ->
                            runCatching { CoachProfile.merge(ctx, pr) }
                        }
                        obj.optString("advice").takeIf { it.isNotBlank() }?.let { a ->
                            runCatching { recordAdvice(ctx, a) }
                        }
                    }
                    val suggestions = obj.optJSONArray("suggestions")?.let { s ->
                        (0 until s.length()).map { s.getString(it).trim() }
                            .filter { it.isNotBlank() }.take(3)
                    } ?: emptyList()
                    val actions = runCatching { parseActions(ctx, obj.optJSONArray("actions")) }
                        .getOrDefault(emptyList())
                    if (reply.isBlank()) null else CoachReply(reply, suggestions, actions)
                }
                attempt.getOrNull()?.let {
                    clearError(ctx)
                    return@withContext CoachOutcome.Ok(it)
                }
                lastFailure = attempt.exceptionOrNull()
                if (!retryWorthwhile(lastFailure)) break
            }
            // Both tries failed to parse, but the model did answer and the answer is readable:
            // show it rather than "the coach replied in a form the app couldn't read". Only the
            // words are salvaged — goals, profile updates and advice all need a well-formed
            // object, and half-applying a goal update from a truncated one is exactly the kind of
            // silent wrong state this app cannot afford.
            salvaged?.takeIf { it.isNotBlank() }?.let {
                clearError(ctx)
                return@withContext CoachOutcome.Ok(CoachReply(it, emptyList()))
            }
            // A success with nothing usable in it (a blank reply, an unparsable object) leaves
            // lastFailure null — that is BAD_REPLY, not UNKNOWN: the call worked and the content
            // was the problem.
            val error =
                if (lastFailure == null) CoachError.BAD_REPLY else classify(lastFailure)
            recordError(ctx, error)
            CoachOutcome.Failed(error)
        }

    /**
     * Pulls the `reply` text out of an answer that is *nearly* JSON.
     *
     * The coach is asked for one JSON object, so an answer cut off by the output ceiling — which
     * happens on exactly the requests worth having, a weekly report or a plan — leaves an object
     * that never closes. `JSONObject` throws, and the whole answer is lost over a missing brace.
     *
     * Deliberately dumb: find `"reply"`, take the string that follows, unescape it. It does not
     * try to repair the object, because anything cleverer would eventually "repair" a goals array
     * into something the owner never agreed to. Words only; see the caller.
     */
    internal fun salvageReply(raw: String): String? {
        val key = raw.indexOf("\"reply\"")
        if (key < 0) return null
        val open = raw.indexOf('"', raw.indexOf(':', key) + 1)
        if (open < 0) return null
        val sb = StringBuilder()
        var i = open + 1
        while (i < raw.length) {
            when (val c = raw[i]) {
                '\\' -> {
                    if (i + 1 >= raw.length) break
                    when (val esc = raw[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> Unit
                        'u' -> {
                            val hex = raw.substring(i + 2, minOf(i + 6, raw.length))
                            hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                            i += 4
                        }
                        else -> sb.append(esc) // \" and \\ land here, as themselves
                    }
                    i += 2
                }
                // The closing quote of the reply — anything after it is structure we don't need.
                '"' -> return sb.toString().trim().ifBlank { null }
                else -> { sb.append(c); i++ }
            }
        }
        // Ran off the end: the string itself was truncated mid-sentence. Still his answer.
        return sb.toString().trim().ifBlank { null }
    }

    /** Turns the coach's goal objects into real [Goals], keeping the id (and so the whole
     *  hit history) of any goal that survives the update unchanged. */
    private suspend fun applyGoalUpdate(ctx: Context, arr: JSONArray) {
        InstalledAppsRepository.ensureLoaded(ctx)
        val apps = InstalledAppsRepository.apps.value.map { CoachActions.App(it.packageName, it.label) }
        goalUpdate(arr, apps, Goals.all(ctx))?.let { Goals.replaceAll(ctx, it) }
    }

    /**
     * The goal list after the coach's update, or **null to keep the current list untouched**.
     *
     * ⚠️ Null whenever ANY entry cannot be understood (26 Nov 2026 review). The update REPLACES the
     * whole list, and an entry used to be dropped silently when, say, its app name did not match an
     * installed app — so a reply meant to keep three goals and add a fourth could delete one of the
     * kept ones, streak and all. Half-understanding an update is worse than ignoring it.
     */
    internal fun goalUpdate(arr: JSONArray, apps: List<CoachActions.App>, existing: List<Goal>): List<Goal>? {
        val parsed = ArrayList<Goal>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: return null
            val target = o.optInt("minutes", 0)
            if (target <= 0) return null
            parsed += when (o.optString("kind")) {
                "screen_time" -> Goal(Goals.newId(), GoalKind.SCREEN_TIME, target)
                "unlocks" -> Goal(Goals.newId(), GoalKind.UNLOCKS, target)
                "app" -> {
                    val match = CoachActions.resolve(o.optString("app"), apps) ?: return null
                    Goal(Goals.newId(), GoalKind.APP_LIMIT, target, match.pkg, match.label)
                }
                else -> return null
            }
        }
        return parsed.map { new ->
            existing.firstOrNull {
                it.kind == new.kind && it.target == new.target && it.pkg == new.pkg
            } ?: new
        }
    }

    /** The coach's action buttons, resolved against the apps on this phone (see [CoachActions]). */
    private suspend fun parseActions(ctx: Context, arr: JSONArray?): List<CoachActions.Action> {
        if (arr == null || arr.length() == 0) return emptyList()
        InstalledAppsRepository.ensureLoaded(ctx)
        val apps = InstalledAppsRepository.apps.value.map { CoachActions.App(it.packageName, it.label) }
        val never = neverActOn(ctx)
        return CoachActions.parse(arr, apps) { pkg ->
            pkg !in never && !com.appblocker.service.InstagramBan.isBannedApp(pkg)
        }
    }

    /** The phone, messages, keyboard, home screen and AppBlocker itself: no button may lock them —
     *  the same set the low-battery lock always keeps open. */
    private fun neverActOn(ctx: Context): Set<String> = runCatching {
        val home = ctx.packageManager.queryIntentActivities(
            android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        ).map { it.activityInfo.packageName }
        com.appblocker.service.findEssentialPackages(ctx) + home + listOfNotNull(
            com.appblocker.service.currentImePackage(ctx),
            com.appblocker.service.LowBatteryLock.smsPackage(ctx),
            ctx.packageName,
        )
    }.getOrDefault(setOf(ctx.packageName))

    /**
     * The recovery lines for the prompt — his pick on 26 Nov 2026: **the streak and the dates,
     * never the journal's text**. Pure, so the test can pin exactly what leaves the phone.
     *
     * [resets] are relapse times (newest first, as [CleanStreak.resets] gives them); [urges] are
     * presses of the 🆘 button. Weekday and hour are given because that is where the patterns are.
     */
    internal fun recoveryPromptText(
        startedAt: Long,
        bestMs: Long,
        resets: List<Long>,
        urges: List<Long>,
        now: Long,
    ): String = buildString {
        val day = 24 * 60 * 60_000L
        val fmt = SimpleDateFormat("EEE d MMM, HH:mm", Locale.US)
        if (startedAt > 0L && startedAt <= now) {
            val days = (now - startedAt) / day
            appendLine("- Clean for $days day${if (days == 1L) "" else "s"} (since ${fmt.format(Date(startedAt))}).")
        }
        if (bestMs > 0L) {
            val best = bestMs / day
            appendLine("- Longest streak so far: $best day${if (best == 1L) "" else "s"}.")
        }
        if (resets.isNotEmpty()) {
            appendLine("- Relapses recorded (newest first): " + resets.take(10).joinToString("; ") { fmt.format(Date(it)) } + ".")
        }
        val recentUrges = urges.filter { now - it in 0..7 * day }
        if (recentUrges.isNotEmpty()) {
            appendLine("- Pressed \"I have an urge\" ${recentUrges.size} time${if (recentUrges.size == 1) "" else "s"} in the last 7 days: " +
                recentUrges.takeLast(5).joinToString("; ") { fmt.format(Date(it)) } + ".")
        }
    }.trimEnd()

    /** Compact plain-text usage summary — aggregate numbers and app names only, nothing
     *  sensitive. Shared by daily tips and chat; everything reads from day-cached sources. */
    suspend fun usageSummary(ctx: Context): String {
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
            appendLine("Screen time so far today: ${fmt(UsageTracker.screenMinutesToday(ctx))}")
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
            val top = UsageTracker.topAppsToday(ctx, 3)
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
                val cats = UsageTracker.categoryMinutesToday(ctx)
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
                        "gemini-temp" -> "Gemini temporary chat"
                        "discover" -> "Google news feed"
                        "ultra-saver" -> "Ultra battery saver"
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
            if (SettingsStore.quickBlockAllowlist(ctx)) {
                appendLine("Quick Block mode: Allowlist (everything except the allowed apps is blocked while it runs)")
            }
            // The rest of what protects him today (26 Nov 2026): without these lines the coach
            // could only ever suggest things he already had, or miss that a session is running.
            runCatching {
                val strictLeft = StrictSessions.remainingMs(ctx)
                appendLine(
                    if (strictLeft > 0L) "Strict Mode: running, ${fmt((strictLeft / 60_000L).toInt())} left (nothing can be unblocked until it ends)"
                    else "Strict Mode: not running"
                )
            }
            runCatching {
                val lockedApps = ItemLocks.remaining(ctx, ItemLocks.Kind.APP)
                val labels = InstalledAppsRepository.apps.value.associate { it.packageName to it.label }
                if (lockedApps.isNotEmpty()) appendLine("Locked apps (cannot be unblocked until the lock ends): " +
                    lockedApps.entries.joinToString { (pkg, ms) -> "${labels[pkg] ?: pkg} ${fmt((ms / 60_000L).toInt())} left" })
                // Locked WORDS are counted, never listed: a blocked word is exactly what must not
                // leave the phone.
                val lockedWords = ItemLocks.remaining(ctx, ItemLocks.Kind.WORD).size
                if (lockedWords > 0) appendLine("Locked blocked words/sites: $lockedWords")
            }
            appendLine("Instagram: blocked completely and permanently (his choice, no switch)")
            runCatching {
                appendLine("Guard the off-switch: ${onOff(SettingsStore.guardOffSwitch(ctx))}; " +
                    "Prevent uninstall: ${onOff(com.appblocker.service.KeepAliveSwitches.admin(ctx) == com.appblocker.service.KeepAliveSwitches.State.ON)}")
            }
            runCatching {
                val dz = SettingsStore.dangerZone(ctx)?.remaining(DeviceBoot.count(ctx)) ?: 0L
                if (dz > 0L) appendLine("Danger zone active now: every browser locked for ${fmt(((dz + 59_999L) / 60_000L).toInt())} more")
            }
            runCatching {
                appendLine("Family filter (Private DNS): ${if (NetworkFilter.protecting(NetworkFilter.read(ctx).state)) "on" else "off"}")
            }
            runCatching {
                if (com.appblocker.service.Grayscale.isOn(ctx)) appendLine("Black & white screen: on")
            }
            if (SettingsStore.blockYoutubeShorts(ctx)) appendLine("YouTube Shorts blocking: on")
            appendLine("Gemini temporary chat: always blocked")
            appendLine("Gemini instructions page: always blocked")
            appendLine("Google news feed (Discover): always blocked")
            appendLine("Ultra battery saver: always blocked (mode list closed, control panel editing locked)")
            appendLine("Low battery (5% or less, not charging): every app closed except calls and messages")
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
     * Only a *this model might not be the problem* error walks the chain — see [tryNextModel].
     * Auth, a malformed body and the proxy being down are rethrown at once, because they would
     * fail identically on every model. Quota is NOT in that group: it is metered per model, which
     * is exactly why the walk helps.
     */
    private fun attemptWithModelFallback(
        ctx: Context, key: String, body: JSONObject, viaProxy: Boolean, chain: ModelChain,
    ): String {
        val prefs = p(ctx)
        val okKey = "model_ok_${chain.name}"
        val okAtKey = "model_ok_at_${chain.name}"
        val now = System.currentTimeMillis()
        val cooling = chain.models.filter {
            val at = prefs.getLong("model_bad_at_$it", 0L)
            at > 0L && now - at in 0 until BAD_MODEL_COOLDOWN_MS
        }.toSet()
        val remembered = prefs.getString(okKey, null)
            ?.takeIf { it in chain.models } // a chain edit retires the old winner
            ?.takeIf {
                val age = System.currentTimeMillis() - prefs.getLong(okAtKey, 0)
                // A fallback is remembered for hours; the first choice for a week.
                //
                // The chain is a PREFERENCE order — the model at the top is the one whose answers
                // are worth having. Quota is what usually knocks it out, and quota resets daily,
                // so remembering "flash answered" for a full week means one exhausted afternoon
                // costs seven days of second-best coaching, silently. Nobody would notice: the
                // coach still replies, just less well.
                //
                // The long memory exists for a different case — an id this key does not serve at
                // all, which costs a 404 every time it is tried. That only applies to the model
                // we actually settled on being the best available, so it keeps the long window.
                age < if (it == chain.models.first()) MODEL_REPROBE_MS else FALLBACK_REPROBE_MS
            }
        val order = modelOrder(chain.models, remembered, rememberedFresh = remembered != null, cooling)

        var last: Exception? = null
        for (model in order) {
            try {
                val out = postWithThinking(key, body, model, viaProxy, chain.thinking)
                val edit = prefs.edit().remove("model_bad_at_$model")
                if (model != remembered) {
                    edit.putString(okKey, model).putLong(okAtKey, System.currentTimeMillis())
                }
                edit.apply()
                if (BuildConfig.DEBUG) Log.d(TAG, "${chain.name} answered by $model")
                return out
            } catch (e: Exception) {
                last = e
                if (!tryNextModel(e)) throw e
                // Rest it for a few hours, so a hanging or exhausted model costs one wait — not one
                // per message. A remembered winner that just failed loses its place too.
                val edit = prefs.edit().putLong("model_bad_at_$model", System.currentTimeMillis())
                if (model == remembered) edit.remove(okKey)
                edit.apply()
                if (BuildConfig.DEBUG) Log.d(TAG, "${chain.name}: $model unavailable, trying next")
            }
        }
        throw last ?: java.io.IOException("no models configured for ${chain.name}")
    }

    /**
     * "Another model in the chain might succeed where this one didn't" — the only errors worth
     * walking for.
     *
     * **404** is the original case: this key doesn't serve that id.
     *
     * **429 and 503 are here because Gemini's quota and capacity are PER MODEL.** That is what
     * makes them different from auth or a malformed body, which would fail identically on every
     * model and are still rethrown at once. Leaving 429 out killed the coach outright: the owner's
     * `gemini-2.5-pro` quota was exhausted while `gemini-3.5-flash` and `gemini-2.5-flash` — the
     * next two entries — answered normally. The walk died one line above two working models, and
     * the coach reported itself unavailable.
     *
     * If every model really is exhausted the loop still ends by rethrowing the last exception, so
     * a global quota problem surfaces instead of being swallowed.
     */
    internal fun tryNextModel(e: Exception): Boolean {
        // ⚠️ 26 Nov 2026: a model that HANGS is a per-model problem too. `gemini-3.5-flash` sat
        // silent until Cloudflare answered 524 for it, while the next model in the chain answered
        // in a second — and a timeout used to end the walk, so the coach failed after 90 s with a
        // working model one line below. Timeouts and every 5xx (500/502/503/504 and Cloudflare's
        // 52x) now walk on. Auth (401/403) and a malformed body (400) still do not: they would
        // fail identically on every model.
        if (e is java.io.InterruptedIOException) return true
        val it = e.message ?: ""
        return it.contains("HTTP 404") || it.contains("NOT_FOUND") || it.contains("not found") ||
            it.contains("HTTP 429") || it.contains("RESOURCE_EXHAUSTED") ||
            Regex("HTTP 5\\d\\d").containsMatchIn(it) ||
            it.contains("UNAVAILABLE") || it.contains("DEADLINE_EXCEEDED")
    }

    /**
     * The order to try [models] in: the remembered winner first when [rememberedFresh], then the
     * chain — and any model that misbehaved recently ([cooling]: timed out, overloaded, out of
     * quota or missing within [BAD_MODEL_COOLDOWN_MS]) moved to the back, still tried if all else
     * fails. Pure, for the test.
     */
    internal fun modelOrder(
        models: List<String>,
        remembered: String?,
        rememberedFresh: Boolean,
        cooling: Set<String>,
    ): List<String> {
        val base = if (remembered != null && rememberedFresh && remembered in models) {
            listOf(remembered) + models.filter { it != remembered }
        } else {
            models
        }
        val (ready, resting) = base.partition { it !in cooling }
        return ready + resting
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
        return extractText(response)
    }

    /** Finish reasons that mean "the provider's filter refused", not "the reply broke". */
    private val REFUSED = setOf(
        "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION", "IMAGE_SAFETY",
    )

    /**
     * The reply's text out of a generateContent response. Pure, for the test.
     *
     * Joins **every** text part that is not a thought — it used to take `parts[0]` only, which a
     * thinking model is free to split an answer across. A refusal (the prompt blocked, or a
     * candidate stopped for safety) throws [CoachBlockedException], so the chat can say "it
     * wouldn't answer that one" instead of "the app couldn't read it"; an empty answer for any
     * other reason throws a JSONException, which is BAD_REPLY.
     */
    internal fun extractText(response: String): String {
        val root = JSONObject(response)
        root.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotBlank() }
            ?.let { throw CoachBlockedException(it) }
        val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw org.json.JSONException("no candidates")
        val finish = candidate.optString("finishReason")
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
        val text = buildString {
            for (i in 0 until (parts?.length() ?: 0)) {
                val part = parts?.optJSONObject(i) ?: continue
                if (part.optBoolean("thought", false)) continue
                append(part.optString("text"))
            }
        }
        if (text.isBlank()) {
            if (finish in REFUSED) throw CoachBlockedException(finish)
            throw org.json.JSONException("empty reply ($finish)")
        }
        return text
    }

    private fun onOff(b: Boolean) = if (b) "on" else "off"
    private fun avg(v: List<Int>) = if (v.isEmpty()) 0 else v.average().roundToInt()
    private fun hm(m: Int) = "%d:%02d".format(m / 60, m % 60)
    private fun fmt(minutes: Int): String =
        if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}
