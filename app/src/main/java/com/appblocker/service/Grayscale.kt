package com.appblocker.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * **Black & white screen** — Android's own "Color correction ▸ Grayscale", switched from Profile.
 *
 * A grey phone is a duller phone: feeds, thumbnails and red badges lose most of their pull. It is
 * the owner's ask, and **deliberately not part of Strict Mode** (his choice): it can be switched on
 * and off at any time, during a session too. It is a comfort setting, not a protection, so nothing
 * here locks, guards or re-applies it.
 *
 * Writing the setting needs `WRITE_SECURE_SETTINGS` — the same computer-granted permission the
 * silent repair uses ([SelfToggle]), so on his phone it is already there. Without it (the Play
 * build, or a reinstall that lost the grant) the switch says how to do it by hand instead.
 *
 * The setting is per Android user, so the main space and Second Space each have their own switch.
 */
object Grayscale {

    private const val ENABLED = "accessibility_display_daltonizer_enabled"
    private const val MODE = "accessibility_display_daltonizer"

    /** `AccessibilityManager.DALTONIZER_SIMULATE_MONOCHROMACY` — hidden, so spelled here. */
    private const val MONOCHROMACY = 0

    private const val PREFS = "grayscale"
    private const val KEY_PREVIOUS_MODE = "previous_mode"

    /** Whether AppBlocker may write the switch on this phone. */
    fun permitted(context: Context): Boolean = runCatching {
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** True when the screen is grey right now, whoever switched it on. */
    fun isOn(context: Context): Boolean = runCatching {
        val cr = context.contentResolver
        Settings.Secure.getInt(cr, ENABLED, 0) == 1 &&
            Settings.Secure.getInt(cr, MODE, -1) == MONOCHROMACY
    }.getOrDefault(false)

    /**
     * Switches grayscale on or off. False when the phone refused (no permission, or the write
     * failed) — the caller then shows the by-hand way.
     *
     * Switching on remembers a colour-correction mode the phone already had (someone who uses it
     * for real colour blindness), and switching off puts that mode back rather than wiping it.
     */
    fun set(context: Context, on: Boolean): Boolean {
        if (!permitted(context)) return false
        return runCatching {
            val cr = context.contentResolver
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (on) {
                val wasEnabled = Settings.Secure.getInt(cr, ENABLED, 0) == 1
                val mode = Settings.Secure.getInt(cr, MODE, -1)
                if (wasEnabled && mode != MONOCHROMACY && mode != -1) {
                    prefs.edit().putInt(KEY_PREVIOUS_MODE, mode).apply()
                } else {
                    prefs.edit().remove(KEY_PREVIOUS_MODE).apply()
                }
                Settings.Secure.putInt(cr, MODE, MONOCHROMACY) &&
                    Settings.Secure.putInt(cr, ENABLED, 1)
            } else {
                val previous = prefs.getInt(KEY_PREVIOUS_MODE, -1)
                prefs.edit().remove(KEY_PREVIOUS_MODE).apply()
                if (previous != -1) {
                    // Hand the phone back the colour correction it had before, still switched on.
                    Settings.Secure.putInt(cr, MODE, previous)
                } else {
                    Settings.Secure.putInt(cr, ENABLED, 0)
                }
            }
        }.getOrDefault(false)
    }
}
