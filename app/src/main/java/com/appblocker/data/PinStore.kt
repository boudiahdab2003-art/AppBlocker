package com.appblocker.data

import android.content.Context
import java.security.MessageDigest

/**
 * Stores a SHA-256 hash of the user's PIN (never the PIN itself). Used to gate
 * access to the app's settings so blocks can't be removed on a whim.
 */
object PinStore {
    private const val PREFS = "appblocker_prefs"
    private const val KEY = "pin_hash"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isSet(context: Context): Boolean = prefs(context).contains(KEY)

    fun set(context: Context, pin: String) =
        prefs(context).edit().putString(KEY, hash(pin)).apply()

    fun check(context: Context, pin: String): Boolean =
        prefs(context).getString(KEY, null) == hash(pin)

    fun clear(context: Context) = prefs(context).edit().remove(KEY).apply()

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
