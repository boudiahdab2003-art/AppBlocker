package com.appblocker.data

import android.content.Context

/** Small on/off settings (shares the prefs file with PinStore). */
object SettingsStore {
    private const val PREFS = "appblocker_prefs"
    private const val KEY_BLOCK_ADULT = "block_adult"
    private const val KEY_ADD_NEW_APPS = "add_new_apps"
    private const val KEY_BLOCK_PURCHASES = "block_purchases"
    private const val KEY_BLOCK_UNSUPPORTED = "block_unsupported_browsers"
    private const val KEY_BLOCK_YT_SHORTS = "block_youtube_shorts"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun blockAdult(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BLOCK_ADULT, true)

    fun setBlockAdult(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_BLOCK_ADULT, value).apply()

    fun addNewApps(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ADD_NEW_APPS, false)

    fun setAddNewApps(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_ADD_NEW_APPS, value).apply()

    fun blockPurchases(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BLOCK_PURCHASES, false)

    fun setBlockPurchases(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_BLOCK_PURCHASES, value).apply()

    fun blockUnsupportedBrowsers(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BLOCK_UNSUPPORTED, false)

    fun setBlockUnsupportedBrowsers(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_BLOCK_UNSUPPORTED, value).apply()

    /** Block only the YouTube Shorts feed/player (the rest of YouTube still works). */
    fun blockYoutubeShorts(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BLOCK_YT_SHORTS, false)

    fun setBlockYoutubeShorts(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_BLOCK_YT_SHORTS, value).apply()

    /** The owner's display name shown on the Profile page. */
    fun userName(context: Context): String =
        prefs(context).getString("user_name", "Abdallah Ahdab") ?: "Abdallah Ahdab"

    fun setUserName(context: Context, value: String) =
        prefs(context).edit().putString("user_name", value.trim()).apply()

    const val KEY_THEME_MODE = "theme_mode"

    /** App theme: "system" (follow the phone), "light", or "dark". Default follows the phone. */
    fun themeMode(context: Context): String =
        prefs(context).getString(KEY_THEME_MODE, "system") ?: "system"

    fun setThemeMode(context: Context, value: String) =
        prefs(context).edit().putString(KEY_THEME_MODE, value).apply()

    const val KEY_UPDATE_PAUSED = "update_paused"

    /** True after an app update, until the user reactivates blocking (Blocking-tab banner). */
    fun updatePaused(context: Context): Boolean =
        prefs(context).getBoolean(KEY_UPDATE_PAUSED, false)

    fun setUpdatePaused(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_UPDATE_PAUSED, value).apply()

    /** The versionCode last seen running — a change means the app was just updated. */
    fun lastSeenVersionCode(context: Context): Long =
        prefs(context).getLong("last_seen_version", -1L)

    fun setLastSeenVersionCode(context: Context, value: Long) =
        prefs(context).edit().putLong("last_seen_version", value).apply()

    /** True while an update-triggered Strict-session clear hasn't landed in the DB yet —
     *  the durable intent that lets UpdatePause retry a clear whose process was killed. */
    fun strictClearPending(context: Context): Boolean =
        prefs(context).getBoolean("strict_clear_pending", false)

    fun setStrictClearPending(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("strict_clear_pending", value).apply()

    /** Which launcher icon is active (Profile ▸ App icon). Ids defined in [AppIcons]. */
    fun appIcon(context: Context): String =
        prefs(context).getString("app_icon", "halo") ?: "halo"

    fun setAppIcon(context: Context, value: String) =
        prefs(context).edit().putString("app_icon", value).apply()

    /** Whether the first-run setup screen has been shown once. */
    fun setupSeen(context: Context): Boolean =
        prefs(context).getBoolean("setup_seen", false)

    fun setSetupSeen(context: Context) =
        prefs(context).edit().putBoolean("setup_seen", true).apply()

    /** Quick Block paused = its apps aren't enforced (selection kept). Schedules unaffected. */
    fun quickBlockPaused(context: Context): Boolean =
        prefs(context).getBoolean("quick_block_paused", false)

    fun setQuickBlockPaused(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("quick_block_paused", value).apply()

    const val KEY_ADULT_WORDS_PACK = "adult_words_pack"

    /** The built-in pack of pornographic words (English + Arabic), blocked like the user's own
     *  words. On by default; turning it off is Strict-locked like other protective toggles. */
    fun adultWordsPack(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ADULT_WORDS_PACK, true)

    fun setAdultWordsPack(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_ADULT_WORDS_PACK, value).apply()

    const val KEY_KEYWORDS_EVERYWHERE = "keywords_everywhere"

    /** Match blocked words in every app (default), not just browsers. When off, only browsers
     *  are scanned. (Replaced the old per-app opt-in list "keyword_scan_apps".) */
    fun keywordsEverywhere(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEYWORDS_EVERYWHERE, true)

    fun setKeywordsEverywhere(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_KEYWORDS_EVERYWHERE, value).apply()

    /** Whether the one-time purge of template-injected app-name words has run (see
     *  BlockerAccessibilityService.purgeTemplateWordsOnce). */
    fun templateWordsPurged(context: Context): Boolean =
        prefs(context).getBoolean("template_words_purged", false)

    fun setTemplateWordsPurged(context: Context) =
        prefs(context).edit().putBoolean("template_words_purged", true).apply()

    /** Epoch millis when the owner passed the adult-pack turn-off gate (0 = no pending
     *  request). The pack keeps filtering through the cooling-off; see KeywordsScreen. */
    fun adultPackOffRequestedAt(context: Context): Long =
        prefs(context).getLong("adult_pack_off_requested_at", 0L)

    fun setAdultPackOffRequestedAt(context: Context, value: Long) =
        prefs(context).edit().putLong("adult_pack_off_requested_at", value).apply()

    private const val KEY_KEYWORD_LOCKOUTS = "keyword_lockouts"

    /** Apps under a keyword lockout (a blocked word was caught in them), as package →
     *  expiry epoch millis. Persisted so a service or phone restart doesn't lift the lock. */
    fun keywordLockouts(context: Context): Map<String, Long> =
        prefs(context).getStringSet(KEY_KEYWORD_LOCKOUTS, emptySet()).orEmpty()
            .mapNotNull { entry ->
                val split = entry.lastIndexOf('|')
                if (split <= 0) return@mapNotNull null
                val until = entry.substring(split + 1).toLongOrNull() ?: return@mapNotNull null
                entry.substring(0, split) to until
            }.toMap()

    fun setKeywordLockouts(context: Context, value: Map<String, Long>) =
        prefs(context).edit().putStringSet(
            KEY_KEYWORD_LOCKOUTS,
            value.filterValues { it > System.currentTimeMillis() }
                .map { (pkg, until) -> "$pkg|$until" }.toSet(),
        ).apply()

    private const val KEY_PROTECTION_LAST_NOTIFIED = "protection_last_notified_at"

    /** Epoch millis of the last "protection off" notification, for re-notify throttling. */
    fun protectionLastNotifiedAt(context: Context): Long =
        prefs(context).getLong(KEY_PROTECTION_LAST_NOTIFIED, 0L)

    fun setProtectionLastNotifiedAt(context: Context, value: Long) =
        prefs(context).edit().putLong(KEY_PROTECTION_LAST_NOTIFIED, value).apply()

    /** Reset the moment the service is confirmed back on, so the next disable notifies at once. */
    fun clearProtectionOffSince(context: Context) = setProtectionLastNotifiedAt(context, 0L)
}
