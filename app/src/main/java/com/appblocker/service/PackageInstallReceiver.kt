package com.appblocker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.appblocker.data.AppRule
import com.appblocker.data.BlockerDatabase
import com.appblocker.data.InstalledAppsRepository
import com.appblocker.data.NewAppWatcher
import com.appblocker.data.NewAppRules
import com.appblocker.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * When "Add newly installed apps" is on, automatically blocks any newly installed launchable app.
 * Fires on ACTION_PACKAGE_ADDED (a manifest-allowed system broadcast).
 */
class PackageInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // An app was installed or removed → the cached app list is now stale, refresh it.
        if (intent.action == Intent.ACTION_PACKAGE_REMOVED ||
            intent.action == Intent.ACTION_PACKAGE_FULLY_REMOVED
        ) {
            // Ignore the "removed" half of an app update (a matching ADDED follows).
            if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                InstalledAppsRepository.invalidate(context)
            }
            return
        }
        if (intent.action != Intent.ACTION_PACKAGE_ADDED) return
        // EXTRA_REPLACING = an update to an existing app, not a brand-new install.
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
        InstalledAppsRepository.invalidate(context)
        val pkg = intent.data?.schemeSpecificPart ?: return
        if (pkg == context.packageName) return
        val pm = context.packageManager
        // One rule, shared with NewAppWatcher's backstop so the two cannot disagree about an app.
        if (!NewAppRules.shouldAutoBlock(
                addNewApps = SettingsStore.addNewApps(context),
                allowlistMode = SettingsStore.quickBlockAllowlist(context),
                launchable = pm.getLaunchIntentForPackage(pkg) != null,
                isProtector = isProtectiveApp(context, pkg),
                isBrowser = pkg in findRealBrowserPackages(context),
            )
        ) return

        val label = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Wrapped, like every other background path in this app: an exception in a
                // coroutine launched from a bare scope has no handler above it, so a database
                // hiccup here would crash the process — in the background, minutes after the
                // owner installed something, with no way to connect the two. NewAppWatcher.catchUp
                // adds the app on the next service start, so a failure here is recoverable.
                runCatching {
                    val dao = BlockerDatabase.get(context).appRuleDao()
                    // Don't flatten an existing rule: a reinstalled app keeps the mode and daily
                    // limit the owner set for it.
                    if (dao.get(pkg) == null) {
                        dao.upsert(AppRule(packageName = pkg, appLabel = label, isBlocked = true))
                    }
                    // Keep the baseline in step so the backstop doesn't re-consider this app.
                    SettingsStore.setKnownPackages(
                        context, NewAppWatcher.launchablePackages(context),
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}
