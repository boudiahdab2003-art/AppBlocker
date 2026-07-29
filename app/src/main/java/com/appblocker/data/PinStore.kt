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

    /**
     * How long the app may sit in the background before the PIN is asked for again.
     *
     * Not zero, and that is the whole design problem. The app sends the user *out* to system
     * screens constantly — the accessibility list, the device-admin prompt, battery settings,
     * the app-info page — and re-locking on every one of those would make setup miserable and
     * teach him to turn the PIN off. Two minutes covers a trip to Settings and back; it does not
     * cover picking the phone up again later, which is the moment the PIN exists for.
     */
    const val RELOCK_AFTER_MS = 2 * 60_000L

    /**
     * Whether returning after [awayMs] in the background should ask for the PIN again.
     *
     * **The PIN used to be asked once and never again.** `LockGate` held "unlocked" for the life
     * of the composition, which survives backgrounding and even process-death restore — so on a
     * phone where AppBlocker sits in recents, the lock opened once and stayed open for days. The
     * setting says "lock your settings so blocks can't be removed on a whim", and the whim arrives
     * hours later, by which time the lock was already open.
     *
     * A negative [awayMs] means the clocks cannot be trusted (a restore across a reboot, where the
     * monotonic clock restarted): re-lock, because asking for a PIN that isn't needed costs
     * seconds, and skipping one that is costs the thing the PIN protects.
     */
    fun shouldRelock(pinSet: Boolean, awayMs: Long): Boolean =
        pinSet && awayMs !in 0..RELOCK_AFTER_MS

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
