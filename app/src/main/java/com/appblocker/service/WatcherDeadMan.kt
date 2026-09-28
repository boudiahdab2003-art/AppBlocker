package com.appblocker.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import com.appblocker.data.OutageLog
import kotlin.concurrent.thread

/**
 * **A dead-man's switch for the watcher** (invariant 82): while it is alive it keeps pushing one
 * alarm a few seconds or two minutes into the future ([delayMs]), so the alarm only ever goes off
 * once nothing has pushed it for that long — which, while the phone is awake, means the watcher's
 * process is gone.
 *
 * Why it exists: a killed watcher used to be noticed by the next scheduled check, the 15-minute
 * worker or the 20-minute alarm, and his reports show `noticedAfter` from 3 to 21 minutes. With the
 * silent repair ([SelfToggle]) the stoppage now lasts about as long as the noticing does, so the
 * noticing is the part left to shorten. On his phone HyperOS never restarts the watcher by itself
 * (proven 22 Sep 2026), so nothing else was ever going to come looking sooner.
 *
 * ⭐ **Seconds while the screen is on** (invariant 85) — his choice of 28 Sep 2026, "make it notice
 * faster", knowing it costs a little battery. The first four real kills the repair answered (27–28
 * Sep) were each back in 2–4 minutes, and nearly all of that was this alarm waiting out its two
 * minutes. With the screen on the watcher pushes it every [FAST_PUSH_MS] to [FAST_DELAY_MS] away;
 * with the screen off nothing changes: [DELAY_MS], pushed by the heartbeat, as before. Nothing can
 * be opened on a dark screen, and the cost stays where the phone is awake anyway.
 *
 * ⚠️ **What it costs on a healthy phone: one AlarmManager call per push and nothing else** — twelve
 * a minute with the screen on, one a minute with it off. The alarm is `ELAPSED_REALTIME`, not a
 * wakeup: it never wakes a sleeping phone, and one that came due during sleep is delivered when the
 * phone next wakes — which is when being noticed starts to matter. The pushes run on the uptime
 * clock and pause in deep sleep, so a delivery just after waking is usually a false one;
 * [WatcherDeadManReceiver] answers that from the running watcher in this process, with no check and
 * no new process. Screen off is exactly when the phone sleeps, which is why the short delay stops
 * there: a nine-second alarm would be delivered on nearly every wake of a sleeping phone.
 *
 * Cancelled on an ORDERLY unbind with the switch still on — a space switch — because then nothing
 * died and the ordinary checks own what comes next. A killed process runs no callbacks at all,
 * which is exactly why the alarm it leaves behind is the one that goes off. An unbind that finds the
 * switch turned off brings it forward instead ([armSoon]), so the check that switches it back on
 * (invariant 84) comes within half a minute.
 */
object WatcherDeadMan {

    /** Screen off: how long without a push before the alarm fires. Two heartbeats, so one late
     *  tick is forgiven. */
    const val DELAY_MS = 2 * 60_000L

    /**
     * Screen on: how long without a push before the alarm fires (invariant 85).
     *
     * ⚠️ **Under ten seconds on purpose.** An inexact alarm may be delivered up to 75% of its lead
     * late, and Android only stops adding that for leads under ten seconds
     * (`AlarmManagerService.MIN_FUZZABLE_INTERVAL`) — on the Android 16 emulator the 30 s [SOON_MS]
     * went off at 52 s, the far end of its window. Nine seconds is delivered when it is due, with no
     * exact-alarm permission. `WatcherDeadManTest` fails if it reaches ten.
     */
    const val FAST_DELAY_MS = 9_000L

    /** Screen on: how often the watcher pushes it — a push can be four seconds late before the
     *  alarm goes off for nothing (and nothing is harmless: the live watcher just pushes it again). */
    const val FAST_PUSH_MS = 5_000L

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

    /** The delay for the screen as it is: seconds while it is on, [DELAY_MS] while it is off or
     *  cannot be read — the behaviour before invariant 85, never a guess. */
    internal fun delayMs(screenOn: Boolean?): Long = if (screenOn == true) FAST_DELAY_MS else DELAY_MS

    /**
     * How long [WatcherDeadManReceiver] waits, from our process start, before it judges an unbound
     * watcher (invariant 85). Short only where it buys something and costs nothing: the silent repair
     * is there to act on the answer, and no install can be about to bind the watcher. Anywhere else
     * the verdict's own [SERVICE_BIND_GRACE_MS], as before — an alert is not made faster at the price
     * of a false one.
     */
    internal fun bindWaitMs(repairPermitted: Boolean, justInstalled: Boolean): Long =
        if (repairPermitted && !justInstalled) KILLED_BIND_WAIT_MS else SERVICE_BIND_GRACE_MS

    /** Whether the screen is on, or null when it cannot be read. */
    fun screenOn(context: Context): Boolean? =
        runCatching { context.getSystemService(PowerManager::class.java)?.isInteractive }.getOrNull()

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

    /** Pushes the alarm [delayMs] away again. Called by the watcher on connect, every heartbeat and,
     *  while the screen is on, every [FAST_PUSH_MS]. */
    fun arm(context: Context, screenOn: Boolean? = screenOn(context)) = set(context, delayMs(screenOn))

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
