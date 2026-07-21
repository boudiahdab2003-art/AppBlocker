package com.appblocker.data

import android.os.SystemClock

/**
 * Computes the time remaining in a duration-anchored session (Strict Focus, Quick Timer,
 * Pomodoro) in a way that can't be shortened by changing the device clock.
 *
 * Within a single boot, [SystemClock.elapsedRealtime] is monotonic and immune to wall-clock
 * changes, so moving the system clock forward cannot end a session early. A reboot resets the
 * monotonic clock, so we detect that (`now < realtimeStart`) and fall back to the wall-clock
 * deadline, which is the best information available after a reboot. Legacy sessions written
 * before this feature (realtimeEnd == 0) also use the wall-clock path.
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
    fun remaining(realtimeStart: Long, realtimeEnd: Long, wallStart: Long, wallEnd: Long): Long {
        return remainingAt(
            realtimeStart, realtimeEnd, wallStart, wallEnd,
            SystemClock.elapsedRealtime(), System.currentTimeMillis(),
        )
    }

    internal fun remainingAt(
        realtimeStart: Long,
        realtimeEnd: Long,
        wallStart: Long,
        wallEnd: Long,
        nowRt: Long,
        nowWall: Long,
    ): Long {
        if (realtimeEnd > 0L && nowRt >= realtimeStart) {
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
    fun elapsed(realtimeStart: Long, wallStart: Long): Long {
        return elapsedAt(
            realtimeStart, wallStart,
            SystemClock.elapsedRealtime(), System.currentTimeMillis(),
        )
    }

    internal fun elapsedAt(
        realtimeStart: Long,
        wallStart: Long,
        nowRt: Long,
        nowWall: Long,
    ): Long {
        return if (realtimeStart > 0L && nowRt >= realtimeStart) {
            nowRt - realtimeStart
        } else {
            (nowWall - wallStart).coerceAtLeast(0L)
        }
    }
}
