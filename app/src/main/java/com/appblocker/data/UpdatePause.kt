package com.appblocker.data

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat

/**
 * "Blocking pauses after an update": when a new version starts for the first time, ALL blocking
 * is switched off until the user taps Reactivate on the Blocking tab. Two deliberate exceptions,
 * enforced in the service: a running Strict session keeps blocking (an update must never be an
 * escape hatch), and the adult-content layer stays on (its off-switch is intentionally hard).
 */
object UpdatePause {

    /** Call at every app/service start: detects a version change and arms the pause.
     *  The very first run just records the version — a fresh install has nothing to pause. */
    fun checkVersionChange(context: Context) {
        val current = runCatching {
            PackageInfoCompat.getLongVersionCode(
                context.packageManager.getPackageInfo(context.packageName, 0)
            )
        }.getOrNull() ?: return
        val last = SettingsStore.lastSeenVersionCode(context)
        if (last == current) return
        SettingsStore.setLastSeenVersionCode(context, current)
        if (last != -1L) SettingsStore.setUpdatePaused(context, true)
    }
}
