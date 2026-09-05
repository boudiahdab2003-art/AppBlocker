package com.appblocker.data

import android.content.Context
import android.os.SystemClock

/**
 * Whether the blocking watcher is actually alive, and what has gone wrong lately.
 *
 * The app used to only know whether the accessibility service was *switched on* in Settings.
 * "Switched on but dead" — the process killed by an aggressive OEM battery manager, which is the
 * normal failure on HyperOS/Xiaomi — looked exactly like healthy. The service now stamps
 * [recordEvent] as it works, so the watchdog can tell the difference.
 *
 * Shares `appblocker_prefs` with [SettingsStore]. Writes are throttled by the caller.
 */
object ServiceHealth {
    private const val PREFS = "appblocker_prefs"
    private const val KEY_LAST_EVENT = "health_last_event_at"
    private const val KEY_LAST_ALIVE = "health_last_alive_at"
    private const val KEY_FOUND_DEAD = "health_found_dead_count"
    private const val KEY_REVIVES = "health_revive_count"
    private const val KEY_REVIVE_FAILS = "health_revive_fail_count"
    private const val KEY_PROBE_FAILS = "health_probe_fail_streak"
    private const val KEY_UNBOUND_AT = "health_last_unbound_at"
    private const val KEY_UNBINDS = "health_unbind_count"
    private const val KEY_INTERRUPTS = "health_interrupt_count"
    private const val KEY_LAST_ERROR_AT = "health_last_error_at"
    private const val KEY_LAST_ERROR = "health_last_error"
    private const val KEY_LAST_ERROR_WHERE = "health_last_error_where"
    private const val KEY_ERROR_COUNT = "health_error_count"

    /** Don't touch disk on every accessibility event — one write a minute is plenty. */
    private const val WRITE_THROTTLE_MS = 60_000L

    /**
     * When the last write happened, **monotonically** (invariant 9).
     *
     * ⚠️ This used to hold `System.currentTimeMillis()`, and the throttle read
     * `now - lastWrittenAt < WRITE_THROTTLE_MS`. After the wall clock moved *backwards* — an
     * unsynced clock at boot, or a manual change — that subtraction is negative, so the branch
     * returned and `health_last_event_at` stopped advancing until the clock caught back up.
     * Downstream, `protectionState` computes `now - lastEventAt` against a stamp from the future
     * and answers OK, so the deaf-but-bound detector — the one written for exactly the failure
     * the owner reports — went blind for the length of the jump.
     *
     * The service's own `stopwatchNow()` KDoc enumerates this failure for six in-service timers
     * and fixed them; these two stamps were the last ones left on the wall clock.
     *
     * Starts far in the past so the first event after a process start always writes: a boot leaves
     * `elapsedRealtime` at a few seconds, and a zero here would have swallowed the first minute.
     */
    @Volatile private var lastWrittenAtRt = Long.MIN_VALUE / 2

    /** A stamp later than "now" is impossible — the wall clock moved back under it. Anchoring it to
     *  now restarts the staleness window instead of leaving one that can never elapse. Pure so the
     *  rule is testable; the caller does the repair. */
    internal fun anchoredStamp(stored: Long, now: Long): Long = if (stored > now) now else stored

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * "The watcher is alive and receiving events." Called from the service's event path, so it
     * throttles itself: at most one write a minute, and the in-memory guard means the common
     * case is a comparison, not a disk touch.
     */
    fun recordEvent(
        context: Context,
        now: Long = System.currentTimeMillis(),
        nowRt: Long = SystemClock.elapsedRealtime(),
    ) {
        if (nowRt - lastWrittenAtRt < WRITE_THROTTLE_MS) return
        lastWrittenAtRt = nowRt
        prefs(context).edit().putLong(KEY_LAST_EVENT, now).apply()
    }

    /**
     * When the watcher last showed a sign of life (0 = never since install).
     *
     * Self-heals a stamp from the future: see [anchoredStamp]. Without that, a backward clock
     * change on a watcher that has genuinely gone deaf leaves a stamp no event will ever correct,
     * and the staleness check answers OK for as long as the clock is behind.
     */
    fun lastEventAt(context: Context, now: Long = System.currentTimeMillis()): Long {
        val stored = prefs(context).getLong(KEY_LAST_EVENT, 0L)
        val sane = anchoredStamp(stored, now)
        if (sane != stored) prefs(context).edit().putLong(KEY_LAST_EVENT, sane).apply()
        return sane
    }

    /**
     * "The watcher is still bound" — stamped on a timer rather than from the event path, so a
     * phone nobody is touching still proves it.
     *
     * ⚠️ **A separate key from [recordEvent] on purpose.** The staleness check in `protectionState`
     * asks "no events for hours while the phone was in use?"; a heartbeat written to the *event*
     * key would answer that question "no" forever and silently retire the check. Two facts, two
     * keys: `KEY_LAST_EVENT` is "the watcher saw something", this is "the watcher exists".
     *
     * Not itself a trigger for declaring blocking dead — a process the OS has frozen stops ticking
     * while being perfectly healthy, and a false "blocking stopped" alert teaches the owner to
     * ignore the true one. It is evidence, for the diagnostics screen and bug reports.
     */
    fun recordAlive(context: Context, now: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_LAST_ALIVE, now).apply()
    }

    /** When the watcher last ticked (0 = never since install). */
    fun lastAliveAt(context: Context): Long = prefs(context).getLong(KEY_LAST_ALIVE, 0L)

    /**
     * Counts each time blocking was found switched-on-but-dead — once per occasion, not once per
     * check, so the caller must only call it on the *transition* into that state.
     *
     * The number is the point. "Sometimes when I switch spaces" is a report nobody can act on;
     * "it has happened 14 times" is a measurement, and it is the difference between knowing
     * whether a fix worked and hoping it did.
     */
    fun recordFoundDead(context: Context) {
        val p = prefs(context)
        p.edit().putInt(KEY_FOUND_DEAD, p.getInt(KEY_FOUND_DEAD, 0) + 1).apply()
    }

    fun foundDeadCount(context: Context): Int = prefs(context).getInt(KEY_FOUND_DEAD, 0)

    /**
     * Counts the heartbeat's re-post of `serviceInfo` — **the only self-repair this app has**, and
     * until now the only one that left no trace.
     *
     * After [BlockerAccessibilityService.REVIVE_AFTER_SILENCE_MS] of no events the heartbeat
     * re-registers the event mask with the system, which is the one thing an app can do for a
     * service that is still bound but has stopped being delivered to. Whether it fires, and how
     * often, is the *inside* view of `OutageLog.aliveButDeaf`: a phone where this climbs is a
     * phone whose watcher keeps going deaf while running. A phone where it stays at zero while
     * outages accumulate is a phone whose watcher is being killed outright — different cause,
     * different fix, and it was not measurable from either end before.
     *
     * @param worked whether the re-post itself threw. A nudge that throws is evidence too: it
     *   means the binding is already gone, not merely quiet.
     */
    fun recordRevive(context: Context, worked: Boolean) {
        val p = prefs(context)
        p.edit()
            .putInt(KEY_REVIVES, p.getInt(KEY_REVIVES, 0) + 1)
            .apply { if (!worked) putInt(KEY_REVIVE_FAILS, p.getInt(KEY_REVIVE_FAILS, 0) + 1) }
            .apply()
    }

    private const val KEY_REVIVE_HELPED = "health_revive_helped"
    private const val KEY_REVIVE_FUTILE = "health_revive_futile"

    private const val KEY_REBOUND_WARM = "health_rebound_warm"
    private const val KEY_REBOUND_COLD = "health_rebound_cold"

    /**
     * A process this old at the moment of a rebind was already running before it. Ten seconds is
     * well past the point where the bind could plausibly have started us — cold binds report an
     * age in the low hundreds of milliseconds — and well short of anything that would make a
     * genuinely idle process look busy.
     */
    const val REBOUND_WARM_AFTER_MS = 10_000L

    /** Pure so it can be tested without a device: the classification is the whole instrument. */
    fun isWarmRebound(processAgeMs: Long): Boolean = processAgeMs >= REBOUND_WARM_AFTER_MS

    /**
     * **The one question standing between us and the recovery problem.**
     *
     * On 5 Sep 2026 the first honestly-timed stoppages came back at twenty and sixteen minutes,
     * both recovered with nobody looking. Nothing in this app can reconnect its own accessibility
     * service — Android decides that — so all it does on detecting a stoppage is put up a
     * notification. Something else brings blocking back a quarter of an hour later, and we do not
     * know what.
     *
     * This is the cheapest thing that could tell us. **Warm** means our process was already awake
     * when Android reconnected us: something woke it and the reconnection followed, which is a
     * lever — the app could pull it deliberately instead of waiting. **Cold** means the
     * reconnection is what created the process, and Android acted alone with nothing to trigger.
     *
     * Recorded only on a rebind that actually ended an outage, so an ordinary boot or update does
     * not dilute it.
     */
    fun recordReboundWake(context: Context, processAgeMs: Long) {
        synchronized(this) {
            val key = if (isWarmRebound(processAgeMs)) KEY_REBOUND_WARM else KEY_REBOUND_COLD
            val p = prefs(context)
            p.edit().putInt(key, p.getInt(key, 0) + 1).apply()
        }
    }

    fun reboundWarmCount(context: Context): Int = prefs(context).getInt(KEY_REBOUND_WARM, 0)

    fun reboundColdCount(context: Context): Int = prefs(context).getInt(KEY_REBOUND_COLD, 0)

    /**
     * **Did the nudge actually bring events back?** — asked, rather than inferred from the
     * re-post not throwing.
     *
     * [recordRevive]'s `worked` means only that `serviceInfo = serviceInfo` did not throw, and on
     * 2 Sep 2026 that read **67 of 67 successful** on a phone whose blocking had been stopping for
     * hours at a time. A remedy reporting perfect success while the fault it treats continues is
     * worse than no remedy: it is the reason the self-repair was never suspected.
     *
     * This is the honest question. An accessibility service that is deaf receives no events, so
     * the test is whether one arrived AFTER the nudge — nothing else is evidence.
     */
    fun recordReviveOutcome(context: Context, helped: Boolean) {
        synchronized(this) {
            val p = prefs(context)
            val key = if (helped) KEY_REVIVE_HELPED else KEY_REVIVE_FUTILE
            p.edit().putInt(key, p.getInt(key, 0) + 1).apply()
        }
    }

    fun reviveHelpedCount(context: Context): Int = prefs(context).getInt(KEY_REVIVE_HELPED, 0)

    fun reviveFutileCount(context: Context): Int = prefs(context).getInt(KEY_REVIVE_FUTILE, 0)

    fun reviveCount(context: Context): Int = prefs(context).getInt(KEY_REVIVES, 0)

    fun reviveFailCount(context: Context): Int = prefs(context).getInt(KEY_REVIVE_FAILS, 0)

    /**
     * **The watcher asking the screen a question, instead of waiting to be told.**
     *
     * Every other liveness signal in this app is a *push*: an event arrived, or it did not. That
     * is why the deaf-but-bound case took so long to detect — the only evidence was an absence,
     * and an absence needs hours before it means anything (`STALE_AFTER_MS`). A probe is the pull
     * side: on a lit, unlocked screen the service asks for the window tree, and a service that
     * cannot read one is a service the framework has stopped talking to. That is an answer in
     * seconds rather than hours.
     *
     * It costs nothing on a healthy phone, because it runs only inside the heartbeat branch that
     * has *already* noticed several minutes of silence — the same branch that fires the nudge. No
     * silence, no binder call. (The owner's constraint is "keep the battery as it is", and this
     * satisfies it by construction rather than by argument.)
     *
     * It also splits `OutageLog.aliveButDeaf` in two, which nothing on the phone could do before:
     * a **failing** probe means the connection to the system is gone while our timer still runs;
     * a **passing** probe during silence means the connection is fine and delivery has stopped.
     * Different causes, different fixes.
     *
     * @param ok the probe passed **and** the nudge did not throw. "Could not tell" — screen off,
     *   keyguard up, no PowerManager — must pass; see the caller.
     */
    fun recordProbe(context: Context, ok: Boolean) {
        // Read-then-write on the streak that decides when an outage is declared. Two probes
        // landing together each read the same value and each wrote the same +1, so the streak
        // advanced by one instead of two and the declaration came late. Same shape as invariants
        // 36 and 37; here it costs detection time rather than a duplicate.
        synchronized(this) {
            val p = prefs(context)
            val current = p.getInt(KEY_PROBE_FAILS, 0)
            val next = nextProbeStreak(current, ok)
            if (next != current) p.edit().putInt(KEY_PROBE_FAILS, next).apply()
        }
    }

    /**
     * The streak arithmetic, pure so it can be stated rather than trusted.
     *
     * **Consecutive** is the whole idea: one passing probe wipes the count, because it is direct
     * evidence the connection is alive right now and no number of earlier failures survives that.
     * Failures only mean something in a row — a single one is a moment, five is a condition.
     */
    internal fun nextProbeStreak(current: Int, ok: Boolean): Int = if (ok) 0 else current + 1

    /**
     * Consecutive failed probes. Read by `protectionState`, which calls it dead past
     * `PROBE_FAIL_LIMIT`.
     *
     * ⚠️ **Only a real event may clear this, never a successful nudge.** The nudge fires on the
     * same three-minute schedule as the probe that watches it, so letting `worked = true` reset
     * the streak would mean the streak could never reach two — a threshold measured against a
     * clock the act of measuring resets, which is invariant 30 exactly and has already cost this
     * project one release. `serviceInfo = serviceInfo` on a dead connection may also return
     * quietly rather than throw, so a "successful" nudge is close to no evidence at all; only a
     * failing one is worth anything, and it is folded in here as a failing probe.
     */
    fun probeFailStreak(context: Context): Int = prefs(context).getInt(KEY_PROBE_FAILS, 0)

    /** Forget the streak. Called on a fresh bind (a new question) and when silence breaks (the
     *  window this measures has ended — invariant 33). */
    fun clearProbeStreak(context: Context) {
        synchronized(this) {
            val p = prefs(context)
            if (p.getInt(KEY_PROBE_FAILS, 0) != 0) p.edit().putInt(KEY_PROBE_FAILS, 0).apply()
        }
    }

    /**
     * **`onDestroy` ran — which is itself the finding.**
     *
     * Android calls it when the binding is taken down in an orderly way: the user switching the
     * service off, the system unbinding it, our own process shutting down cleanly. It is **not**
     * called when an OEM battery manager force-stops the app, and it is not called when the
     * process is killed outright.
     *
     * So the presence or absence of a recent stamp at the start of an outage separates two things
     * that look identical from the outside and have nothing else in common:
     *
     * - a stamp moments before → the binding was **ended**, by something that meant to end it;
     * - no stamp at all → the process **vanished** without being told anything.
     *
     * Roughly as sharp a discriminator as `OutageLog.aliveButDeaf`, for one line at the one place
     * that already knew. `onDestroy` used to set `connected = false` and record nothing.
     */
    fun recordUnbind(context: Context, now: Long = System.currentTimeMillis()) {
        val p = prefs(context)
        p.edit()
            .putLong(KEY_UNBOUND_AT, now)
            .putInt(KEY_UNBINDS, p.getInt(KEY_UNBINDS, 0) + 1)
            .apply()
    }

    /** When the binding was last taken down in an orderly way (0 = never since install). */
    fun lastUnboundAt(context: Context): Long = prefs(context).getLong(KEY_UNBOUND_AT, 0L)

    fun unbindCount(context: Context): Int = prefs(context).getInt(KEY_UNBINDS, 0)

    /**
     * Counts `onInterrupt` — Android telling the service to stop what it is doing.
     *
     * The callback has always been an empty body, so the one moment the framework explicitly says
     * something is happening to this service went unrecorded. It is not by itself a failure (it
     * fires for ordinary reasons too), but a spike of them either side of an outage would be the
     * first evidence anyone has had about what precedes one.
     */
    fun recordInterrupt(context: Context) {
        val p = prefs(context)
        p.edit().putInt(KEY_INTERRUPTS, p.getInt(KEY_INTERRUPTS, 0) + 1).apply()
    }

    fun interruptCount(context: Context): Int = prefs(context).getInt(KEY_INTERRUPTS, 0)

    /**
     * An error the service swallowed instead of dying on. Kept (with a count) so a recurring
     * bug is visible rather than invisible — swallowing errors silently would trade a loud
     * failure for a quiet one.
     *
     * "Visible" needs somewhere to actually look, and for a while there was none: nothing read
     * these back, so the store was written and never surfaced — the quiet failure this comment
     * warns about. The Profile screen now shows a row whenever the count is above zero, which is
     * what makes a recurring problem reportable ("it says 12 errors") instead of invisible.
     */
    fun recordError(context: Context, where: String, t: Throwable) {
        val p = prefs(context)
        p.edit()
            .putLong(KEY_LAST_ERROR_AT, System.currentTimeMillis())
            .putString(KEY_LAST_ERROR, "$where: ${t.javaClass.simpleName}: ${t.message}")
            // The tag on its own, so a bug report can say *where* the last error was without the
            // string above it. That one includes `t.message`, which is where a failing value gets
            // quoted back — and in this app that value is a blocked word. The full line is for the
            // Profile screen, on the device; only this half may ever leave it. See BugReport.
            .putString(KEY_LAST_ERROR_WHERE, where)
            .putInt(KEY_ERROR_COUNT, p.getInt(KEY_ERROR_COUNT, 0) + 1)
            .apply()
    }

    /** The `where` tag of the most recent swallowed error — a literal from our own code. */
    fun lastErrorWhere(context: Context): String? =
        prefs(context).getString(KEY_LAST_ERROR_WHERE, null)

    fun errorCount(context: Context): Int = prefs(context).getInt(KEY_ERROR_COUNT, 0)

    /** Forgets the recorded errors, so the Profile row goes quiet until something new breaks. */
    fun clearErrors(context: Context) = prefs(context).edit()
        .remove(KEY_LAST_ERROR_AT).remove(KEY_LAST_ERROR).remove(KEY_ERROR_COUNT)
        .apply()

    fun lastError(context: Context): String? = prefs(context).getString(KEY_LAST_ERROR, null)

    fun lastErrorAt(context: Context): Long = prefs(context).getLong(KEY_LAST_ERROR_AT, 0L)
}
