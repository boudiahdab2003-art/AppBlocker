package com.appblocker.data

import android.content.Context
import android.os.SystemClock

/**
 * **Did our own start-up run after the phone restarted, and how long after?**
 *
 * Written for the longest stoppage ever recorded on the owner's phone: 5 Sep 2026, 351 minutes,
 * `after=boot`, and **350 of those minutes passed before anything noticed**. The fast detector
 * (`DetectedBy.UNBOUND`, about twenty seconds) never fired. The screen probe (about fifteen
 * minutes) never fired. Only `STALE` did — the fallback that works from a stored `lastEventAt`
 * rather than from live state, which is precisely the detector that still answers when nothing
 * else has been running.
 *
 * The suspicion that follows: after that reboot **nothing of ours ran at all**. The watchdog alarm
 * is a one-shot `setAndAllowWhileIdle`, and alarms do not survive a reboot — the only thing that
 * re-arms it is [com.appblocker.service.BootReceiver] via `ProtectionScheduler.ensureScheduled`.
 * If `BOOT_COMPLETED` never reaches us, and an OEM auto-start restriction is exactly that, then
 * there is no alarm, no worker and no process, so no detector *can* run until something else
 * happens to open the app.
 *
 * ⚠️ **Nothing in the app could answer this, and one number looked as though it could.** `boots`
 * comes from [DeviceBoot], which reads Android's own `Settings.Global.BOOT_COUNT` on demand — it
 * counts the phone's restarts and says nothing whatever about whether our receiver ran. Believing
 * it would have closed the question with the wrong answer.
 *
 * So this records the one fact that settles it, and it is deliberately small: a stamp written by
 * the receiver itself, and a count of the boots that went by without one.
 */
object BootAudit {

    private const val PREFS = "boot_audit"

    /** The boot count as it stood when [heard] last ran, and the elapsed-realtime at that moment —
     *  which, measured from a boot, IS the lag since it (invariant 9: monotonic, from the OS). */
    private const val KEY_HEARD_BOOT = "heard_boot"
    private const val KEY_HEARD_RT = "heard_rt"

    /** The last boot [noteRun] has already judged, so one missed boot is counted once however many
     *  components run afterwards. */
    private const val KEY_NOTED_BOOT = "noted_boot"
    private const val KEY_MISSED = "missed_count"

    /** When something of ours was FIRST seen running on this boot. See [judge]. */
    private const val KEY_SEEN_BOOT = "seen_boot"
    private const val KEY_SEEN_RT = "seen_rt"

    /**
     * ⚠️ **How long [heard] gets to arrive before a boot is called missed.**
     *
     * The first version judged on first sight, and that is a race this loses more often than not:
     * Android binds an enabled accessibility service early, so `onServiceConnected` reaches
     * `noteRun` before `BOOT_COMPLETED` is ever delivered — and on a file-based-encryption phone
     * that broadcast waits for the first unlock, which can be hours later. The counter would have
     * climbed on every single restart and reported the exact opposite of the truth, about the one
     * question it exists to answer.
     */
    private const val JUDGE_AFTER_MS = 2 * 60_000L

    enum class Judgement { HEARD, WAIT, MISSED }

    /**
     * Pure, because the whole instrument is this decision.
     *
     * [WAIT][Judgement.WAIT] is the honest answer on first sight and it must stay reachable: a
     * verdict taken before the receiver could plausibly have run is not evidence, it is a guess
     * with a number attached — the same rule `recordReviveOutcome` follows on a dark screen.
     */
    internal fun judge(heard: Boolean, firstSeenRt: Long, nowRt: Long): Judgement = when {
        heard -> Judgement.HEARD
        firstSeenRt <= 0L -> Judgement.WAIT
        nowRt - firstSeenRt >= JUDGE_AFTER_MS -> Judgement.MISSED
        else -> Judgement.WAIT
    }

    /** No stamp for the boot being asked about. */
    const val MISSED = -1L

    /** No stamp at all, ever — a fresh install that has not seen a restart yet. Distinct from
     *  [MISSED] because "we have never had the chance" is not "we were not started". */
    const val NEVER = -2L

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The lag for [nowBoot], or [MISSED] / [NEVER].
     *
     * Pure, for the reason [com.appblocker.service.ProtectionPulse.sinceStamp] is: unit tests run
     * with `isReturnDefaultValues = true`, so an un-mocked `SystemClock.elapsedRealtime()` returns
     * 0 in silence, and a rule that cannot be stated in a test is a rule nobody checks.
     */
    internal fun lagFor(storedBoot: Int, storedRt: Long, nowBoot: Int): Long = when {
        storedRt <= 0L -> NEVER
        // An unreadable boot counter is -1 on both sides and compares equal. That is right: "can't
        // tell" must not invent a missed boot (invariant 11), and inventing one here would put a
        // red line on a healthy phone — the exact failure the 5 Sep report pass was about.
        storedBoot != nowBoot -> MISSED
        else -> storedRt.coerceAtLeast(0L)
    }

    /**
     * ⚠️ **Must be the first thing [com.appblocker.service.BootReceiver] does on a boot.**
     *
     * Everything else the receiver calls reaches [noteRun], which would otherwise look for a stamp
     * that this call has not written yet and record the receiver's own boot as missed — an
     * instrument reporting the opposite of what happened. `CodeShapeTest` fails the build on the
     * ordering rather than leaving it to whoever edits the receiver next.
     */
    fun heard(context: Context) {
        runCatching {
            prefs(context).edit()
                .putInt(KEY_HEARD_BOOT, DeviceBoot.count(context))
                .putLong(KEY_HEARD_RT, SystemClock.elapsedRealtime().coerceAtLeast(1L))
                .apply()
        }
    }

    /** How long after this boot our start-up ran, or [MISSED] / [NEVER]. */
    fun lagMsForThisBoot(context: Context): Long = runCatching {
        val p = prefs(context)
        lagFor(
            storedBoot = p.getInt(KEY_HEARD_BOOT, -2),
            storedRt = p.getLong(KEY_HEARD_RT, 0L),
            nowBoot = DeviceBoot.count(context),
        )
    }.getOrDefault(NEVER)

    /**
     * **The audit, run by whatever finally does run.**
     *
     * Called from `ProtectionWatchdog.checkAndNotify`, which every out-of-process check passes
     * through — boot, worker, alarm, notification listener, tile, app resume and the watcher
     * itself. Lazy on purpose: a missed boot only becomes knowable at the moment something else
     * gets going, because until then there is by definition nothing to notice it.
     */
    fun noteRun(context: Context) {
        runCatching {
            val p = prefs(context)
            val now = DeviceBoot.count(context)
            if (p.getInt(KEY_NOTED_BOOT, -2) == now) return@runCatching
            val nowRt = SystemClock.elapsedRealtime()
            // First sighting of this boot: remember when, and judge nothing yet.
            val seen = if (p.getInt(KEY_SEEN_BOOT, -2) == now) p.getLong(KEY_SEEN_RT, 0L) else 0L
            val heard = lagFor(
                storedBoot = p.getInt(KEY_HEARD_BOOT, -2),
                storedRt = p.getLong(KEY_HEARD_RT, 0L),
                nowBoot = now,
            ) >= 0L
            when (judge(heard, seen, nowRt)) {
                Judgement.WAIT -> if (seen <= 0L) {
                    p.edit()
                        .putInt(KEY_SEEN_BOOT, now)
                        .putLong(KEY_SEEN_RT, nowRt.coerceAtLeast(1L))
                        .apply()
                }
                Judgement.HEARD -> p.edit().putInt(KEY_NOTED_BOOT, now).apply()
                Judgement.MISSED -> p.edit()
                    .putInt(KEY_NOTED_BOOT, now)
                    .putInt(KEY_MISSED, p.getInt(KEY_MISSED, 0) + 1)
                    .apply()
            }
        }
    }

    fun missedCount(context: Context): Int =
        runCatching { prefs(context).getInt(KEY_MISSED, 0) }.getOrDefault(0)
}
