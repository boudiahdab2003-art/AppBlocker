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

    /** True between noticing a version change and deciding whether the pause actually applies (it
     *  doesn't while a Strict session is running) — the durable intent that lets [UpdatePause]
     *  retry a decision whose process was killed before it landed.
     *
     *  Retires the old "strict_clear_pending", which meant the opposite: an update used to END a
     *  running Strict session, and that flag was the intent to do so. Cleared on read so an install
     *  carrying it doesn't hold a stale instruction forever. */
    fun updatePausePending(context: Context): Boolean =
        prefs(context).getBoolean(KEY_UPDATE_PAUSE_PENDING, false)

    fun setUpdatePausePending(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_UPDATE_PAUSE_PENDING, value)
            .remove("strict_clear_pending") // retired; an update no longer ends a Strict session
            .apply()

    private const val KEY_UPDATE_PAUSE_PENDING = "update_pause_pending"

    /** Which launcher icon is active (Profile ▸ App icon). Ids defined in [AppIcons]. */
    fun appIcon(context: Context): String =
        prefs(context).getString("app_icon", "halo") ?: "halo"

    fun setAppIcon(context: Context, value: String) =
        prefs(context).edit().putString("app_icon", value).apply()

    /** Which block-screen look is active (Profile ▸ Block screen). Ids in [BlockThemes]. */
    fun blockTheme(context: Context): String =
        prefs(context).getString("block_theme", "midnight") ?: "midnight"

    fun setBlockTheme(context: Context, value: String) =
        prefs(context).edit().putString("block_theme", value).apply()

    /** Which block-screen layout is active (Profile ▸ Block screen). Ids in [BlockLayouts]. */
    fun blockLayout(context: Context): String =
        prefs(context).getString("block_layout", "editorial") ?: "editorial"

    fun setBlockLayout(context: Context, value: String) =
        prefs(context).edit().putString("block_layout", value).apply()

    /** The block screen's element order/visibility/alignment, encoded by [BlockArrangement].
     *  Null = untouched, so each layout keeps its own arrangement. */
    fun blockArrangement(context: Context): String? =
        prefs(context).getString("block_arrangement", null)

    fun setBlockArrangement(context: Context, value: String?) =
        prefs(context).edit().apply {
            if (value == null) remove("block_arrangement") else putString("block_arrangement", value)
        }.apply()

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

    const val KEY_QUICK_BLOCK_ALLOWLIST = "quick_block_allowlist"

    /** Quick Block mode: false = Blocklist (block the chosen apps), true = Allowlist (allow only
     *  the chosen apps, block everything else). Both selections are kept independently. */
    fun quickBlockAllowlist(context: Context): Boolean =
        prefs(context).getBoolean(KEY_QUICK_BLOCK_ALLOWLIST, false)

    fun setQuickBlockAllowlist(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_QUICK_BLOCK_ALLOWLIST, value).apply()

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

    private const val KEY_ADULT_OFF_REQUEST = "adult_pack_off_request"

    /**
     * The pending "turn the adult pack off" request, or null when there is none.
     *
     * Anchored via [GuardedDeadline] rather than the bare epoch-millis this used to be. The 24-hour
     * cooling-off is the app's most deliberately hardened protection — the changelog promises "the
     * pack keeps protecting you for another 24 hours" — and comparing a stored timestamp against
     * `System.currentTimeMillis()` meant winding the clock forward a day skipped it entirely. The
     * keyword lockout had the identical hole; that is why the anchoring is now one shared type.
     */
    internal fun adultPackOffRequest(context: Context): GuardedDeadline? =
        prefs(context).getString(KEY_ADULT_OFF_REQUEST, null)
            ?.let { GuardedDeadline.decode(it)?.second }

    /** Records a request starting now, lasting [delayMs] before the switch works. */
    internal fun setAdultPackOffRequest(context: Context, delayMs: Long, bootCount: Int) =
        prefs(context).edit().putString(
            KEY_ADULT_OFF_REQUEST,
            GuardedDeadline.starting(delayMs, bootCount).encode(ADULT_OFF_KEY),
        ).apply()

    internal fun clearAdultPackOffRequest(context: Context) =
        prefs(context).edit().remove(KEY_ADULT_OFF_REQUEST)
            .remove("adult_pack_off_requested_at") // retire the old wall-clock-only key
            .apply()

    /** GuardedDeadline.encode needs a key; this record only ever holds one. */
    private const val ADULT_OFF_KEY = "adult"

    private const val KEY_KEYWORD_LOCKOUTS = "keyword_lockouts"

    /** Apps under a keyword lockout (a blocked word was caught in them). Persisted so a service
     *  or phone restart doesn't lift the lock; see [GuardedDeadline] for the anchoring, which is
     *  clock-change-proof rather than a bare wall-clock deadline. */
    internal fun keywordLockouts(context: Context): Map<String, GuardedDeadline> =
        prefs(context).getStringSet(KEY_KEYWORD_LOCKOUTS, emptySet()).orEmpty()
            .mapNotNull { GuardedDeadline.decode(it) }
            .toMap()

    /** [currentBootCount] so expired entries are dropped on the same clock rules that decide
     *  whether a lockout is still running — filtering on the wall clock here would undo the
     *  clock-proofing by pruning a live lockout whose deadline merely looks past. */
    internal fun setKeywordLockouts(
        context: Context,
        value: Map<String, GuardedDeadline>,
        currentBootCount: Int,
    ) = prefs(context).edit().putStringSet(
        KEY_KEYWORD_LOCKOUTS,
        value.filterValues { it.remaining(currentBootCount) > 0L }
            .map { (pkg, lockout) -> lockout.encode(pkg) }.toSet(),
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
