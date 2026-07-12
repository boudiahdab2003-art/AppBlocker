package com.appblocker.data

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * "Blocking pauses after an update": when a new version starts for the first time, ALL blocking
 * is switched off until the user taps Reactivate on the Blocking tab — and a running Strict
 * session ENDS (owner's choice: a fresh version is a clean slate; this only fires on a real
 * version change, so reinstalling the same APK is not an escape hatch). One exception, enforced
 * in the service: the adult-content layer stays on (its off-switch is intentionally hard).
 */
object UpdatePause {

    /** Call at every app/service start: detects a version change and arms the pause.
     *  The very first run just records the version — a fresh install has nothing to pause
     *  (which also means the update INTO the first version carrying this feature is
     *  invisible: the old version left no record to compare against). */
    fun checkVersionChange(context: Context) {
        val current = runCatching {
            PackageInfoCompat.getLongVersionCode(
                context.packageManager.getPackageInfo(context.packageName, 0)
            )
        }.getOrNull() ?: return
        val last = SettingsStore.lastSeenVersionCode(context)
        if (last != current) {
            SettingsStore.setLastSeenVersionCode(context, current)
            if (last != -1L) {
                SettingsStore.setUpdatePaused(context, true)
                // Durable intent to end any running Strict session, set BEFORE attempting:
                // prefs writes survive a broadcast receiver's process teardown, the DB
                // coroutine below may not.
                SettingsStore.setStrictClearPending(context, true)
            }
        }
        // Runs on EVERY call (app open, service reconnect, install broadcast) — a clear
        // whose process died mid-write is retried until it actually lands. The flag is
        // consumed only after the write returns, so a Strict session the user starts
        // later is never touched.
        if (SettingsStore.strictClearPending(context)) {
            val appContext = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                BlockerDatabase.get(appContext).focusDao().set(FocusState(id = 0))
                SettingsStore.setStrictClearPending(appContext, false)
            }
        }
    }
}
