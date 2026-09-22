package com.appblocker.service

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import com.appblocker.Dist
import com.appblocker.data.OutageLog
import com.appblocker.data.SelfToggleLog
import kotlin.concurrent.thread

/**
 * **Revives a killed watcher by switching AppBlocker's own Accessibility entry off and on** —
 * silently, the way the owner does it by hand (invariant 82). See [SelfToggleLog] for why this is
 * the repair, the proof from his phone, and the rules; this is the part that writes the setting.
 *
 * It needs `WRITE_SECURE_SETTINGS`, which Android lets only a computer grant:
 * `adb shell pm grant com.appblocker android.permission.WRITE_SECURE_SETTINGS` (and again with
 * `--user 10` for a Second Space). Without it every call declines and the alert stays the answer.
 * The Play build never asks: [Dist.SELF_TOGGLE] is false there and the permission is not declared.
 *
 * ⚠️ **The off write is the dangerous half.** Everything here is arranged so that the switch can
 * only be left off by our hand for as long as it takes the next check to run: the attempt and an
 * in-flight marker are committed before the first write ([SelfToggleLog.markAttempt]), the on write
 * runs in a `finally` with retries, and [finishInterrupted] — the first thing every check does —
 * puts back an entry a dead process left off.
 */
object SelfToggle {

    private const val TAG = "SelfToggle"

    /**
     * Between the off write and the on write. On his phone 0.3 s was enough for Android to drop the
     * "crashed" mark; this leaves room for a phone busy enough to have killed the watcher.
     */
    private const val GAP_MS = 800L

    /** Tries at the on write before leaving it to [finishInterrupted]. */
    private const val ON_TRIES = 3

    /** Whether this build and this phone let AppBlocker write the switch at all. */
    fun permitted(context: Context): Boolean =
        Dist.SELF_TOGGLE && runCatching {
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    /**
     * Called by the watchdog on a check that finds the watcher killed. True when a toggle was started
     * — the caller then holds its alert, because blocking should be back within seconds and an alert
     * about a stoppage that is already over is noise. The next stalled check alerts if it is not.
     */
    fun maybeRepair(context: Context, arm: String): Boolean {
        var started = false
        guarded(context, "selfToggle") {
            val app = context.applicationContext
            SelfToggleLog.resolveStale(app)
            val skip = SelfToggleLog.decide(
                watcherUnbound = arm == OutageLog.DetectedBy.UNBOUND &&
                    !BlockerAccessibilityService.isConnected(),
                switchedOn = AccessibilityUtil.isEnabled(app),
                permitted = permitted(app),
                attemptPending = SelfToggleLog.isPending(app),
                sinceLastAttemptMs = SelfToggleLog.sinceLastAttemptMs(app),
                futileStreak = SelfToggleLog.futileStreak(app),
            )
            if (skip != null) return@guarded
            // No marker, no toggle: a switch turned off with nothing on disk to say it was ours
            // could not be put back by anything that runs after this process.
            if (!SelfToggleLog.markAttempt(app)) return@guarded
            // A safety net under the only dangerous moment: if this process dies between the two
            // writes, this alarm starts a new one within a minute, and its check finishes the job.
            WatcherDeadMan.armSoon(app)
            started = true
            // Off the calling thread: the checks run on the main thread of receivers, the tile and
            // the app's own screen, and the gap below must not freeze any of them.
            thread(name = "appblocker-self-toggle") { toggle(app) }
        }
        return started
    }

    private fun toggle(app: Context) {
        val ours = ComponentName(app, BlockerAccessibilityService::class.java).flattenToString()
        var offWritten = false
        try {
            val now = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            offWritten = Settings.Secure.putString(
                app.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                AccessibilityUtil.listWithout(now, ours),
            )
            Thread.sleep(GAP_MS)
        } catch (t: Throwable) {
            Log.w(TAG, "switching the entry off failed", t)
        } finally {
            val on = writeOn(app, ours)
            // The marker exists only to finish an off that went through. If the off never happened
            // there is nothing of ours to finish, and a marker left behind could later read his own
            // switch-off as our unfinished toggle — so it goes whether or not the on write did.
            if (on || !offWritten) SelfToggleLog.clearInFlight(app)
            // Refused outright: nothing changed and nothing will rebind. Judged now, so a revoked
            // permission stops the tries instead of repeating them. An off that went through and an
            // on that did not is NOT this: the marker stays, and finishInterrupted owns it.
            if (!offWritten) SelfToggleLog.noteFailed(app)
        }
    }

    /**
     * Writes the entry back on: the list as it reads NOW with ours in it, so a service Android or he
     * changed in the gap is kept as he left it — and the master switch, which Android turns off
     * when the list it reads is empty. True when both writes went through.
     */
    private fun writeOn(app: Context, ours: String): Boolean {
        repeat(ON_TRIES) { attempt ->
            val ok = runCatching {
                val now = Settings.Secure.getString(
                    app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                )
                Settings.Secure.putString(
                    app.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    AccessibilityUtil.listWith(now, ours),
                ) && Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }.onFailure { Log.w(TAG, "switching the entry back on failed (try ${attempt + 1})", it) }
                .getOrDefault(false)
            if (ok) return true
            runCatching { Thread.sleep(300L) }
        }
        return false
    }

    /**
     * **Puts back an entry our own toggle left off.** The first thing every check does, before it
     * reads the switch: a process killed between the two writes leaves the entry off with our marker
     * beside it, and read as it stands that is the switch being off — filed as his choice, alerted
     * about, and left. Only our own recent marker is acted on, never an entry he switched off.
     *
     * @return true while a toggle is still between its writes — the caller must not judge the switch
     *   in that moment, because what it would read is ours.
     */
    fun finishInterrupted(context: Context): Boolean {
        val app = context.applicationContext
        return when (SelfToggleLog.markerState(app, switchedOn = AccessibilityUtil.isEnabled(app))) {
            SelfToggleLog.Marker.NONE -> false
            SelfToggleLog.Marker.IN_FLIGHT -> true
            SelfToggleLog.Marker.STALE -> {
                SelfToggleLog.clearInFlight(app)
                false
            }
            SelfToggleLog.Marker.FINISH_ON -> {
                val ours = ComponentName(app, BlockerAccessibilityService::class.java).flattenToString()
                if (permitted(app) && writeOn(app, ours)) SelfToggleLog.clearInFlight(app)
                false
            }
        }
    }
}
