package com.appblocker.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * "Blocking pauses after an update": when a new version starts for the first time, ALL blocking is
 * switched off until the user taps Reactivate on the Blocking tab. One exception, enforced in the
 * service: the adult-content layer stays on (its off-switch is intentionally hard).
 *
 * **A running Strict session survives the update, and suppresses the pause entirely** (owner's
 * decision). Earlier versions did the opposite — an update ENDED the session — with the reasoning
 * that a fresh version is a clean slate, and that reinstalling the same APK could not be used as an
 * escape hatch because the pause only fires on a real version change. True as far as it went, but
 * it missed the case that matters here: releases are frequent, so *whenever an unreleased version
 * existed*, a Strict session could be ended in two taps from inside the app — Update, install,
 * session gone. Every other protective control is locked during Strict; this one was the way out.
 *
 * The pause is therefore skipped rather than the session being cleared. That keeps blocking on
 * across the update, and it keeps every *report* of the pause honest by construction: nothing
 * downstream has to learn about Strict, because [SettingsStore.updatePaused] is never set while a
 * session is running. (The watchdog notification, the Blocking-tab banner and the Profile status
 * row all read that flag directly and would otherwise announce that blocking was off while Strict
 * was actively enforcing it.)
 */
object UpdatePause {

    /** Call at every app/service start: detects a version change and arms the pause.
     *  The very first run just records the version — a fresh install has nothing to pause
     *  (which also means the update INTO the first version carrying this feature is
     *  invisible: the old version left no record to compare against). */
    fun checkVersionChange(context: Context) {
        val current = AppVersion.code(context)
        if (current < 0L) return
        val last = SettingsStore.lastSeenVersionCode(context)
        if (last != current) {
            // An update the owner did not ask for must not switch his blocking off and then wait
            // for a tap he has no reason to know is needed. The pause exists so that a person who
            // just installed something confirms it still works; nobody is standing there after a
            // silent auto-install, so blocking would simply be off — silently, for however long
            // until he next opened the app. If the update really did break the watcher,
            // ProtectionWatchdog notices and says so, which is the mechanism built for exactly
            // that. See SilentInstaller.
            val automatic = SettingsStore.autoInstalled(context)
            SettingsStore.setAutoInstalled(context, false)
            // A version change is the only proof an install actually landed, so this is where the
            // "already tried this one" note is torn up — whoever installed it. Leaving it would
            // make the next release look like the one that was already declined.
            SettingsStore.setAutoUpdateAttempt(context, null)
            if (last != -1L && !automatic) {
                // Durable intent, written BEFORE anything is attempted: prefs writes survive a
                // broadcast receiver's process teardown, the coroutine below may not.
                SettingsStore.setUpdatePausePending(context, true)
            }
            // Outside the branch above, and outside the `last != -1L` check with it: a version
            // change means the downloaded APK has served its purpose whoever installed it. It is
            // tens of megabytes, and an auto-install leaves one behind on every single release.
            if (last != -1L) Updater.discardDownload(context)
            // Recorded LAST, deliberately. Recording it first meant that a process killed between
            // the two writes — a real risk here, since this also runs from a broadcast receiver —
            // left the version already updated and the pause never armed, with no second chance:
            // the next start sees last == current and concludes nothing changed. Writing it last
            // makes the whole thing re-runnable, and re-running it is harmless.
            SettingsStore.setLastSeenVersionCode(context, current)
        }
        resolvePendingPause(context)
    }

    /**
     * Turns a pending pause into a real one — unless a Strict session is running, in which case the
     * session keeps blocking on and the pause is dropped.
     *
     * Runs on EVERY call (app open, service reconnect, install broadcast), so a decision whose
     * process died before it landed is retried until it does. Arming the pause asynchronously is
     * safe in a way that clearing a Strict session never was: the pause switches blocking OFF, so
     * being a few milliseconds late is harmless, while being wrong is not.
     */
    private fun resolvePendingPause(context: Context) {
        if (!SettingsStore.updatePausePending(context)) return
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            if (!strictSessionRunning(app)) SettingsStore.setUpdatePaused(app, true)
            // Consumed only after the decision above has been made, so an interrupted run
            // re-decides rather than silently dropping the pause.
            SettingsStore.setUpdatePausePending(app, false)
        }
    }

    /** Reads the Strict row and asks [SessionClock] — the same clock-change-proof rule the watcher
     *  enforces with, rather than a second opinion about what "running" means. */
    private suspend fun strictSessionRunning(context: Context): Boolean {
        val state = BlockerDatabase.get(context).focusDao().get().first() ?: return false
        return SessionClock.remaining(
            state.realtimeStartMillis, state.realtimeEndMillis,
            state.startTimeMillis, state.endTimeMillis,
            state.bootCount, DeviceBoot.count(context),
        ) > 0L
    }
}
