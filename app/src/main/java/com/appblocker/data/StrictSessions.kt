package com.appblocker.data

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.first

/**
 * Starting and lengthening a Strict Mode session, outside any one screen.
 *
 * The Strict screen ([com.appblocker.ui.FocusViewModel]) and the AI Coach's "🛡 Strict" button
 * (invariant 114) both need it, and two copies of "start, or extend the one that is running" is
 * exactly the two-state-machines shape `docs/BLOCKING_INVARIANTS.md` opens with. Only ever moves a
 * deadline later or starts a new one: there is no stop here, as there is none anywhere.
 */
object StrictSessions {

    /** Millis left on the running session, 0 when none is running. */
    suspend fun remainingMs(ctx: Context): Long {
        val state = BlockerDatabase.get(ctx).focusDao().get().first() ?: return 0L
        return SessionClock.remaining(
            state.realtimeStartMillis, state.realtimeEndMillis,
            state.startTimeMillis, state.endTimeMillis,
            state.bootCount, DeviceBoot.count(ctx),
        )
    }

    /** Starts a fresh session of [minutes]. */
    suspend fun start(ctx: Context, minutes: Int) {
        if (minutes <= 0) return
        StatsStore.addStrictMinutes(ctx, minutes)
        val duration = minutes * 60_000L
        val nowRt = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        BlockerDatabase.get(ctx).focusDao().set(
            FocusState(
                id = 0,
                endTimeMillis = nowWall + duration,
                realtimeStartMillis = nowRt,
                realtimeEndMillis = nowRt + duration,
                startTimeMillis = nowWall,
                bootCount = DeviceBoot.count(ctx),
                appVersionCode = AppVersion.code(ctx),
            ),
        )
    }

    /**
     * Adds [minutes] to the running session. Records the minutes only if the row actually moved —
     * the DAO refuses to resurrect a session that has already ended, and Insights must not count
     * Strict minutes that never happened.
     */
    suspend fun extend(ctx: Context, minutes: Int): Boolean {
        if (minutes <= 0) return false
        val moved = BlockerDatabase.get(ctx).focusDao().extend(minutes * 60_000L) > 0
        if (moved) StatsStore.addStrictMinutes(ctx, minutes)
        return moved
    }

    /**
     * Lengthens the running session by [minutes], or starts one of [minutes] when none runs. Reads
     * the row rather than trusting a cached flag: a session that ended a moment ago but has not been
     * zeroed yet must start afresh, not be extended onto a deadline already in the past.
     */
    suspend fun startOrExtend(ctx: Context, minutes: Int) {
        if (minutes <= 0) return
        if (remainingMs(ctx) > 0L && extend(ctx, minutes)) return
        start(ctx, minutes)
    }
}
