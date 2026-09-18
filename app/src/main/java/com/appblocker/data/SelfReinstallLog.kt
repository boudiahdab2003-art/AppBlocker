package com.appblocker.data

import android.content.Context
import android.os.SystemClock

/**
 * **AppBlocker reinstalling itself to bring a killed watcher back — and whether it worked**
 * (invariant 81).
 *
 * Why this exists. From 16 to 18 Sep 2026 the owner's phone killed the watcher six times while he was
 * using it (`signaled@fg-service`), and not once is it known to have come back by itself: on stock
 * Android the system restarts a killed accessibility service within seconds, on his HyperOS phone it
 * did not. Android's own source (`AccessibilityManagerService`, Android 16) leaves a killed service
 * marked "crashed" until one of four things happens — the system restarts it, he switches it off and
 * on, he switches spaces, or **the app is updated** (`onPackageUpdateFinished` clears the mark and binds
 * it again). The app can cause only the last. And it had already worked on his phone: on 15 Sep a
 * watcher killed at 11:21 came back at 12:28, the minute v1.165 was installed (#131, invariant 78).
 *
 * So when the watcher is found dead, the app reinstalls its own installed copy — same version, same
 * signature, nothing of his changes — through [SilentInstaller], without a tap where Android allows an
 * app to update itself. Where the phone insists on asking, the system's confirmation arrives as a
 * notification ([com.appblocker.service.InstallResultReceiver]): one tap instead of a Settings hunt.
 *
 * A repair that judges itself, like [SelfRestoreLog]:
 * - [Verdict.HELPED] only when the watcher is bound again within [JUDGE_WINDOW_MS] of the attempt (or,
 *   when the phone asked for a tap, within [STALE_PENDING_MS] — he may take a minute to tap);
 * - [Verdict.NEEDS_TAP] when the phone asked for a tap and nothing followed; [Verdict.FAILED] when the
 *   install was refused or could not start; [Verdict.NO_REBIND] when nothing followed at all;
 * - after [MAX_FUTILE_STREAK] futile tries in a row it stops for [GIVE_UP_FOR_MS].
 *
 * The install kills this process, so the attempt is written BEFORE it is made and judged by the next
 * process. Times are monotonic with the boot count beside them (invariant 9): a restart in between
 * is [Verdict.DROPPED], evidence neither way.
 */
object SelfReinstallLog {

    private const val PREFS = "self_reinstall"

    private const val KEY_PENDING_RT = "pending_rt"
    private const val KEY_PENDING_BOOT = "pending_boot"
    private const val KEY_OUTCOME = "outcome"
    private const val KEY_LAST_RT = "last_attempt_rt"
    private const val KEY_LAST_BOOT = "last_attempt_boot"
    private const val KEY_ATTEMPTS = "attempts"
    private const val KEY_HELPED = "helped"
    private const val KEY_NO_REBIND = "no_rebind"
    private const val KEY_ASKED_TAP = "asked_tap"
    private const val KEY_FAILED = "failed"
    private const val KEY_FUTILE_STREAK = "futile_streak"

    /** The system asked for a tap before installing. */
    const val OUTCOME_TAP = "tap"

    /** The system refused the install, or it could not be started. */
    const val OUTCOME_FAILED = "failed"

    /** At most one reinstall per quarter of an hour, however often the stalled checks run. */
    const val COOLDOWN_MS = 15 * 60_000L

    /**
     * A rebind this soon after the attempt is credited to it. Copying the app, installing it and the
     * rebind that follows took seconds on the test phones; a slow phone gets minutes.
     */
    const val JUDGE_WINDOW_MS = 3 * 60_000L

    /** An attempt nobody has judged after this long is judged by what is known about it. */
    const val STALE_PENDING_MS = 10 * 60_000L

    /** Futile tries in a row before it stops trying for [GIVE_UP_FOR_MS]. */
    const val MAX_FUTILE_STREAK = 3

    const val GIVE_UP_FOR_MS = 24 * 3_600_000L

    /** Why a reinstall was not attempted. */
    enum class Skip {
        NOT_POSSIBLE, NO_INSTALL_PERMISSION, NOT_UNBOUND, APP_OPEN, UPDATE_PAUSED, ATTEMPT_PENDING,
        GAVE_UP, COOLDOWN,
    }

    /** What an attempt came to. */
    enum class Verdict { HELPED, NO_REBIND, NEEDS_TAP, FAILED, DROPPED }

    /**
     * Whether to reinstall now — null means yes. Pure, so every refusal is tested.
     *
     * ⚠️ **Only the unbound case**: a watcher that is bound but deaf has its own repair, and an install
     * would kill a process that was still working. ⚠️ **Never while AppBlocker's own screen is up**:
     * the install replaces the process, which would close the app under his fingers. The alert and the
     * repair screen are his route then. And never while the update pause is on — that pause is waiting
     * for him on purpose.
     *
     * @param sinceLastAttemptMs -1 when there has never been an attempt, or not since this boot.
     */
    internal fun decide(
        possible: Boolean,
        canInstall: Boolean,
        watcherUnbound: Boolean,
        appOpen: Boolean,
        updatePaused: Boolean,
        attemptPending: Boolean,
        sinceLastAttemptMs: Long,
        futileStreak: Int,
    ): Skip? = when {
        !possible -> Skip.NOT_POSSIBLE
        !canInstall -> Skip.NO_INSTALL_PERMISSION
        !watcherUnbound -> Skip.NOT_UNBOUND
        appOpen -> Skip.APP_OPEN
        updatePaused -> Skip.UPDATE_PAUSED
        attemptPending -> Skip.ATTEMPT_PENDING
        futileStreak >= MAX_FUTILE_STREAK && sinceLastAttemptMs in 0 until GIVE_UP_FOR_MS -> Skip.GAVE_UP
        sinceLastAttemptMs in 0 until COOLDOWN_MS -> Skip.COOLDOWN
        else -> null
    }

    /**
     * What a pending attempt comes to, or null while it is still too early to say. Pure.
     *
     * @param rebound true when asked because the watcher is bound again right now; false when asked
     *   because nobody has judged the attempt yet.
     */
    internal fun judge(
        pendingRt: Long,
        pendingBoot: Int,
        outcome: String?,
        nowRt: Long,
        boot: Int,
        rebound: Boolean,
    ): Verdict? {
        if (pendingRt <= 0L) return null
        if (pendingBoot != boot) return Verdict.DROPPED
        val since = nowRt - pendingRt
        val window = if (outcome == OUTCOME_TAP) STALE_PENDING_MS else JUDGE_WINDOW_MS
        return when {
            rebound && since in 0..window -> Verdict.HELPED
            outcome == OUTCOME_FAILED -> Verdict.FAILED
            !rebound && since < STALE_PENDING_MS -> null
            outcome == OUTCOME_TAP -> Verdict.NEEDS_TAP
            else -> Verdict.NO_REBIND
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
     * An attempt is being made now. ⚠️ `commit()`, not `apply()`: the install that follows kills this
     * process, and a write still queued would die with it — leaving nothing to judge and no cooldown,
     * so the next check would reinstall again.
     */
    fun markAttempt(context: Context, nowRt: Long = SystemClock.elapsedRealtime()) {
        synchronized(this) {
            val p = prefs(context)
            val boot = DeviceBoot.count(context)
            val stamp = nowRt.coerceAtLeast(1L)
            p.edit()
                .putLong(KEY_PENDING_RT, stamp)
                .putInt(KEY_PENDING_BOOT, boot)
                .remove(KEY_OUTCOME)
                .putLong(KEY_LAST_RT, stamp)
                .putInt(KEY_LAST_BOOT, boot)
                .putInt(KEY_ATTEMPTS, p.getInt(KEY_ATTEMPTS, 0) + 1)
                .commit()
        }
    }

    /** The system wants a tap before installing — counted once per attempt. */
    fun noteAskedTap(context: Context) = noteOutcome(context, OUTCOME_TAP, KEY_ASKED_TAP)

    /** The install was refused, or could not even be started. */
    fun noteFailed(context: Context) = noteOutcome(context, OUTCOME_FAILED, KEY_FAILED)

    private fun noteOutcome(context: Context, outcome: String, counter: String) {
        runCatching {
            synchronized(this) {
                val p = prefs(context)
                if (p.getLong(KEY_PENDING_RT, 0L) <= 0L) return@synchronized
                if (p.getString(KEY_OUTCOME, null) == outcome) return@synchronized
                p.edit()
                    .putString(KEY_OUTCOME, outcome)
                    .putInt(counter, p.getInt(counter, 0) + 1)
                    .apply()
            }
        }
    }

    /**
     * The watcher is bound again right now. True when a reinstall was pending and this rebind is
     * credited to it — which also settles the attempt, so one rebind is never counted twice.
     */
    fun claimRebind(context: Context, nowRt: Long = SystemClock.elapsedRealtime()): Boolean =
        resolve(context, nowRt, rebound = true) == Verdict.HELPED

    /** Judges an attempt nobody has judged yet. Costs one prefs read when nothing is pending. */
    fun resolveStale(context: Context, nowRt: Long = SystemClock.elapsedRealtime()) {
        resolve(context, nowRt, rebound = false)
    }

    // Invariant 37: a check-then-act on the pending key, reached from the watcher's rebind, the
    // watchdog's OK path and every stalled check — one lock, so an attempt is settled exactly once.
    private fun resolve(context: Context, nowRt: Long, rebound: Boolean): Verdict? =
        runCatching {
            synchronized(this) {
                val p = prefs(context)
                val verdict = judge(
                    pendingRt = p.getLong(KEY_PENDING_RT, 0L),
                    pendingBoot = p.getInt(KEY_PENDING_BOOT, -2),
                    outcome = p.getString(KEY_OUTCOME, null),
                    nowRt = nowRt,
                    boot = DeviceBoot.count(context),
                    rebound = rebound,
                ) ?: return@synchronized null
                val e = p.edit()
                    .remove(KEY_PENDING_RT)
                    .remove(KEY_PENDING_BOOT)
                    .remove(KEY_OUTCOME)
                val streak = p.getInt(KEY_FUTILE_STREAK, 0)
                when (verdict) {
                    Verdict.HELPED -> e.putInt(KEY_HELPED, p.getInt(KEY_HELPED, 0) + 1)
                        .putInt(KEY_FUTILE_STREAK, 0)
                    Verdict.NO_REBIND -> e.putInt(KEY_NO_REBIND, p.getInt(KEY_NO_REBIND, 0) + 1)
                        .putInt(KEY_FUTILE_STREAK, streak + 1)
                    // Counted when the system asked (noteAskedTap / noteFailed); only the streak here.
                    Verdict.NEEDS_TAP, Verdict.FAILED -> e.putInt(KEY_FUTILE_STREAK, streak + 1)
                    Verdict.DROPPED -> Unit
                }
                e.apply()
                verdict
            }
        }.getOrNull()

    /** Every attempt and what it came to. */
    data class Counts(
        val attempts: Int = 0,
        val helped: Int = 0,
        val noRebind: Int = 0,
        val askedTap: Int = 0,
        val failed: Int = 0,
        val futileStreak: Int = 0,
    )

    fun counts(context: Context): Counts = runCatching {
        val p = prefs(context)
        Counts(
            attempts = p.getInt(KEY_ATTEMPTS, 0),
            helped = p.getInt(KEY_HELPED, 0),
            noRebind = p.getInt(KEY_NO_REBIND, 0),
            askedTap = p.getInt(KEY_ASKED_TAP, 0),
            failed = p.getInt(KEY_FAILED, 0),
            futileStreak = p.getInt(KEY_FUTILE_STREAK, 0),
        )
    }.getOrDefault(Counts())
}
