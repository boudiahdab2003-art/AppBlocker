package com.appblocker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.appblocker.data.AppRule
import com.appblocker.data.BlockerDatabase
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
        if (intent.action != Intent.ACTION_PACKAGE_ADDED) return
        // EXTRA_REPLACING = an update to an existing app, not a brand-new install.
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
        if (!SettingsStore.addNewApps(context)) return

        val pkg = intent.data?.schemeSpecificPart ?: return
        if (pkg == context.packageName) return
        val pm = context.packageManager
        if (pm.getLaunchIntentForPackage(pkg) == null) return // skip non-launchable / background apps

        val label = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                BlockerDatabase.get(context).appRuleDao()
                    .upsert(AppRule(packageName = pkg, appLabel = label, isBlocked = true))
            } finally {
                pending.finish()
            }
        }
    }
}
