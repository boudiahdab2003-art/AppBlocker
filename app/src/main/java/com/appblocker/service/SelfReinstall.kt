package com.appblocker.service

import android.content.Context
import com.appblocker.Dist
import com.appblocker.data.OutageLog
import com.appblocker.data.OwnUi
import com.appblocker.data.SelfReinstallLog
import com.appblocker.data.SettingsStore
import com.appblocker.data.SilentInstaller
import com.appblocker.data.Updater

/**
 * **Reinstalls AppBlocker's own copy when the watcher is found killed, so Android binds it again**
 * (invariant 81). The rules, the verdicts and why this exists: [SelfReinstallLog]. This is only the
 * part that acts.
 *
 * Tried before the reopen ([SelfRestore]), because it is the one repair seen to work on the owner's
 * phone: on 15 Sep 2026 an install brought back a watcher the phone had left dead for an hour, while
 * the reopen has never once brought it back there. When this declines — cooldown, given up, no
 * permission, the Play build — the reopen still gets its turn.
 */
object SelfReinstall {

    /**
     * Called by the watchdog on a check that finds blocking stalled. True when a reinstall was
     * attempted — the process is about to be replaced, so nothing else should start on this check.
     *
     * Synchronous on purpose: the copy is a few megabytes, and a receiver's process may be reclaimed
     * the moment `onReceive` returns, which would strand a half-written session on the system's
     * books ([SilentInstaller]'s KDoc on why that matters).
     */
    fun maybeRepair(context: Context, arm: String, calledBy: String): Boolean {
        var attempted = false
        guarded(context, "selfReinstall") {
            val app = context.applicationContext
            SelfReinstallLog.resolveStale(app)
            val skip = SelfReinstallLog.decide(
                possible = Dist.SELF_UPDATE && SilentInstaller.possible(),
                canInstall = Updater.canInstall(app),
                watcherUnbound = arm == OutageLog.DetectedBy.UNBOUND &&
                    !BlockerAccessibilityService.isConnected(),
                appOpen = calledBy == OutageLog.EndedBy.APP_OPENED || OwnUi.visible,
                updatePaused = SettingsStore.updatePaused(app),
                attemptPending = SelfReinstallLog.isPending(app),
                sinceLastAttemptMs = SelfReinstallLog.sinceLastAttemptMs(app),
                futileStreak = SelfReinstallLog.futileStreak(app),
            )
            if (skip != null) return@guarded
            // Written first, with commit(): the install replaces this process, and the next one is
            // what judges the attempt.
            SelfReinstallLog.markAttempt(app)
            attempted = true
            if (!SilentInstaller.reinstallSelf(app)) SelfReinstallLog.noteFailed(app)
        }
        return attempted
    }
}
