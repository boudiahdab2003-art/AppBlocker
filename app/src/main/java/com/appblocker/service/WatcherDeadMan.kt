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
 * alarm two minutes into the future ([DELAY_MS]), so the alarm only ever goes off once nothing has
 * pushed it for that long — which, while the phone is awake, means the watcher's process is gone.
 *
 * Why it exists: a killed watcher used to be noticed by the next scheduled check, the 15-minute
 * worker or the 20-minute alarm, and his reports show `noticedAfter` from 3 to 21 minutes. With the
 * silent repair ([SelfToggle]) the stoppage now lasts about as long as the noticing does, so the
 * noticing is the part left to shorten. On his phone HyperOS never restarts the watcher by itself
 * (proven 22 Sep 2026), so nothing else was ever going to come looking sooner.
 *
 * **Once a minute, screen on or off** — 10 Oct 2026, his request: "slow the check", for battery.
 * v1.171 (invariant 85) pushed it every 5 s to 9 s away while the screen was on, so a kill in use
 * was noticed in about ten seconds; that cost twelve AlarmManager calls a minute, and he chose the
 * battery. Back to the heartbeat alone: a kill is noticed two to four minutes after it (two minutes
 * of lead, up to ninety seconds of inexact-alarm window, then [KILLED_BIND_WAIT_MS]). The shorter
 * bind wait stays — it costs nothing while the watcher is alive.
 *
 * ⚠️ **What it costs on a healthy phone: one AlarmManager call a minute and nothing else.** The
 * alarm is `ELAPSED_REALTIME`, not a wakeup: it never wakes a sleeping phone, and one that came due
 * during sleep is delivered when the phone next wakes — which is when being noticed starts to
 * matter. The pushes run on the uptime clock and pause in deep sleep, so a delivery just after
 * waking is usually a false one; [WatcherDeadManReceiver] answers that from the running watcher in
 * this process, with no check and no new process.
 *
 * Cancelled on an ORDERLY unbind with the switch still on — a space switch — because then nothing
 * died and the ordinary checks own what comes next. A killed process runs no callbacks at all,
 * which is exactly why the alarm it leaves behind is the one that goes off. An unbind that finds the
 * switch turned off brings it forward instead ([armSoon]), so the check that switches it back on
 * (invariant 84) comes within half a minute.
 */
object WatcherDeadMan {

    /** How long without a push before the alarm fires. Two heartbeats, so one late tick is
     *  forgiven. */
    const val DELAY_MS = 2 * 60_000L

    /**
     * How long a process this alarm started waits for Android to hand it the watcher before the
     * check judges it (invariant 85), when the silent repair can act and nothing was just installed.
     * A phone that restarts a crashed watcher does it the moment our process starts
     * (`attachApplicationLocked` brings a waiting service up with it), well inside five seconds; his
     * phone never does, so every second of [SERVICE_BIND_GRACE_MS] here was a second of use unblocked.
     */
    const val KILLED_BIND_WAIT_MS = 5_000L

    /**
     * An install in the last this-long keeps the full [SERVICE_BIND_GRACE_MS]: installing is Android
     * killing our process (invariant 21), and it binds the watcher again only once the install has
     * finished — while an alarm pushed seconds before the kill can come due during it. Android stamps
     * the install as it finishes and binds within seconds of that; three minutes is room to spare.
     */
    private const val INSTALL_WINDOW_MS = 3 * 60_000L

    /** The short fuse [SelfToggle] lights under its own off-and-on, in case its process dies between. */
    private const val SOON_MS = 30_000L

    /**
     * How long [WatcherDeadManReceiver] waits, from our process start, before it judges an unbound
     * watcher (invariant 85). Short only where it buys something and costs nothing: the silent repair
     * is there to act on the answer, and no install can be about to bind the watcher. Anywhere else
     * the verdict's own [SERVICE_BIND_GRACE_MS], as before — an alert is not made faster at the price
     * of a false one.
     */
    internal fun bindWaitMs(repairPermitted: Boolean, justInstalled: Boolean): Long =
        if (repairPermitted && !justInstalled) KILLED_BIND_WAIT_MS else SERVICE_BIND_GRACE_MS

    /** Whether this package was installed or updated in the last [INSTALL_WINDOW_MS]. Unreadable
     *  counts as yes: the full grace, the old behaviour. */
    internal fun justInstalled(context: Context): Boolean = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        System.currentTimeMillis() - info.lastUpdateTime < INSTALL_WINDOW_MS
    }.getOrDefault(true)

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

    /** Brings it forward to [SOON_MS]: the safety net under [SelfToggle]'s off-and-on, a return's
     *  wait (invariant 83), and the switch found off at an unbind (invariant 84). */
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
        // and nothing could push it. Push it and stop — no check, no cost.
        if (BlockerAccessibilityService.isConnected()) {
            WatcherDeadMan.arm(app)
            return
        }
        // Held open while the check runs, because the check may be about to switch the watcher off
        // and on, and a receiver's process is otherwise free to be reclaimed the moment it returns.
        val done = goAsync()
        thread(name = "appblocker-deadman") {
            try {
                // A process this young may still be about to be handed the watcher: wait for that
                // here, in this process, rather than deferring the answer to a WorkManager job — the
                // thing this phone throttles, and the reason this alarm exists. Five seconds when the
                // silent repair can act and nothing was installed in the last three minutes, the full
                // grace otherwise (invariant 85) — and the check is told which, so its verdict agrees
                // with the wait.
                val grace = WatcherDeadMan.bindWaitMs(
                    repairPermitted = SelfToggle.permitted(app),
                    justInstalled = WatcherDeadMan.justInstalled(app),
                )
                val age = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
                val wait = grace + 1_000L - age
                if (wait > 0L) Thread.sleep(wait)
                ProtectionWatchdog.checkAndNotify(app, calledBy = OutageLog.EndedBy.BACKGROUND, startGraceMs = grace)
            } catch (_: InterruptedException) {
                // Nothing to do: the check did not run, and the next check will.
            } finally {
                done.finish()
            }
        }
    }
}
