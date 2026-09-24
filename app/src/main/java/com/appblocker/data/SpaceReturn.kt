package com.appblocker.data

import android.content.Context
import android.os.SystemClock

/**
 * **This space coming back to the front after the other one** (invariant 83).
 *
 * Invariant 79 stands every check down while another space — Xiaomi's Second Space — is in front,
 * because Android unbinds this space's watcher on purpose then. What it did not cover is the way
 * back. Android binds the watcher again some seconds after this space is in front again — about
 * 30 s on the Android 16 emulator — and a check that runs in between finds the switch on, the
 * watcher gone and a process hours old: every sign of a killed watcher. On 23 Sep 2026 his main
 * phone filed that shape (report #196: `deaf=true killedBy=none used=0`, `rebound-after-repair`,
 * an orderly unbind with the process alive) minutes after he had opened AppBlocker, with his Second
 * Space copy watching a minute later — the best fit being a check inside that gap, the silent repair
 * switching the watcher off and on, and the rebind Android was about to make anyway credited to the
 * repair, in `selfToggle`, the one number that says whether the repair works. Reproduced on the
 * emulator on 24 Sep 2026 with AppBlocker's screen on top: screen resumed +7 s after the switch
 * back, stoppage filed +10 s, repair +13 s, bound +28 s, filed in #196's exact shape.
 *
 * So a return is treated like a start. An unbind with another space in front leaves a return
 * AWAITED; the first check that sees this space in front again starts the wait
 * ([com.appblocker.service.SPACE_RETURN_GRACE_MS]), and until it runs out the watchdog waits for
 * Android instead of judging, as it does for a process seconds old. The watcher being bound again
 * ends it. Past it, a watcher still missing is judged as before: a return Android never rebinds is a
 * real stoppage, and it is repaired.
 *
 * ⚠️ **Fails open, like [OwnSpace].** Only an unbind Android positively placed behind another space
 * starts a wait. A doubt at the unbind, an unreadable boot count, a restart, or an unbind with this
 * space in front leaves nothing awaited, and every check judges as it always did.
 */
object SpaceReturn {

    private const val PREFS = "space_return"

    /** The boot in which the watcher was unbound with another space in front. Absent: nothing awaited. */
    private const val KEY_AWAITING_BOOT = "awaiting_boot"

    /** When a check first saw this space in front again while a return was awaited (monotonic). */
    private const val KEY_SEEN_RT = "seen_rt"

    /** Returns in which a check found the watcher not back yet, and so waited instead of judging. */
    private const val KEY_WAITS = "waits"

    private const val NONE = Int.MIN_VALUE

    /**
     * Whether a return is awaited in this boot. Pure. An unreadable boot count (`-1`) awaits nothing,
     * so a failed read can never hold a stoppage back.
     */
    internal fun awaited(awaitingBoot: Int, boot: Int): Boolean = boot >= 0 && awaitingBoot == boot

    /**
     * Millis since a check first saw the return, or null while none has. Pure. A stamp ahead of the
     * clock is not from this boot's clock and is treated as not seen.
     */
    internal fun sinceSeen(seenRt: Long, nowRt: Long): Long? =
        if (seenRt <= 0L || seenRt > nowRt) null else nowRt - seenRt

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The watcher was unbound in an orderly way. [behind]: Android said another space was in front
     * at that moment — the switch away, the only unbind worth waiting out. Any other unbind ends a
     * wait, because nothing is coming back by itself after it.
     */
    fun noteUnbind(context: Context, behind: Boolean) {
        runCatching {
            synchronized(this) {
                val boot = DeviceBoot.count(context)
                val e = prefs(context).edit().remove(KEY_SEEN_RT)
                if (behind && boot >= 0) e.putInt(KEY_AWAITING_BOOT, boot) else e.remove(KEY_AWAITING_BOOT)
                e.apply()
            }
        }
    }

    /** The watcher is bound: whatever was awaited has arrived. */
    fun noteBound(context: Context) {
        runCatching {
            synchronized(this) {
                prefs(context).edit().remove(KEY_AWAITING_BOOT).remove(KEY_SEEN_RT).apply()
            }
        }
    }

    /**
     * A check has found this space in front — the caller has already asked [OwnSpace.inFront]. The
     * first one after an awaited return starts the wait, and is counted once per return: no check
     * runs while he is away, so this is the nearest the app gets to the moment he came back.
     */
    fun noteInFront(context: Context, nowRt: Long = SystemClock.elapsedRealtime()) {
        runCatching {
            synchronized(this) {
                val p = prefs(context)
                if (!awaited(p.getInt(KEY_AWAITING_BOOT, NONE), DeviceBoot.count(context))) return@synchronized
                if (sinceSeen(p.getLong(KEY_SEEN_RT, 0L), nowRt) != null) return@synchronized
                p.edit()
                    .putLong(KEY_SEEN_RT, nowRt.coerceAtLeast(1L))
                    .putInt(KEY_WAITS, p.getInt(KEY_WAITS, 0) + 1)
                    .apply()
            }
        }
    }

    /**
     * Millis since this space was seen back in front while its watcher is still awaited, or null
     * when no return is awaited or none has been seen yet. Reads only: [noteInFront] starts the wait.
     */
    fun sinceReturnMs(context: Context, nowRt: Long = SystemClock.elapsedRealtime()): Long? =
        runCatching {
            val p = prefs(context)
            if (!awaited(p.getInt(KEY_AWAITING_BOOT, NONE), DeviceBoot.count(context))) {
                null
            } else {
                sinceSeen(p.getLong(KEY_SEEN_RT, 0L), nowRt)
            }
        }.getOrNull()

    /** How many returns a check waited out. Our own integer, for the report. */
    fun waitCount(context: Context): Int =
        runCatching { prefs(context).getInt(KEY_WAITS, 0) }.getOrDefault(0)
}
