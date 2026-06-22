package com.appblocker.data

import android.content.Context

/** Small on/off settings (shares the prefs file with PinStore). */
object SettingsStore {
    private const val PREFS = "appblocker_prefs"
    private const val KEY_BLOCK_ADULT = "block_adult"
    private const val KEY_ADD_NEW_APPS = "add_new_apps"
    private const val KEY_BLOCK_PURCHASES = "block_purchases"
    private const val KEY_BLOCK_UNSUPPORTED = "block_unsupported_browsers"

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
}
