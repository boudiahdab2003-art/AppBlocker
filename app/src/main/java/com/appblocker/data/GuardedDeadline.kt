package com.appblocker.data

import android.os.SystemClock

/**
 * "Locked until X", anchored so that changing the device clock can't shorten it.
 *
 * Anchored exactly like a Strict/Timer session (see [SessionClock]) rather than as a plain
 * wall-clock deadline. Both users of this were originally a bare epoch-millis deadline compared
 * against `System.currentTimeMillis()`, so winding the clock forward defeated them — the very
 * bypass every session timer was moved off the wall clock to prevent:
 *
 *  - **the keyword lockout**: an app stays fully blocked for a while after a blocked word was
 *    caught in it. That is the consequence for hitting a blocked word, so it should not be cheap
 *    to escape.
 *  - **the adult-pack off delay**: turning the built-in adult filter off only *requests* it, and
 *    the pack keeps working for 24 hours first. That delay is the app's single most deliberately
 *    hardened protection, and it was one clock change away from nothing.
 *
 * This type exists **because that mistake was made twice.** When sessions were moved onto
 * [SessionClock] the keyword lockout was overlooked, precisely because it had its own separate
 * implementation of the same idea. Anything else that needs a deadline the user shouldn't be able
 * to skip belongs here too, not in a third copy.
 *
 * [note] is in-memory only (the blocked word, for showing again on re-entry); after a restart the
 * deadline survives but the note is gone. It is deliberately not persisted: a user keyword can
 * contain anything, including this format's separator.
 */
internal data class GuardedDeadline(
    val realtimeStart: Long,
    val realtimeEnd: Long,
    val wallStart: Long,
    val wallEnd: Long,
    val bootCount: Int,
    val note: String? = null,
) {
    /** Millis left, 0 when expired — monotonic within a boot, guarded wall clock after one.
     *  [extraMs] shifts the end later, for callers with a second, later moment measured from the
     *  same anchors (the adult gate's "and then the switch works for a while" window). */
    fun remaining(currentBootCount: Int, extraMs: Long = 0L): Long = remainingAt(
        currentBootCount, SystemClock.elapsedRealtime(), System.currentTimeMillis(), extraMs,
    )

    /**
     * [remaining] with the clocks passed in, mirroring [SessionClock.remainingAt].
     *
     * The seam is the point: unit tests run with `isReturnDefaultValues = true`, so an un-mocked
     * `SystemClock.elapsedRealtime()` silently returns 0 while `System.currentTimeMillis()` is the
     * real JVM clock. Reading them inside made this untestable — the monotonic branch could never
     * be reached, because nowRt of 0 always looks like a reboot.
     */
    internal fun remainingAt(
        currentBootCount: Int,
        nowRt: Long,
        nowWall: Long,
        extraMs: Long = 0L,
    ): Long = SessionClock.remainingAt(
        realtimeStart, realtimeEnd + extraMs, wallStart, wallEnd + extraMs,
        bootCount, currentBootCount, nowRt, nowWall,
    )


    /** `pkg|rtStart|rtEnd|wallStart|wallEnd|boot`. Package names cannot contain '|'. */
    fun encode(pkg: String): String =
        "$pkg|$realtimeStart|$realtimeEnd|$wallStart|$wallEnd|$bootCount"

    companion object {
        /** A deadline [durationMs] from now, anchored to both clocks and the current boot. Both
         *  clocks are read once each, so the two anchors can't drift by a scheduling hiccup. */
        fun starting(durationMs: Long, bootCount: Int, note: String? = null): GuardedDeadline {
            val nowRt = SystemClock.elapsedRealtime()
            val nowWall = System.currentTimeMillis()
            return GuardedDeadline(
                realtimeStart = nowRt,
                realtimeEnd = nowRt + durationMs,
                wallStart = nowWall,
                wallEnd = nowWall + durationMs,
                bootCount = bootCount,
                note = note,
            )
        }

        /**
         * Parses one stored entry, or null if it is unreadable.
         *
         * Also accepts the old two-field `pkg|expiryEpochMillis` form so an app update doesn't
         * silently drop lockouts that are still running. Those keep using the wall clock (there
         * is no better information in them) until they expire; new ones are clock-proof.
         */
        fun decode(entry: String): Pair<String, GuardedDeadline>? {
            val parts = entry.split('|')
            val pkg = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
            return when (parts.size) {
                6 -> {
                    val n = parts.drop(1).map { it.toLongOrNull() ?: return null }
                    pkg to GuardedDeadline(n[0], n[1], n[2], n[3], n[4].toInt())
                }
                // Legacy: expiry only. bootCount -1 forces the wall-clock path in SessionClock.
                2 -> {
                    val until = parts[1].toLongOrNull() ?: return null
                    pkg to GuardedDeadline(0L, 0L, 0L, until, -1)
                }
                else -> null
            }
        }
    }
}
