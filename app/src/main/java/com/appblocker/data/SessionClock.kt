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

    /** Remaining millis for a session; 0 means expired/inactive. */
    fun remaining(realtimeStart: Long, realtimeEnd: Long, wallEnd: Long): Long {
        val nowRt = SystemClock.elapsedRealtime()
        return if (realtimeEnd > 0L && nowRt >= realtimeStart) {
            (realtimeEnd - nowRt).coerceAtLeast(0L)
        } else {
            (wallEnd - System.currentTimeMillis()).coerceAtLeast(0L)
        }
    }

    /**
     * Elapsed millis since a session that started at [realtimeStart] / [wallStart].
     * Mirrors [remaining]: monotonic within a boot, wall-clock after a reboot. Never negative.
     */
    fun elapsed(realtimeStart: Long, wallStart: Long): Long {
        val nowRt = SystemClock.elapsedRealtime()
        return if (realtimeStart > 0L && nowRt >= realtimeStart) {
            nowRt - realtimeStart
        } else {
            (System.currentTimeMillis() - wallStart).coerceAtLeast(0L)
        }
    }
}
