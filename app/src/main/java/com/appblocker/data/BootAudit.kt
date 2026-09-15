package com.appblocker.data

import android.app.ActivityManager
import android.app.ApplicationStartInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import kotlin.math.abs

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
            val p = prefs(context)
            val boot = DeviceBoot.count(context)
            val nowRt = SystemClock.elapsedRealtime()
            // The boot's own start-up is the FIRST time this runs in a boot. A later BOOT_COMPLETED in
            // the same boot is a comeback after a force stop that still fell inside isBoot's window,
            // and stamping it would replace the boot's real lag with a later one.
            val keep = keepsEarlierStamp(
                storedBoot = p.getInt(KEY_HEARD_BOOT, -2),
                storedRt = p.getLong(KEY_HEARD_RT, 0L),
                nowBoot = boot,
                nowRt = nowRt,
            )
            if (keep) return@runCatching
            p.edit()
                .putInt(KEY_HEARD_BOOT, boot)
                .putLong(KEY_HEARD_RT, nowRt.coerceAtLeast(1L))
                .apply()
        }
    }

    /**
     * Whether a stamp already written stands. Pure, for the reason [lagFor] is.
     *
     * Only a stamp from this same boot does: a readable boot count on both sides, the two equal, and
     * a stored time no later than now. An unreadable counter is -1 on both sides and would compare
     * equal for ever, so it never keeps (every boot then stamps, as it always did); and a stored time
     * later than now means a restart the counter did not see.
     */
    internal fun keepsEarlierStamp(storedBoot: Int, storedRt: Long, nowBoot: Int, nowRt: Long): Boolean =
        storedRt > 0L && storedBoot >= 0 && storedBoot == nowBoot && storedRt <= nowRt

    /** A start record this close to our own process's start is that start. */
    private const val START_MATCH_MS = 5_000L

    /** Reaches past the warm starts recorded since, back to this process's own cold start. */
    private const val MAX_STARTS = 16

    /**
     * How far into a boot a start after a force stop still counts as the boot's own start-up.
     *
     * If Android binds our notification listener as the phone comes up, that bind is itself what
     * takes a force-stopped app out of the stopped state — the boot heard through another door.
     * Generous on purpose: too tight turns a slow phone's boot into a missed one, while too loose
     * only lets a start minutes into a boot stamp a lag of minutes.
     */
    private const val BOOT_WINDOW_MS = 10 * 60_000L

    /**
     * ⚠️ **Whether the `BOOT_COMPLETED` being handled is the phone's boot** (invariant 77).
     *
     * Since Android 15 a force-stopped app is sent `BOOT_COMPLETED` when it next starts, with no
     * restart at all: on the Android 16 emulator on 15 Sep 2026 it arrived 0.2 s after a force stop,
     * at exactly the cold starts Android marked `wasForceStopped`. [heard] believed every one, so
     * "how long after the restart did our start-up run" could be written hours into a boot — the
     * likeliest source of the owner's `bootHeard 58531s`, printed under a ✅ that a long wait is
     * normal.
     */
    fun isBoot(context: Context): Boolean =
        isBoot(startedAfterForceStop(context), SystemClock.elapsedRealtime())

    /**
     * The pure rule behind [isBoot]. A start Android did not mark force-stopped is the boot, however
     * late — a file-based-encryption phone hands the broadcast out at the first unlock — and so is
     * "can't tell", which is what every earlier version assumed. A start after a force stop is the
     * boot only inside [BOOT_WINDOW_MS]; later than that the phone had been up a while, and it is
     * the app coming back.
     */
    internal fun isBoot(forceStopped: Boolean?, uptimeMs: Long): Boolean =
        forceStopped != true || uptimeMs < BOOT_WINDOW_MS

    /**
     * Whether this process started while the app was force-stopped, from Android's own start
     * record — which exists exactly where the behaviour does (API 35+). Null when it cannot be told:
     * an older phone, or no record matching this process.
     */
    private fun startedAfterForceStop(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
        return runCatching {
            val am = context.getSystemService(ActivityManager::class.java) ?: return null
            val starts = am.getHistoricalProcessStartReasons(MAX_STARTS)
                .filter {
                    it.processName == context.packageName &&
                        it.startType == ApplicationStartInfo.START_TYPE_COLD
                }
                .mapNotNull { start ->
                    val launchNs = start.startupTimestamps[ApplicationStartInfo.START_TIMESTAMP_LAUNCH]
                        ?: return@mapNotNull null
                    ColdStart(launchRtMs = launchNs / 1_000_000L, forceStopped = start.wasForceStopped())
                }
            startedAfterForceStop(starts, Process.getStartElapsedRealtime())
        }.getOrNull()
    }

    /** One cold start of this app's process, as Android recorded it. */
    internal data class ColdStart(val launchRtMs: Long, val forceStopped: Boolean)

    /**
     * The pure half of reading the start record: the answer carried by the record that IS this
     * process's start, or null.
     *
     * Matched by time rather than pid — a cold start for a bound service is recorded with pid 0 —
     * and only within [START_MATCH_MS], because the records outlive a reboot: the newest one can
     * belong to the process that died before the restart being asked about.
     */
    internal fun startedAfterForceStop(starts: List<ColdStart>?, processStartRt: Long): Boolean? {
        if (starts == null || processStartRt <= 0L) return null
        return starts
            .filter { abs(it.launchRtMs - processStartRt) <= START_MATCH_MS }
            .minByOrNull { abs(it.launchRtMs - processStartRt) }
            ?.forceStopped
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
