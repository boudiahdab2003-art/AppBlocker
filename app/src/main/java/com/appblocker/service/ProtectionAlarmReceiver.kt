package com.appblocker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.appblocker.data.OutageLog
import com.appblocker.data.ProtectionPulse

/**
 * The second wake source: an alarm whose only job is to notice that **WorkManager has stopped
 * running**.
 *
 * See [ProtectionPulse] for why this exists and, just as importantly, what it does not claim. In
 * one line: every out-of-process check in this app is a WorkManager job, and `ProtectionScheduler`
 * says in its own KDoc that the conditions which kill the watcher are the conditions that throttle
 * WorkManager — so the alert about the failure is subject to the same force as the failure.
 *
 * ⚠️ **It re-arms first, before anything else can throw.** An inexact alarm has no handler
 * re-posting it — miss one firing and the chain is over for the life of the install, with nothing
 * anywhere to notice. That is invariant 35 (a loop that re-arms inside its own error handler) at
 * the process level, and worse, because there is no `postDelayed` to fall back on. It is also
 * armed from `ProtectionScheduler.ensureScheduled`, `BootReceiver` and `onServiceConnected`, so a
 * broken chain repairs itself the next time the app is opened or the phone restarts.
 */
class ProtectionAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // First, unconditionally: see above.
        ProtectionScheduler.ensureAlarmScheduled(context)
        guarded(context, "protectionAlarm") {
            val silent = ProtectionPulse.silentFor(context)
            // UNKNOWN (no stamp yet, or a reboot in between) is not silence — same rule as every
            // other "can't tell" in this app. Doing nothing is the healthy-phone path and it is
            // meant to be almost all of them: one prefs read per firing, then stop.
            if (silent == ProtectionPulse.UNKNOWN || silent < WORKER_SILENT_MS) return@guarded
            // The number nobody has: how often WorkManager stops running on his phone.
            ProtectionPulse.recordSilent(context)
            // Only now is a real check worth its cost, and it re-stamps the pulse on its way
            // through so one silent spell is counted once rather than every twenty minutes.
            ProtectionPulse.stamp(context)
            ProtectionWatchdog.checkAndNotify(context, calledBy = OutageLog.EndedBy.BACKGROUND)
        }
    }
}

/**
 * How long WorkManager has to have been quiet before the alarm does anything.
 *
 * The periodic worker is on a fifteen-minute cycle and WorkManager is allowed to be late, so
 * twenty-five minutes is "later than late" rather than "not exactly on time" — the alarm must not
 * become a second copy of a check that is merely running a few minutes behind.
 */
internal const val WORKER_SILENT_MS = 25 * 60_000L

/**
 * How often the alarm fires.
 *
 * Doze throttles `setAndAllowWhileIdle` to roughly one firing per nine to fifteen minutes per app,
 * and app-standby buckets add their own quota on top — so this is the same order as the worker it
 * is watching, and asking for less would not get it. That is fine: the point is diversity of
 * mechanism, not frequency. Twenty minutes keeps it just under [WORKER_SILENT_MS] so a genuinely
 * stopped worker is noticed on the first firing rather than the second.
 */
internal const val ALARM_INTERVAL_MS = 20 * 60_000L
