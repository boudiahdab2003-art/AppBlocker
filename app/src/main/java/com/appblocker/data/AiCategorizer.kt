package com.appblocker.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gemini-powered app categorization, riding on the AI Coach's on-device key: the phone's own
 * app list (package + display name only) is sent to Gemini once, which files every app into one
 * of the [AppCategory] buckets. Answers are cached permanently per package, so the whole phone
 * costs ONE request, and only newly installed apps are ever asked about again. No key = silent
 * no-op (the baked map + system category still group things sensibly).
 */
object AiCategorizer {

    private const val PREFS = "ai_categories"
    private const val MAX_BATCH = 400 // safety cap; a phone has ~100-250 launchable apps

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Every AI answer so far: package -> category. */
    fun cached(ctx: Context): Map<String, AppCategory> =
        p(ctx).all.mapNotNull { (pkg, v) ->
            (v as? String)?.let { pkg to AppCategories.parse(it) }
        }.toMap()

    /**
     * Asks Gemini to categorize every app in [apps] that hasn't been answered before.
     * Returns the newly answered map (empty/null when there was nothing to ask, no key,
     * or the call failed — failures cache nothing and are retried on a later launch).
     */
    suspend fun categorizeAll(ctx: Context, apps: List<InstalledApp>): Map<String, AppCategory>? =
        withContext(Dispatchers.IO) {
            val key = AiCoach.apiKey(ctx)
            if (key.isBlank()) return@withContext null
            val prefs = p(ctx)
            val pending = apps.filter { !prefs.contains(it.packageName) }.take(MAX_BATCH)
            if (pending.isEmpty()) return@withContext null

            val catNames = AppCategory.entries.joinToString(", ") { it.name }
            val prompt = buildString {
                appendLine("Categorize these Android apps for a digital-wellbeing blocker.")
                appendLine("Allowed categories (use EXACTLY these names): $catNames")
                appendLine("SOCIAL includes messengers; ENTERTAINMENT is video/streaming/music;")
                appendLine("UTILITIES includes banking/finance/files/weather; PRODUCTIVITY includes browsers/email/office.")
                appendLine("Reply with ONLY a JSON object mapping every package name to its category, e.g.")
                appendLine("""{"com.instagram.android":"SOCIAL"}""")
                appendLine()
                appendLine("Apps:")
                pending.forEach { appendLine("${it.packageName} — ${it.label}") }
            }
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject()
                    .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
                .toString()

            runCatching {
                val answer = JSONObject(AiCoach.post(key, body))
                val editor = prefs.edit()
                val result = mutableMapOf<String, AppCategory>()
                val askedPkgs = pending.mapTo(HashSet()) { it.packageName }
                answer.keys().forEach { pkg ->
                    if (pkg in askedPkgs) {
                        val cat = AppCategories.parse(answer.optString(pkg))
                        editor.putString(pkg, cat.name)
                        result[pkg] = cat
                    }
                }
                editor.apply()
                result.ifEmpty { null }
            }.getOrNull()
        }
}
