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

    const val KEY_KEYWORD_SCAN_APPS = "keyword_scan_apps"

    /** Packages the user opted in to also have blocked words matched inside (beyond browsers).
     *  Empty by default, so existing users' behavior is unchanged. */
    fun keywordScanApps(context: Context): Set<String> =
        // getStringSet returns a shared instance the caller must not mutate — hand back a copy.
        prefs(context).getStringSet(KEY_KEYWORD_SCAN_APPS, emptySet())?.toSet() ?: emptySet()

    fun setKeywordScanApps(context: Context, value: Set<String>) =
        // putStringSet only persists reliably when given a fresh set instance.
        prefs(context).edit().putStringSet(KEY_KEYWORD_SCAN_APPS, HashSet(value)).apply()

    private const val KEY_PROTECTION_LAST_NOTIFIED = "protection_last_notified_at"

    /** Epoch millis of the last "protection off" notification, for re-notify throttling. */
    fun protectionLastNotifiedAt(context: Context): Long =
        prefs(context).getLong(KEY_PROTECTION_LAST_NOTIFIED, 0L)

    fun setProtectionLastNotifiedAt(context: Context, value: Long) =
        prefs(context).edit().putLong(KEY_PROTECTION_LAST_NOTIFIED, value).apply()

    /** Reset the moment the service is confirmed back on, so the next disable notifies at once. */
    fun clearProtectionOffSince(context: Context) = setProtectionLastNotifiedAt(context, 0L)
}
