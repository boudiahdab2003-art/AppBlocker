package com.appblocker.data

import android.content.Context

/** Small on/off settings (shares the prefs file with PinStore). */
object SettingsStore {
    private const val PREFS = "appblocker_prefs"
    private const val KEY_BLOCK_ADULT = "block_adult"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun blockAdult(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BLOCK_ADULT, true)

    fun setBlockAdult(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_BLOCK_ADULT, value).apply()
}
