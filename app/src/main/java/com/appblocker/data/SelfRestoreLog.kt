package com.appblocker.data

import android.content.Context
import android.os.SystemClock

/**
 * **AppBlocker reopening itself when the watcher is found unbound during use — and whether it worked.**
 *
 * On 14 Sep 2026 (report #127) the watcher's process died at 10:45, the "blocking has stopped"
 * alert went up at about 11:02, the owner saw it — and blocking stayed down through hours of use
 * until he opened AppBlocker at 16:58, when it came back within a second. A background check had
 * run in a process of ours twenty minutes earlier and had not brought it back; opening the app
 * did. Asked directly the same day, he chose that the app should do that itself rather than wait
 * for him to act on an alert.
 *
 * One occasion is a lead, not a law, so this is a repair that **judges itself** (invariant 74):
 * - [Verdict.HELPED] only when Android binds the watcher again within [JUDGE_WINDOW_MS];
 * - [Verdict.NO_REBIND] when the screen opened and nothing followed; [Verdict.NOT_LAUNCHED] when
 *   the phone never opened it at all (background-launch rules);
 * - after [MAX_FUTILE_STREAK] futile tries in a row it stops for [GIVE_UP_FOR_MS] and leaves it to
 *   the alert. A repair that keeps interrupting him without helping is worse than none, and
 *   `revives` — 67 of 67 "worked" while the fault carried on — is what an unjudged repair becomes.
 *
 * Times are monotonic with the boot count beside them (invariant 9): a restart between an attempt
 * and its verdict is [Verdict.DROPPED], evidence neither way.
 */
object SelfRestoreLog {

    private const val PREFS = "self_restore"

    private const val KEY_PENDING_RT = "pending_rt"
    private const val KEY_PENDING_BOOT = "pending_boot"
    private const val KEY_LAUNCHED = "launched"
    private const val KEY_LAST_RT = "last_attempt_rt"
    private const val KEY_LAST_BOOT = "last_attempt_boot"
    private const val KEY_ATTEMPTS = "attempts"
    private const val KEY_HELPED = "helped"
    private const val KEY_NO_REBIND = "no_rebind"
    private const val KEY_NOT_LAUNCHED = "not_launched"
    private const val KEY_FUTILE_STREAK = "futile_streak"

    /** At most one attempt per ten minutes, however often the stalled checks run. */
    const val COOLDOWN_MS = 10 * 60_000L

    /** A rebind this soon after the attempt is credited to it. On 14 Sep it took about a second. */
    const val JUDGE_WINDOW_MS = 20_000L

    /** An attempt nobody has judged after this long is judged by what is known about it. */
    const val STALE_PENDING_MS = 60_000L

    /** Futile tries in a row before it stops trying for [GIVE_UP_FOR_MS]. */
    const val MAX_FUTILE_STREAK = 3

    const val GIVE_UP_FOR_MS = 24 * 3_600_000L

    /** Why an attempt was not made. */
    enum class Skip {
        NOT_UNBOUND, APP_ALREADY_OPEN, PHONE_NOT_IN_USE, NO_OVERLAY_PERMISSION,
        ATTEMPT_PENDING, GAVE_UP, COOLDOWN,
    }

    /** What an attempt came to. */
    enum class Verdict { HELPED, NO_REBIND, NOT_LAUNCHED, DROPPED }

    /**
     * Whether to reopen now — null means yes. Pure, so every refusal is tested.
     *
     * ⚠️ **Only the unbound case.** A watcher that is bound but deaf is not helped by a screen
     * opening, and has its own repair (the heartbeat's revive). And **only while the phone is in
     * use** — lit and unlocked — because opening something on a dark or locked screen interrupts
     * nobody and proves nothing.
     *
     * @param sinceLastAttemptMs -1 when there has never been an attempt, or not since this boot.
     */
    internal fun decide(
        watcherUnbound: Boolean,
        appAlreadyOpen: Boolean,
        phoneInUse: Boolean,
        overlayAllowed: Boolean,
        attemptPending: Boolean,
        sinceLastAttemptMs: Long,
        futileStreak: Int,
    ): Skip? = when {
        !watcherUnbound -> Skip.NOT_UNBOUND
        appAlreadyOpen -> Skip.APP_ALREADY_OPEN
        !phoneInUse -> Skip.PHONE_NOT_IN_USE
        !overlayAllowed -> Skip.NO_OVERLAY_PERMISSION
        attemptPending -> Skip.ATTEMPT_PENDING
        futileStreak >= MAX_FUTILE_STREAK && sinceLastAttemptMs in 0 until GIVE_UP_FOR_MS -> Skip.GAVE_UP
        sinceLastAttemptMs in 0 until COOLDOWN_MS -> Skip.COOLDOWN
        else -> null
    }

    /**
     * What a pending attempt comes to, or null while it is still too early to say. Pure.
     *
     * @param rebound true when this is asked because the watcher has just been bound again; false
     *   when it is asked because nobody has judged the attempt yet.
     */
    internal fun judge(
        pendingRt: Long,
        pendingBoot: Int,
        launched: Boolean,
        nowRt: Long,
        boot: Int,
        rebound: Boolean,
    ): Verdict? = when {
        pendingRt <= 0L -> null
        pendingBoot != boot -> Verdict.DROPPED
        rebound && nowRt - pendingRt in 0..JUDGE_WINDOW_MS -> Verdict.HELPED
        !rebound && nowRt - pendingRt < STALE_PENDING_MS -> null
        launched -> Verdict.NO_REBIND
        else -> Verdict.NOT_LAUNCHED
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isPending(context: Context): Boolean =
        runCatching { prefs(context).getLong(KEY_PENDING_RT, 0L) > 0L }.getOrDefault(false)

    fun futileStreak(context: Context): Int =
        runCatching { prefs(context).getInt(KEY_FUTILE_STREAK, 0) }.getOrDefault(0)

    /** Millis since the last attempt, or -1 when there has been none since this boot. */
    fun sinceLastAttemptMs(context: Context, nowRt: Long = SystemClock.elapsedRealtime()): Long {
        val p = prefs(context)
        val rt = p.getLong(KEY_LAST_RT, 0L)
        if (rt <= 0L || p.getInt(KEY_LAST_BOOT, -2) != DeviceBoot.count(context)) return -1L
        return (nowRt - rt).coerceAtLeast(0L)
    }

    /** An attempt is being made now. Written before the screen is asked for, so a launch that
     *  never happens still leaves something to judge. */
    fun markAttempt(context: Context, nowRt: Long = SystemClock.elapsedRealtime()) {
        synchronized(this) {
            val p = prefs(context)
            val boot = DeviceBoot.count(context)
            val stamp = nowRt.coerceAtLeast(1L)
            p.edit()
                .putLong(KEY_PENDING_RT, stamp)
                .putInt(KEY_PENDING_BOOT, boot)
                .putBoolean(KEY_LAUNCHED, false)
                .putLong(KEY_LAST_RT, stamp)
                .putInt(KEY_LAST_BOOT, boot)
                .putInt(KEY_ATTEMPTS, p.getInt(KEY_ATTEMPTS, 0) + 1)
                .apply()
        }
    }

    /** The screen really opened. Separates "the phone refused" from "it opened and did nothing". */
    fun noteLaunched(context: Context) {
        synchronized(this) {
            val p = prefs(context)
            if (p.getLong(KEY_PENDING_RT, 0L) > 0L) p.edit().putBoolean(KEY_LAUNCHED, true).apply()
        }
    }

    /**
     * The watcher has just been bound again. True when an attempt was pending and this rebind is
     * credited to it — which also settles the attempt, so one rebind is never counted twice.
     */
    fun claimRebind(context: Context, nowRt: Long = SystemClock.elapsedRealtime()): Boolean =
        resolve(context, nowRt, rebound = true) == Verdict.HELPED

    /** Judges an attempt nobody has judged yet. Costs one prefs read when nothing is pending. */
    fun resolveStale(context: Context, nowRt: Long = SystemClock.elapsedRealtime()) {
        resolve(context, nowRt, rebound = false)
    }

    // Invariant 37: a check-then-act on the pending key, reached from the watcher's rebind and from
    // every stalled check — held under one lock so an attempt is settled exactly once.
    private fun resolve(context: Context, nowRt: Long, rebound: Boolean): Verdict? =
        runCatching {
            synchronized(this) {
                val p = prefs(context)
                val verdict = judge(
                    pendingRt = p.getLong(KEY_PENDING_RT, 0L),
                    pendingBoot = p.getInt(KEY_PENDING_BOOT, -2),
                    launched = p.getBoolean(KEY_LAUNCHED, false),
                    nowRt = nowRt,
                    boot = DeviceBoot.count(context),
                    rebound = rebound,
                ) ?: return@synchronized null
                val e = p.edit()
                    .remove(KEY_PENDING_RT)
                    .remove(KEY_PENDING_BOOT)
                    .remove(KEY_LAUNCHED)
                val streak = p.getInt(KEY_FUTILE_STREAK, 0)
                when (verdict) {
                    Verdict.HELPED -> e.putInt(KEY_HELPED, p.getInt(KEY_HELPED, 0) + 1)
                        .putInt(KEY_FUTILE_STREAK, 0)
                    Verdict.NO_REBIND -> e.putInt(KEY_NO_REBIND, p.getInt(KEY_NO_REBIND, 0) + 1)
                        .putInt(KEY_FUTILE_STREAK, streak + 1)
                    Verdict.NOT_LAUNCHED -> e.putInt(KEY_NOT_LAUNCHED, p.getInt(KEY_NOT_LAUNCHED, 0) + 1)
                        .putInt(KEY_FUTILE_STREAK, streak + 1)
                    Verdict.DROPPED -> Unit
                }
                e.apply()
                verdict
            }
        }.getOrNull()

    /** Every attempt and what it came to. Attempts dropped across a restart are in [attempts] only. */
    data class Counts(
        val attempts: Int = 0,
        val helped: Int = 0,
        val noRebind: Int = 0,
        val notLaunched: Int = 0,
        val futileStreak: Int = 0,
    )

    fun counts(context: Context): Counts = runCatching {
        val p = prefs(context)
        Counts(
            attempts = p.getInt(KEY_ATTEMPTS, 0),
            helped = p.getInt(KEY_HELPED, 0),
            noRebind = p.getInt(KEY_NO_REBIND, 0),
            notLaunched = p.getInt(KEY_NOT_LAUNCHED, 0),
            futileStreak = p.getInt(KEY_FUTILE_STREAK, 0),
        )
    }.getOrDefault(Counts())
}
