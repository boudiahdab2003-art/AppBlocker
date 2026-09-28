package com.appblocker.data

import android.content.Context
import android.os.SystemClock

/**
 * **AppBlocker switching its own Accessibility entry back ON when a check finds it off — and whether
 * that worked** (invariant 84). The write lives in [com.appblocker.service.SelfToggle.maybeSwitchOn];
 * this is the bookkeeping, kept apart so every rule is testable without a phone.
 *
 * **His choice, 28 Sep 2026.** On Saturday 26 Sep his main phone sent a report at 08:01 with the
 * switch OFF, 24 minutes after a restart — the third switch-off on record (10 Sep: fourteen hours
 * and 772 minutes of use; 16 Sep; 26 Sep) — and he could not say how it happened. Asked what
 * AppBlocker should do when it finds its own switch off, he chose "Turn back on, always": silently,
 * even when he switched it off himself. The pause inside the app is how blocking stops.
 *
 * ⚠️ **One switch-off is left alone: the one made with the off-switch guard down.** The guard's way
 * out ([OffSwitchGuard]) is a served two-hour wait, then a window in which the switch works; a
 * switch-off made in that window — or with the guard lowered for good, which only that window can
 * do — is the emergency exit the guard promises. Switching it back on would make that promise a lie.
 * Everything else — a restart, the phone, a hand that got past the guard — is switched back on.
 *
 * ⚠️ **Never on an install whose watcher has never run.** Turning Accessibility on for someone who
 * has not read the disclosure and switched it on by hand is the one thing this may never do. A PC
 * emulator that reported on 26 Sep (#202) held the permission and had never been switched on.
 *
 * Like the silent repair ([SelfToggleLog]) it judges itself, by the same rule on its own window: helped
 * only when the watcher is bound within [JUDGE_WINDOW_MS] of the write, and after [MAX_FUTILE_STREAK]
 * writes in a row that changed nothing it stops for [GIVE_UP_FOR_MS] and leaves it to the alert.
 * There is no dangerous half here: the only write is the ON one. Times are monotonic with the boot
 * count beside them (invariant 9).
 */
object SelfEnableLog {

    private const val PREFS = "self_enable"

    private const val KEY_PENDING_RT = "pending_rt"
    private const val KEY_PENDING_BOOT = "pending_boot"
    private const val KEY_LAST_RT = "last_attempt_rt"
    private const val KEY_LAST_BOOT = "last_attempt_boot"
    private const val KEY_ATTEMPTS = "attempts"
    private const val KEY_HELPED = "helped"
    private const val KEY_NO_REBIND = "no_rebind"
    private const val KEY_FAILED = "failed"
    private const val KEY_FUTILE_STREAK = "futile_streak"

    /** At most one write a minute, however often the checks that find the switch off run. */
    const val COOLDOWN_MS = 60_000L

    /**
     * ⚠️ **A rebind this soon after the write is credited to it — two minutes, not the repair's 30 s.**
     * On the Android 16 emulator on 28 Sep 2026, the write made by the first check after a restart
     * was followed by the watcher's connect 40–50 s later, on a phone still busy starting up: judged
     * on the repair's window it was filed `no_rebind`, and the period closed as `rebound` — our own
     * write read as the switch coming back without us. A switch that reads OFF has nothing else
     * waiting to bind it but a write to it, so a longer window credits nothing that is not ours.
     */
    const val JUDGE_WINDOW_MS = 120_000L

    /** An unjudged write is judged after this long — past [JUDGE_WINDOW_MS], or a late rebind would
     *  be judged futile a moment before it arrived. */
    const val STALE_PENDING_MS = 150_000L

    /** Writes in a row that changed nothing before it stops for [GIVE_UP_FOR_MS]. */
    const val MAX_FUTILE_STREAK = 3

    /** An hour: the write is invisible, so trying again later costs him nothing. */
    const val GIVE_UP_FOR_MS = 3_600_000L

    /** Why the switch was not written back on. */
    enum class Skip {
        SWITCHED_ON, NEVER_RAN, GUARD_DOWN, NO_PERMISSION, ATTEMPT_PENDING, GAVE_UP, COOLDOWN,
    }

    /**
     * Whether to write the switch back on now — null means yes. Pure, so every refusal is tested.
     *
     * @param everRan the watcher has run at least once on this install: he agreed to the disclosure
     *   and switched it on himself. False on a fresh install, and after the app's data is cleared.
     * @param guardArmedNow [OffSwitchGuard.armed] now. False inside the served window and with the
     *   guard lowered — both are his way out.
     * @param guardArmedWhenOff [SwitchOffLog.openGuardArmed]: false when the guard was down at the
     *   unbind that turned the switch off, so a switch-off made in the window stays off after the
     *   window closes. Null — no unbind belongs to the period — is not his way out.
     * @param sinceLastAttemptMs -1 when there has never been an attempt, or not since this boot.
     */
    internal fun decide(
        switchedOn: Boolean,
        everRan: Boolean,
        guardArmedNow: Boolean,
        guardArmedWhenOff: Boolean?,
        permitted: Boolean,
        attemptPending: Boolean,
        sinceLastAttemptMs: Long,
        futileStreak: Int,
    ): Skip? = when {
        switchedOn -> Skip.SWITCHED_ON
        !everRan -> Skip.NEVER_RAN
        !guardArmedNow || guardArmedWhenOff == false -> Skip.GUARD_DOWN
        !permitted -> Skip.NO_PERMISSION
        attemptPending -> Skip.ATTEMPT_PENDING
        futileStreak >= MAX_FUTILE_STREAK && sinceLastAttemptMs in 0 until GIVE_UP_FOR_MS -> Skip.GAVE_UP
        sinceLastAttemptMs in 0 until COOLDOWN_MS -> Skip.COOLDOWN
        else -> null
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isPending(context: Context): Boolean =
        runCatching { prefs(context).getLong(KEY_PENDING_RT, 0L) > 0L }.getOrDefault(false)

    /**
     * Millis since a write of ours that is still awaiting its rebind, or null when none is, in this
     * boot. What the watchdog's bind grace reads ([com.appblocker.service.SWITCH_ON_GRACE_MS]): the
     * watcher our own write is about to bring back is not a dead one. Unreadable is null — no grace.
     */
    fun sinceWriteMs(context: Context, nowRt: Long = SystemClock.elapsedRealtime()): Long? = runCatching {
        val p = prefs(context)
        val rt = p.getLong(KEY_PENDING_RT, 0L)
        if (rt <= 0L || p.getInt(KEY_PENDING_BOOT, -2) != DeviceBoot.count(context)) null
        else (nowRt - rt).takeIf { it >= 0L }
    }.getOrNull()

    fun futileStreak(context: Context): Int =
        runCatching { prefs(context).getInt(KEY_FUTILE_STREAK, 0) }.getOrDefault(0)

    /** Millis since the last attempt, or -1 when there has been none since this boot. */
    fun sinceLastAttemptMs(context: Context, nowRt: Long = SystemClock.elapsedRealtime()): Long {
        val p = prefs(context)
        val rt = p.getLong(KEY_LAST_RT, 0L)
        if (rt <= 0L || p.getInt(KEY_LAST_BOOT, -2) != DeviceBoot.count(context)) return -1L
        return (nowRt - rt).coerceAtLeast(0L)
    }

    /** A write is being made now. Committed first, so the rebind it causes always finds it. */
    fun markAttempt(context: Context, nowRt: Long = SystemClock.elapsedRealtime()): Boolean =
        runCatching {
            synchronized(this) {
                val p = prefs(context)
                val boot = DeviceBoot.count(context)
                val stamp = nowRt.coerceAtLeast(1L)
                p.edit()
                    .putLong(KEY_PENDING_RT, stamp)
                    .putInt(KEY_PENDING_BOOT, boot)
                    .putLong(KEY_LAST_RT, stamp)
                    .putInt(KEY_LAST_BOOT, boot)
                    .putInt(KEY_ATTEMPTS, p.getInt(KEY_ATTEMPTS, 0) + 1)
                    .commit()
            }
        }.getOrDefault(false)

    /** The write was refused. Judged at once, and it counts toward the futile streak. */
    fun noteFailed(context: Context) {
        runCatching {
            synchronized(this) {
                val p = prefs(context)
                if (p.getLong(KEY_PENDING_RT, 0L) <= 0L) return@synchronized
                p.edit()
                    .remove(KEY_PENDING_RT)
                    .remove(KEY_PENDING_BOOT)
                    .putInt(KEY_FAILED, p.getInt(KEY_FAILED, 0) + 1)
                    .putInt(KEY_FUTILE_STREAK, p.getInt(KEY_FUTILE_STREAK, 0) + 1)
                    .apply()
            }
        }
    }

    /**
     * The watcher has just been bound again. True when a write was pending and this rebind is credited
     * to it — which also settles the attempt, so one rebind is never counted twice.
     */
    fun claimRebind(context: Context, nowRt: Long = SystemClock.elapsedRealtime()): Boolean =
        resolve(context, nowRt, rebound = true) == SelfToggleLog.Verdict.HELPED

    /** Judges a write nobody has judged yet. Costs one prefs read when nothing is pending. */
    fun resolveStale(context: Context, nowRt: Long = SystemClock.elapsedRealtime()) {
        resolve(context, nowRt, rebound = false)
    }

    // Invariant 37: a check-then-act on the pending key, reached from the watcher's rebind and from
    // every check that finds the switch off — held under one lock so a write is settled exactly once.
    // The verdict is the silent repair's own pure rule, so the two can never be judged differently.
    private fun resolve(context: Context, nowRt: Long, rebound: Boolean): SelfToggleLog.Verdict? =
        runCatching {
            synchronized(this) {
                val p = prefs(context)
                val verdict = SelfToggleLog.judge(
                    pendingRt = p.getLong(KEY_PENDING_RT, 0L),
                    pendingBoot = p.getInt(KEY_PENDING_BOOT, -2),
                    nowRt = nowRt,
                    boot = DeviceBoot.count(context),
                    rebound = rebound,
                    windowMs = JUDGE_WINDOW_MS,
                    staleMs = STALE_PENDING_MS,
                ) ?: return@synchronized null
                val e = p.edit().remove(KEY_PENDING_RT).remove(KEY_PENDING_BOOT)
                when (verdict) {
                    SelfToggleLog.Verdict.HELPED -> e.putInt(KEY_HELPED, p.getInt(KEY_HELPED, 0) + 1)
                        .putInt(KEY_FUTILE_STREAK, 0)
                    SelfToggleLog.Verdict.NO_REBIND -> e.putInt(KEY_NO_REBIND, p.getInt(KEY_NO_REBIND, 0) + 1)
                        .putInt(KEY_FUTILE_STREAK, p.getInt(KEY_FUTILE_STREAK, 0) + 1)
                    SelfToggleLog.Verdict.FAILED, SelfToggleLog.Verdict.DROPPED -> Unit
                }
                e.apply()
                verdict
            }
        }.getOrNull()

    /** Every write and what it came to. Writes dropped across a restart are in [attempts] only. */
    data class Counts(
        val attempts: Int = 0,
        val helped: Int = 0,
        val noRebind: Int = 0,
        val failed: Int = 0,
        val futileStreak: Int = 0,
    )

    fun counts(context: Context): Counts = runCatching {
        val p = prefs(context)
        Counts(
            attempts = p.getInt(KEY_ATTEMPTS, 0),
            helped = p.getInt(KEY_HELPED, 0),
            noRebind = p.getInt(KEY_NO_REBIND, 0),
            failed = p.getInt(KEY_FAILED, 0),
            futileStreak = p.getInt(KEY_FUTILE_STREAK, 0),
        )
    }.getOrDefault(Counts())
}
