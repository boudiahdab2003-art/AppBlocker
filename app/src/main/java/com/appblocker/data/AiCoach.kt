package com.appblocker.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The AI Coach: asks Gemini for 2–3 short, personalized screen-time tips based on the day's
 * aggregate stats. The user's API key lives ONLY in this device's SharedPreferences (pasted
 * once in the app) — it is never baked into the APK or the public repo. Tips are cached per
 * day, so normal use costs one free-tier request a day.
 */
object AiCoach {
    private const val PREFS = "ai_coach"
    private const val MODEL = "gemini-2.5-flash"
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun apiKey(ctx: Context): String = p(ctx).getString("key", "") ?: ""

    fun setApiKey(ctx: Context, key: String) {
        // A new key should trigger a fresh fetch rather than serving another key's cache.
        p(ctx).edit().putString("key", key.trim()).remove("tips").apply()
    }

    /**
     * Today's tips: cached if already fetched today (unless [force]), else one Gemini call.
     * On failure returns the last cached tips from any day, or null (caller hides/downgrades).
     */
    suspend fun dailyTips(ctx: Context, summary: String, force: Boolean = false): List<String>? =
        withContext(Dispatchers.IO) {
            val prefs = p(ctx)
            val today = todayStamp()
            val cached = prefs.getString("tips", null)?.let { raw ->
                val sep = raw.indexOf('|')
                if (sep > 0) (raw.substring(0, sep).toIntOrNull() ?: -1) to raw.substring(sep + 1)
                else null
            }
            if (!force && cached?.first == today) return@withContext parseTips(cached.second)

            val key = apiKey(ctx)
            if (key.isBlank()) return@withContext null
            // One retry to ride out transient blips, same as the updater.
            repeat(2) {
                runCatching { fetchTips(key, summary) }.getOrNull()?.let { tips ->
                    prefs.edit().putString("tips", "$today|${JSONArray(tips)}").apply()
                    return@withContext tips
                }
            }
            cached?.let { parseTips(it.second) } // stale tips beat no tips
        }

    private fun parseTips(jsonArray: String): List<String>? = runCatching {
        val arr = JSONArray(jsonArray)
        (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun fetchTips(key: String, summary: String): List<String>? {
        val prompt =
            "You are a friendly screen-time coach inside an app-blocker app. Based on this " +
                "user's usage data, reply with ONLY a JSON array of 2 or 3 short tips " +
                "(strings, max 120 characters each). Each tip must be specific to the data, " +
                "actionable, encouraging, plain language, no emojis, no markdown.\n\n" +
                "Data:\n$summary"
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
            .toString()

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
        val text = JSONObject(response)
            .getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
            .getString("text")
        return parseTips(text)
    }
}
