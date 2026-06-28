package com.appblocker.data

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import com.appblocker.service.UsageTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** An installed launchable app: package, display name, and its (cached) icon bitmap. */
data class InstalledApp(val packageName: String, val label: String, val icon: Bitmap?)

/**
 * Process-wide cache of the installed launchable apps.
 *
 * Scanning every app and decoding its icon is expensive, so we do it once per app run
 * and reuse the result across every editor screen instead of re-scanning on each open.
 * The cache is refreshed only when an app is actually installed or removed
 * (see [com.appblocker.service.PackageInstallReceiver]).
 */
object InstalledAppsRepository {

    private val _apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val apps: StateFlow<List<InstalledApp>> = _apps

    /** Foreground minutes per package today — feeds the "most used" part of the picker order. */
    private val _usage = MutableStateFlow<Map<String, Int>>(emptyMap())
    val usage: StateFlow<Map<String, Int>> = _usage

    @Volatile
    var loaded = false
        private set

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Loads the app list + usage snapshot once; returns immediately if already cached. */
    suspend fun ensureLoaded(context: Context) {
        if (loaded) return
        val appContext = context.applicationContext
        mutex.withLock {
            if (loaded) return
            _apps.value = withContext(Dispatchers.IO) { loadLaunchableApps(appContext) }
            _usage.value = withContext(Dispatchers.IO) { UsageTracker.minutesByPackageToday(appContext) }
            loaded = true
        }
    }

    /** Recomputes the usage snapshot in the background (keeps the order current). */
    fun refreshUsage(context: Context) {
        val appContext = context.applicationContext
        scope.launch { _usage.value = UsageTracker.minutesByPackageToday(appContext) }
    }

    /** Marks the cache stale and reloads it in the background (e.g. after install/uninstall). */
    fun invalidate(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            mutex.withLock {
                _apps.value = loadLaunchableApps(appContext)
                loaded = true
            }
        }
    }

    private fun loadLaunchableApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .filter { it.packageName != context.packageName }
            .map { info ->
                val icon = runCatching {
                    pm.getApplicationIcon(info.packageName).toBitmap(96, 96)
                }.getOrNull()
                InstalledApp(info.packageName, pm.getApplicationLabel(info).toString(), icon)
            }
            .toList()
    }
}
