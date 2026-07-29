package com.appblocker.data

import android.content.Context

/**
 * Whether the blocking watcher is actually alive, and what has gone wrong lately.
 *
 * The app used to only know whether the accessibility service was *switched on* in Settings.
 * "Switched on but dead" — the process killed by an aggressive OEM battery manager, which is the
 * normal failure on HyperOS/Xiaomi — looked exactly like healthy. The service now stamps
 * [recordEvent] as it works, so the watchdog can tell the difference.
 *
 * Shares `appblocker_prefs` with [SettingsStore]. Writes are throttled by the caller.
 */
object ServiceHealth {
    private const val PREFS = "appblocker_prefs"
    private const val KEY_LAST_EVENT = "health_last_event_at"
    private const val KEY_LAST_ERROR_AT = "health_last_error_at"
    private const val KEY_LAST_ERROR = "health_last_error"
    private const val KEY_LAST_ERROR_WHERE = "health_last_error_where"
    private const val KEY_ERROR_COUNT = "health_error_count"

    /** Don't touch disk on every accessibility event — one write a minute is plenty. */
    private const val WRITE_THROTTLE_MS = 60_000L

    @Volatile private var lastWrittenAt = 0L

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * "The watcher is alive and receiving events." Called from the service's event path, so it
     * throttles itself: at most one write a minute, and the in-memory guard means the common
     * case is a comparison, not a disk touch.
     */
    fun recordEvent(context: Context, now: Long = System.currentTimeMillis()) {
        if (now - lastWrittenAt < WRITE_THROTTLE_MS) return
        lastWrittenAt = now
        prefs(context).edit().putLong(KEY_LAST_EVENT, now).apply()
    }

    /** When the watcher last showed a sign of life (0 = never since install). */
    fun lastEventAt(context: Context): Long = prefs(context).getLong(KEY_LAST_EVENT, 0L)

    /**
     * An error the service swallowed instead of dying on. Kept (with a count) so a recurring
     * bug is visible rather than invisible — swallowing errors silently would trade a loud
     * failure for a quiet one.
     *
     * "Visible" needs somewhere to actually look, and for a while there was none: nothing read
     * these back, so the store was written and never surfaced — the quiet failure this comment
     * warns about. The Profile screen now shows a row whenever the count is above zero, which is
     * what makes a recurring problem reportable ("it says 12 errors") instead of invisible.
     */
    fun recordError(context: Context, where: String, t: Throwable) {
        val p = prefs(context)
        p.edit()
            .putLong(KEY_LAST_ERROR_AT, System.currentTimeMillis())
            .putString(KEY_LAST_ERROR, "$where: ${t.javaClass.simpleName}: ${t.message}")
            // The tag on its own, so a bug report can say *where* the last error was without the
            // string above it. That one includes `t.message`, which is where a failing value gets
            // quoted back — and in this app that value is a blocked word. The full line is for the
            // Profile screen, on the device; only this half may ever leave it. See BugReport.
            .putString(KEY_LAST_ERROR_WHERE, where)
            .putInt(KEY_ERROR_COUNT, p.getInt(KEY_ERROR_COUNT, 0) + 1)
            .apply()
    }

    /** The `where` tag of the most recent swallowed error — a literal from our own code. */
    fun lastErrorWhere(context: Context): String? =
        prefs(context).getString(KEY_LAST_ERROR_WHERE, null)

    fun errorCount(context: Context): Int = prefs(context).getInt(KEY_ERROR_COUNT, 0)

    /** Forgets the recorded errors, so the Profile row goes quiet until something new breaks. */
    fun clearErrors(context: Context) = prefs(context).edit()
        .remove(KEY_LAST_ERROR_AT).remove(KEY_LAST_ERROR).remove(KEY_ERROR_COUNT)
        .apply()

    fun lastError(context: Context): String? = prefs(context).getString(KEY_LAST_ERROR, null)

    fun lastErrorAt(context: Context): Long = prefs(context).getLong(KEY_LAST_ERROR_AT, 0L)
}
