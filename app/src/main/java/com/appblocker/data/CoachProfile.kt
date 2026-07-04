package com.appblocker.data

import android.content.Context
import org.json.JSONObject

/**
 * What the coach has learned about the user across conversations — a small flat map of
 * plain-text facts (why they block, what tempts them, what motivates them...). Lives in the
 * same device-only prefs as the rest of the coach's data. Facts are MERGED per key (never
 * wholesale-replaced): the model only sees the last few dozen chat turns, so a reply that
 * mentions nothing personal must not erase older memories.
 */
object CoachProfile {
    private const val PREFS = "ai_coach" // shared with AiCoach
    private const val KEY = "profile"
    private const val MAX_FIELDS = 24
    private const val MAX_VALUE_LEN = 200

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): Map<String, String> =
        p(ctx).getString(KEY, null)?.let { raw ->
            runCatching {
                val obj = JSONObject(raw)
                obj.keys().asSequence().associateWith { obj.getString(it) }
            }.getOrNull()
        } ?: emptyMap()

    /** Per-key upsert from the model's "profile" object: blank value = forget that key. */
    fun merge(ctx: Context, updates: JSONObject) {
        val map = all(ctx).toMutableMap()
        updates.keys().forEach { rawKey ->
            val key = rawKey.trim().lowercase().replace(' ', '_')
            if (key.isEmpty()) return@forEach
            val value = if (updates.isNull(rawKey)) "" else updates.optString(rawKey).trim()
            if (value.isEmpty()) map.remove(key)
            else if (key in map || map.size < MAX_FIELDS) map[key] = value.take(MAX_VALUE_LEN)
        }
        p(ctx).edit().putString(KEY, JSONObject(map as Map<*, *>).toString()).apply()
    }

    fun clear(ctx: Context) = p(ctx).edit().remove(KEY).apply()

    /** "- key: value" lines for the prompts; empty string when nothing is known yet. */
    fun promptText(ctx: Context): String =
        all(ctx).entries.joinToString("\n") { (k, v) -> "- ${k.replace('_', ' ')}: $v" }
}
