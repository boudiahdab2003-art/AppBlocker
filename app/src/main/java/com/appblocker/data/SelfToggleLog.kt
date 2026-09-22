package com.appblocker.data

import android.content.Context
import android.os.SystemClock

/**
 * **AppBlocker switching its own Accessibility entry off and on to revive a killed watcher — and
 * whether it worked** (invariant 82). The action lives in [com.appblocker.service.SelfToggle]; this
 * is the bookkeeping, kept apart so every rule is testable without a phone.
 *
 * Why this is the repair: on 22 Sep 2026, on the owner's own phone (HyperOS 3, Android 16), a watcher
 * crashed from the computer sat under "Crashed services" for 50 seconds with no restart attempt —
 * HyperOS never restarts a killed accessibility service — and came back within a second of the
 * enabled-services setting being written without it and then with it again, even 0.3 s apart.
 * That is exactly what his own switch-off-and-on does by hand, and it is silent: no screen, no
 * install. It needs `WRITE_SECURE_SETTINGS`, which only a computer can grant (`adb shell pm grant`).
 *
 * The two repairs before it are gone, at his request: the reinstall (invariant 81 — "it annoys me
 * and doesn't work") and the reopen (invariant 74 — "it interrupts my call"). Neither ever brought
 * blocking back on his phone: 0 of 2 and 0 of 14.
 *
 * Like them, it **judges itself**:
 * - [Verdict.HELPED] only when Android binds the watcher again within [JUDGE_WINDOW_MS];
 * - [Verdict.NO_REBIND] when the switch was written and nothing followed; [Verdict.FAILED] when the
 *   write itself was refused (the permission revoked, or an OEM refusing the key);
 * - after [MAX_FUTILE_STREAK] futile tries in a row it stops for [GIVE_UP_FOR_MS] and leaves it to
 *   the alert. Silent is not free: an entry toggled for nothing still costs a rebind of every
 *   enabled service on the phone.
 *
 * ⚠️ **The switch is off for a moment, and that moment must never outlive the process.** The off
 * write is preceded by [markAttempt] with `commit()`; [markerState] is how any later check finds a
 * toggle that died between its two writes and finishes it, so the one failure this repair could
 * add — the switch left OFF by our own hand — is repaired by the next thing that runs.
 *
 * Times are monotonic with the boot count beside them (invariant 9): a restart between an attempt
 * and its verdict is [Verdict.DROPPED], evidence neither way.
 */
object SelfToggleLog {

    private const val PREFS = "self_toggle"

    private const val KEY_PENDING_RT = "pending_rt"
    private const val KEY_PENDING_BOOT = "pending_boot"
    private const val KEY_LAST_RT = "last_attempt_rt"
    private const val KEY_LAST_BOOT = "last_attempt_boot"
    private const val KEY_ATTEMPTS = "attempts"
    private const val KEY_HELPED = "helped"
    private const val KEY_NO_REBIND = "no_rebind"
    private const val KEY_FAILED = "failed"
    private const val KEY_FUTILE_STREAK = "futile_streak"
    private const val KEY_IN_FLIGHT_RT = "in_flight_rt"
    private const val KEY_IN_FLIGHT_BOOT = "in_flight_boot"

    /** At most one attempt a minute, however often the stalled checks run. */
    const val COOLDOWN_MS = 60_000L

    /**
     * A rebind this soon after the attempt is credited to it. On his phone it took about a second;
     * the rest is room for a phone under the memory pressure that killed the watcher in the first
     * place, where starting a process is slow.
     */
    const val JUDGE_WINDOW_MS = 30_000L

    /** An attempt nobody has judged after this long is judged by what is known about it. */
    const val STALE_PENDING_MS = 60_000L

    /** Futile tries in a row before it stops trying for [GIVE_UP_FOR_MS]. */
    const val MAX_FUTILE_STREAK = 3

    /** An hour, not a day: the repair is invisible, so trying again later costs him nothing. */
    const val GIVE_UP_FOR_MS = 3_600_000L

    /**
     * How long a toggle may be between its two writes before a check treats it as interrupted. The
     * two writes are under a second apart; this is the margin for a busy phone.
     */
    const val IN_FLIGHT_MS = 15_000L

    /**
     * How old an interrupted toggle may be and still be finished by a later check. Past this the OFF
     * is not ours to undo: something else has had all that time to turn it off, him included.
     */
    const val INTERRUPTED_FINISH_WINDOW_MS = 30 * 60_000L

    /** Why an attempt was not made. */
    enum class Skip {
        NOT_UNBOUND, SWITCHED_OFF, NO_PERMISSION, ATTEMPT_PENDING, GAVE_UP, COOLDOWN,
    }

    /** What an attempt came to. */
    enum class Verdict { HELPED, NO_REBIND, FAILED, DROPPED }

    /**
     * Whether to toggle now — null means yes. Pure, so every refusal is tested.
     *
     * ⚠️ **Only the unbound case.** A watcher that is bound but deaf has its own repair (the
     * heartbeat's revive), and toggling a live one would cut off a watcher that may be working.
     * ⚠️ **Never while the switch reads off.** Off is his choice until shown otherwise; this repair
     * restores an entry that is on and dead, and must never be what turns blocking on against him.
     *
     * @param sinceLastAttemptMs -1 when there has never been an attempt, or not since this boot.
     */
    internal fun decide(
        watcherUnbound: Boolean,
        switchedOn: Boolean,
        permitted: Boolean,
        attemptPending: Boolean,
        sinceLastAttemptMs: Long,
        futileStreak: Int,
    ): Skip? = when {
        !watcherUnbound -> Skip.NOT_UNBOUND
        !switchedOn -> Skip.SWITCHED_OFF
        !permitted -> Skip.NO_PERMISSION
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
        nowRt: Long,
        boot: Int,
        rebound: Boolean,
    ): Verdict? = when {
        pendingRt <= 0L -> null
        pendingBoot != boot -> Verdict.DROPPED
        rebound && nowRt - pendingRt in 0..JUDGE_WINDOW_MS -> Verdict.HELPED
        !rebound && nowRt - pendingRt < STALE_PENDING_MS -> null
        else -> Verdict.NO_REBIND
    }

    /** What the in-flight marker says, for [markerState]. */
    enum class Marker {
        /** No toggle has left a marker. */
        NONE,

        /** A toggle is between its two writes now: what the switch reads is ours. Judge nothing. */
        IN_FLIGHT,

        /** A toggle died between its writes and left the entry off: put it back on. */
        FINISH_ON,

        /** The marker has nothing left to say — the entry is on, or it is too old or from before a
         *  restart to be ours to undo. Cleared, so it can never act on a switch he turned off. */
        STALE,
    }

    /**
     * What the in-flight marker means now. Pure.
     *
     * ⚠️ **Only [Marker.FINISH_ON] may ever write the switch on**, and only for our own recent marker
     * with the entry reading off. Anything else clears the marker the moment it is seen: a marker
     * left lying around would, the next time he switched blocking off himself, read as our unfinished
     * toggle and switch it straight back on against him.
     */
    internal fun markerState(
        stampRt: Long,
        stampBoot: Int,
        nowRt: Long,
        boot: Int,
        switchedOn: Boolean,
    ): Marker {
        if (stampRt <= 0L) return Marker.NONE
        val age = nowRt - stampRt
        return when {
            stampBoot != boot || age < 0L -> Marker.STALE
            age < IN_FLIGHT_MS -> Marker.IN_FLIGHT
            switchedOn -> Marker.STALE
            age < INTERRUPTED_FINISH_WINDOW_MS -> Marker.FINISH_ON
            else -> Marker.STALE
        }
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

    /**
     * An attempt is being made now, and the switch is about to read off. Both facts are written in
     * one `commit()` before the first write, so a process killed between the two writes still leaves
     * the marker that lets the next check finish the job.
     */
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
                    .putLong(KEY_IN_FLIGHT_RT, stamp)
                    .putInt(KEY_IN_FLIGHT_BOOT, boot)
                    .commit()
            }
        }.getOrDefault(false)

    /** Both writes are done (or the second was given up): the switch is ours no longer. */
    fun clearInFlight(context: Context) {
        runCatching {
            synchronized(this) {
                prefs(context).edit().remove(KEY_IN_FLIGHT_RT).remove(KEY_IN_FLIGHT_BOOT).commit()
            }
        }
    }

    /** [markerState] for this phone now. Unreadable reads as [Marker.NONE]: nothing is ever written
     *  on the strength of a marker that could not be read. */
    fun markerState(
        context: Context,
        switchedOn: Boolean,
        nowRt: Long = SystemClock.elapsedRealtime(),
    ): Marker = runCatching {
        val p = prefs(context)
        markerState(
            p.getLong(KEY_IN_FLIGHT_RT, 0L), p.getInt(KEY_IN_FLIGHT_BOOT, -2), nowRt,
            DeviceBoot.count(context), switchedOn,
        )
    }.getOrDefault(Marker.NONE)

    /**
     * The write was refused. Judged at once — nothing is coming to judge it later — and it counts
     * toward the futile streak, so a revoked permission stops the tries instead of repeating them.
     */
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
                    nowRt = nowRt,
                    boot = DeviceBoot.count(context),
                    rebound = rebound,
                ) ?: return@synchronized null
                val e = p.edit().remove(KEY_PENDING_RT).remove(KEY_PENDING_BOOT)
                when (verdict) {
                    Verdict.HELPED -> e.putInt(KEY_HELPED, p.getInt(KEY_HELPED, 0) + 1)
                        .putInt(KEY_FUTILE_STREAK, 0)
                    Verdict.NO_REBIND -> e.putInt(KEY_NO_REBIND, p.getInt(KEY_NO_REBIND, 0) + 1)
                        .putInt(KEY_FUTILE_STREAK, p.getInt(KEY_FUTILE_STREAK, 0) + 1)
                    Verdict.FAILED, Verdict.DROPPED -> Unit
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
