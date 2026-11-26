package com.appblocker.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * **One-tap buttons in the AI Coach's replies** (invariant 114). His pick on 26 Nov 2026: "Action
 * buttons" — the coach can attach 🔒 lock / 🌙 lock till morning / ⏱ daily limit / 🛡 Strict
 * buttons to a reply, and nothing happens until he taps one.
 *
 * Every action **only strengthens** blocking and runs through [QuickActions], the same code the
 * smart suggestions use. There is no action that unlocks, pauses, shortens or removes anything —
 * and [parse] cannot produce one, whatever the model writes, because it only knows these four.
 *
 * The model names apps by their label; [parse] resolves each to a real installed app and drops
 * anything it cannot resolve, anything essential, AppBlocker itself and apps that are always
 * blocked anyway. Minutes are clamped to sane ranges, so a confused reply can at worst offer a
 * button that does less than it said — never one that does something else.
 */
object CoachActions {

    enum class Type(val wire: String) {
        LOCK_APP("lock_app"),
        LOCK_TONIGHT("lock_until_morning"),
        DAILY_LIMIT("daily_limit"),
        STRICT("strict");

        companion object {
            fun of(wire: String?): Type? = entries.firstOrNull { it.wire == wire?.trim()?.lowercase() }
        }
    }

    data class Action(
        val type: Type,
        /** The installed app it acts on; null for [Type.STRICT]. */
        val pkg: String? = null,
        val appLabel: String? = null,
        /** Lock length, daily limit or Strict length; 0 for [Type.LOCK_TONIGHT]. */
        val minutes: Int = 0,
    ) {
        /** Stable for the same button, so a tap stays remembered across restarts. */
        val id: String get() = "${type.wire}|${pkg.orEmpty()}|$minutes"
    }

    const val MAX_PER_REPLY = 3
    val LOCK_RANGE = 15..12 * 60
    val LIMIT_RANGE = 5..4 * 60
    val STRICT_RANGE = 15..24 * 60

    /** An installed app the coach may name: its package and label. */
    data class App(val pkg: String, val label: String)

    /**
     * Turns the model's `actions` array into buttons. Pure — [apps] are the launchable apps and
     * [eligible] says whether an app may carry a button at all.
     */
    fun parse(arr: JSONArray?, apps: List<App>, eligible: (String) -> Boolean): List<Action> {
        if (arr == null) return emptyList()
        val out = ArrayList<Action>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val type = Type.of(o.optString("type")) ?: continue
            val minutes = o.optInt("minutes", 0)
            val action = when (type) {
                Type.STRICT -> Action(type, minutes = minutes.coerceIn(STRICT_RANGE))
                else -> {
                    val app = resolve(o.optString("app"), apps) ?: continue
                    if (!eligible(app.pkg)) continue
                    when (type) {
                        Type.LOCK_APP -> Action(type, app.pkg, app.label, minutes.coerceIn(LOCK_RANGE))
                        Type.LOCK_TONIGHT -> Action(type, app.pkg, app.label)
                        Type.DAILY_LIMIT -> Action(type, app.pkg, app.label, minutes.coerceIn(LIMIT_RANGE))
                        Type.STRICT -> continue
                    }
                }
            }
            if (out.none { it.id == action.id }) out += action
            if (out.size == MAX_PER_REPLY) break
        }
        return out
    }

    /** Exact label first, then a label that contains the name — "YouTube" finds "YouTube", and
     *  "Chrome" finds "Google Chrome". Blank never matches. */
    fun resolve(name: String?, apps: List<App>): App? {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return null
        return apps.firstOrNull { it.label.equals(n, ignoreCase = true) }
            ?: apps.firstOrNull { it.label.contains(n, ignoreCase = true) }
    }

    /** "1 h", "90 min", "2 h 30 min" — the button's length, short enough for a chip. */
    fun lengthLabel(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }

    // ---- stored with the message, so the buttons survive a restart -----------------------------

    fun toJson(actions: List<Action>): JSONArray = JSONArray().apply {
        actions.forEach { a ->
            put(
                JSONObject().put("type", a.type.wire).put("pkg", a.pkg ?: "")
                    .put("label", a.appLabel ?: "").put("minutes", a.minutes),
            )
        }
    }

    fun fromJson(arr: JSONArray?): List<Action> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val type = Type.of(o.optString("type")) ?: return@mapNotNull null
            Action(
                type,
                o.optString("pkg").ifBlank { null },
                o.optString("label").ifBlank { null },
                o.optInt("minutes", 0),
            )
        }
    }

    // ---- the tap ---------------------------------------------------------------------------------

    private const val PREFS = "ai_coach"
    private const val KEY_APPLIED = "actions_applied"

    /** The key a tapped button is remembered by: the message it sits under and the action. The
     *  same button offered again in a LATER reply is a new button, so it is not already ✅. */
    fun key(messageAt: Long, a: Action): String = "$messageAt|${a.id}"

    /** Buttons already tapped, by [key] — a tapped button stays ✅. */
    fun applied(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_APPLIED, emptySet())
            .orEmpty()

    /** Applies [a] and remembers it under [key]. Strengthen-only by construction: see [QuickActions]. */
    suspend fun apply(ctx: Context, a: Action, limitScheduleName: String, key: String) {
        when (a.type) {
            Type.LOCK_APP -> a.pkg?.let { QuickActions.lockApp(ctx, it, a.minutes * 60_000L) }
            Type.LOCK_TONIGHT -> a.pkg?.let { QuickActions.lockAppUntilMorning(ctx, it) }
            Type.DAILY_LIMIT -> a.pkg?.let {
                QuickActions.dailyLimit(ctx, it, limitScheduleName, a.minutes)
            }
            Type.STRICT -> QuickActions.strict(ctx, a.minutes)
        }
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Kept short: only recent buttons can still be on screen.
        val kept = (applied(ctx) + key).toList().takeLast(200).toSet()
        prefs.edit().putStringSet(KEY_APPLIED, kept).apply()
    }
}
