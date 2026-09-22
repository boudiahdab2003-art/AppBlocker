package com.appblocker.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import com.appblocker.data.OutageLog
import kotlin.concurrent.thread

/**
 * **A dead-man's switch for the watcher** (invariant 82): while it is alive it keeps pushing one
 * alarm [DELAY_MS] into the future, so the alarm only ever goes off once nothing has pushed it for
 * that long — which, while the phone is awake, means the watcher's process is gone.
 *
 * Why it exists: a killed watcher used to be noticed by the next scheduled check, the 15-minute
 * worker or the 20-minute alarm, and his reports show `noticedAfter` from 3 to 21 minutes. With the
 * silent repair ([SelfToggle]) the stoppage now lasts about as long as the noticing does, so the
 * noticing is the part left to shorten. On his phone HyperOS never restarts the watcher by itself
 * (proven 22 Sep 2026), so nothing else was ever going to come looking sooner.
 *
 * ⚠️ **What it costs on a healthy phone: one AlarmManager call a minute and nothing else.** The alarm
 * is `ELAPSED_REALTIME`, not a wakeup: it never wakes a sleeping phone, and one that came due during
 * sleep is delivered when the phone next wakes — which is when being noticed starts to matter. The
 * heartbeat that pushes it runs on the uptime clock and pauses in deep sleep, so a delivery just
 * after waking is usually a false one; [WatcherDeadManReceiver] answers that from the running
 * watcher in this process, with no check and no new process.
 *
 * Cancelled on an ORDERLY unbind — a space switch or the switch turned off — because then nothing
 * died and the ordinary checks own what comes next. A killed process runs no callbacks at all,
 * which is exactly why the alarm it leaves behind is the one that goes off.
 */
object WatcherDeadMan {

    /** How long without a push before the alarm fires. Two heartbeats, so one late tick is forgiven. */
    const val DELAY_MS = 2 * 60_000L

    /** The short fuse [SelfToggle] lights under its own off-and-on, in case its process dies between. */
    private const val SOON_MS = 30_000L

    private fun pending(context: Context, create: Boolean): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, WatcherDeadManReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or
                if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE,
        )

    /** Pushes the alarm [DELAY_MS] away again. Called by the watcher on connect and every heartbeat. */
    fun arm(context: Context) = set(context, DELAY_MS)

    /** Brings it forward to [SOON_MS]: the safety net under [SelfToggle]'s off-and-on. */
    fun armSoon(context: Context) = set(context, SOON_MS)

    private fun set(context: Context, delayMs: Long) {
        runCatching {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val pi = pending(context, create = true) ?: return
            am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + delayMs, pi)
        }
    }

    /** The watcher was unbound on purpose. Nothing died, so nothing needs to go off. */
    fun cancel(context: Context) {
        runCatching {
            val pi = pending(context, create = false) ?: return
            context.getSystemService(AlarmManager::class.java)?.cancel(pi)
            pi.cancel()
        }
    }

    private const val REQUEST_CODE = 0x0dead
}

/**
 * Where the dead-man alarm lands. Not exported and without an intent-filter: only our own
 * PendingIntent reaches it, like [ProtectionAlarmReceiver].
 */
class WatcherDeadManReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        // The watcher lives in this process and is bound: the alarm came due while the phone slept
        // and the heartbeat could not push it. Push it and stop — no check, no cost.
        if (BlockerAccessibilityService.isConnected()) {
            WatcherDeadMan.arm(app)
            return
        }
        // Held open while the check runs, because the check may be about to switch the watcher off
        // and on, and a receiver's process is otherwise free to be reclaimed the moment it returns.
        val done = goAsync()
        thread(name = "appblocker-deadman") {
            try {
                // A process this young may still be about to be handed the watcher: wait the bind
                // grace out here, in this process, rather than deferring the answer to a WorkManager
                // job — the thing this phone throttles, and the reason this alarm exists.
                val age = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
                val wait = SERVICE_BIND_GRACE_MS + 1_000L - age
                if (wait > 0L) Thread.sleep(wait)
                ProtectionWatchdog.checkAndNotify(app, calledBy = OutageLog.EndedBy.BACKGROUND)
            } catch (_: InterruptedException) {
                // Nothing to do: the check did not run, and the next check will.
            } finally {
                done.finish()
            }
        }
    }
}
