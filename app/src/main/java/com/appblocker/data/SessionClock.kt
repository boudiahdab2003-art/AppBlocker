package com.appblocker.data

import android.os.SystemClock

/**
 * Computes the time remaining in a duration-anchored session (Strict Focus, Quick Timer,
 * Pomodoro) in a way that can't be shortened by changing the device clock.
 *
 * Within a single boot, [SystemClock.elapsedRealtime] is monotonic and immune to wall-clock
 * changes, so moving the system clock forward cannot end a session early. A reboot resets the
 * monotonic clock, so it is used only when the saved and current Android boot counts match.
 * After a reboot, and for legacy records without a boot count, the guarded wall-clock deadline
 * is the best information available.
 */
object SessionClock {

    /**
     * Remaining millis for a session; 0 means expired/inactive.
     *
     * On the wall-clock (post-reboot) path, [wallStart] guards against a wrong device clock
     * resurrecting an old session: a clock reading *before* the session even started is
     * impossible, so the session is treated as inactive, and remaining can never exceed the
     * session's original duration. wallStart == 0 means a legacy record with no start anchor.
     */
    fun remaining(
        realtimeStart: Long,
        realtimeEnd: Long,
        wallStart: Long,
        wallEnd: Long,
        savedBootCount: Int,
        currentBootCount: Int,
    ): Long {
        return remainingAt(
            realtimeStart, realtimeEnd, wallStart, wallEnd,
            savedBootCount, currentBootCount,
            SystemClock.elapsedRealtime(), System.currentTimeMillis(),
        )
    }

    internal fun remainingAt(
        realtimeStart: Long,
        realtimeEnd: Long,
        wallStart: Long,
        wallEnd: Long,
        savedBootCount: Int,
        currentBootCount: Int,
        nowRt: Long,
        nowWall: Long,
    ): Long {
        val sameKnownBoot = savedBootCount >= 0 && savedBootCount == currentBootCount
        if (sameKnownBoot && realtimeEnd > 0L && nowRt >= realtimeStart) {
            return (realtimeEnd - nowRt).coerceAtLeast(0L)
        }
        val raw = (wallEnd - nowWall).coerceAtLeast(0L)
        if (wallStart <= 0L) return raw
        if (nowWall < wallStart) return 0L
        return raw.coerceAtMost(wallEnd - wallStart)
    }

    /**
     * Elapsed millis since a session that started at [realtimeStart] / [wallStart].
     * Mirrors [remaining]: monotonic within a boot, wall-clock after a reboot. Never negative.
     */
    fun elapsed(
        realtimeStart: Long,
        wallStart: Long,
        savedBootCount: Int,
        currentBootCount: Int,
    ): Long {
        return elapsedAt(
            realtimeStart, wallStart,
            savedBootCount, currentBootCount,
            SystemClock.elapsedRealtime(), System.currentTimeMillis(),
        )
    }

    internal fun elapsedAt(
        realtimeStart: Long,
        wallStart: Long,
        savedBootCount: Int,
        currentBootCount: Int,
        nowRt: Long,
        nowWall: Long,
    ): Long {
        val sameKnownBoot = savedBootCount >= 0 && savedBootCount == currentBootCount
        return if (sameKnownBoot && realtimeStart > 0L && nowRt >= realtimeStart) {
            nowRt - realtimeStart
        } else {
            (nowWall - wallStart).coerceAtLeast(0L)
        }
    }
}
