package com.appblocker.data

import android.content.Context
import android.content.pm.PackageManager

/**
 * The backstop behind "Add newly installed apps".
 *
 * **Why a backstop is needed at all.** That setting is enforced by a broadcast receiver
 * ([com.appblocker.service.PackageInstallReceiver]) listening for `PACKAGE_ADDED`, and a broadcast
 * is a single delivery with no retry. Android does not deliver it to an app in the *stopped* state
 * — which is what a **force stop** produces, and v1.109 deliberately stopped guarding the Force
 * stop button. It is also missed if the write fails, if the app is disabled, or simply if the
 * process cannot start at that moment. Every one of those failures is silent, and what it leaves
 * behind is a freshly installed app that the owner believes is blocked and isn't. Under-blocking,
 * invisible, on the feature whose entire job is to stop a new app becoming a way round the others.
 *
 * So: remember which launchable packages we have already seen, and reconcile on every service
 * start. The receiver stays as the fast path — this only catches what it missed.
 *
 * **The dangerous direction is the one this file guards hardest.** Getting the baseline wrong
 * means blocking every app on the phone at once, which is far worse than the hole being fixed.
 * Hence [newlyInstalled] answering *nothing* when there is no baseline yet, and the baseline being
 * refreshed even while the setting is off — otherwise switching it on after a year would treat a
 * year of installs as new.
 */
object NewAppWatcher {

    /**
     * Packages present now that we had not seen before.
     *
     * **A null baseline means "we have never looked", and must yield nothing.** The first run on
     * any phone has no record, and treating that as "every app is new" would block the lot. The
     * caller stores the current set immediately afterwards, so the first run teaches and the
     * second run enforces.
     */
    internal fun newlyInstalled(baseline: Set<String>?, current: Set<String>): Set<String> =
        if (baseline == null) emptySet() else current - baseline

    /** Every launchable package except our own — the same definition the app list uses, so
     *  "new app" means the same thing here as it does on screen. */
    fun launchablePackages(context: Context): Set<String> {
        val pm = context.packageManager
        return runCatching {
            pm.getInstalledApplications(0)
                .asSequence()
                .map { it.packageName }
                .filter { it != context.packageName }
                .filter { pm.getLaunchIntentForPackage(it) != null }
                .toSet()
        }.getOrElse { emptySet() }
    }

    /**
     * Reconciles the installed apps against what we have seen, blocking anything new when the
     * setting is on. Safe to call often; does nothing on a phone where nothing has changed.
     *
     * Returns the packages it blocked, for logging and tests.
     */
    suspend fun catchUp(context: Context): Set<String> {
        val current = launchablePackages(context)
        // An empty read is a failed read, not "no apps installed" (invariant 10): adopting it
        // would wipe the baseline and make every app look new on the next pass.
        if (current.isEmpty()) return emptySet()

        val baseline = SettingsStore.knownPackages(context)
        // Blocklist-only, exactly like the receiver: in Allowlist mode a new app is already
        // blocked by not being on the list, so there is nothing to add.
        val enforcing = SettingsStore.addNewApps(context) &&
            !SettingsStore.quickBlockAllowlist(context)
        val newOnes = if (enforcing) newlyInstalled(baseline, current) else emptySet()

        if (newOnes.isNotEmpty()) {
            val dao = BlockerDatabase.get(context).appRuleDao()
            val pm = context.packageManager
            newOnes.forEach { pkg ->
                runCatching {
                    // Keep any rule that already exists — a package can be reinstalled, and its
                    // mode and daily limit are the owner's settings, not ours to reset.
                    if (dao.get(pkg) != null) return@runCatching
                    val label = runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    }.getOrDefault(pkg)
                    dao.upsert(AppRule(packageName = pkg, appLabel = label, isBlocked = true))
                }
            }
        }
        // Always refreshed, even when the setting is off: the baseline is a record of what we have
        // seen, not of what we have blocked. Letting it go stale while the toggle is off is what
        // would turn switching it on into a mass block.
        SettingsStore.setKnownPackages(context, current)
        return newOnes
    }
}
