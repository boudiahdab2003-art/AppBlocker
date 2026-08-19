package com.appblocker.data

import android.content.Context
import android.os.SystemClock

/**
 * A running Quick Block session: either a one-shot Timer (block for N minutes) or a Pomodoro
 * (repeating work/break cycles, blocking during work). Stored in SharedPreferences so both
 * the UI and the accessibility service can read it. All times in millis.
 *
 * Durations are anchored to the monotonic clock (see [SessionClock]) so moving the device clock
 * forward can't end a session early; wall-clock anchors are kept as a post-reboot fallback.
 */
object QuickSession {
    private const val PREFS = "quick_session"
    private const val MODE_NONE = 0
    private const val MODE_TIMER = 1
    private const val MODE_POMO = 2

    data class State(
        val active: Boolean,
        val blockingNow: Boolean,
        val remainingMillis: Long,
        val label: String, // "Time left" | "Work" | "Break"
    )

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun startTimer(ctx: Context, minutes: Int) {
        val duration = minutes * 60_000L
        val nowRt = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        p(ctx).edit().clear()
            .putInt("mode", MODE_TIMER)
            .putLong("rtStart", nowRt)
            .putLong("rtEnd", nowRt + duration)
            .putLong("wallStart", nowWall)
            .putLong("wallEnd", nowWall + duration)
            .putInt("bootCount", DeviceBoot.count(ctx))
            .apply()
    }

    fun startPomodoro(ctx: Context, workMin: Int, breakMin: Int, rounds: Int) {
        p(ctx).edit().clear()
            .putInt("mode", MODE_POMO)
            .putLong("rtStart", SystemClock.elapsedRealtime())
            .putLong("wallStart", System.currentTimeMillis())
            .putInt("bootCount", DeviceBoot.count(ctx))
            .putInt("work", workMin).putInt("break", breakMin).putInt("rounds", rounds)
            .apply()
    }

    fun stop(ctx: Context) = p(ctx).edit().clear().apply()

    /** Current session state; lazily clears a finished session. */
    fun state(ctx: Context): State {
        val prefs = p(ctx)
        when (prefs.getInt("mode", MODE_NONE)) {
            MODE_TIMER -> {
                val remaining = SessionClock.remaining(
                    prefs.getLong("rtStart", 0L),
                    prefs.getLong("rtEnd", 0L),
                    prefs.getLong("wallStart", 0L),
                    prefs.getLong("wallEnd", 0L),
                    prefs.getInt("bootCount", -1),
                    DeviceBoot.count(ctx),
                )
                if (remaining <= 0L) { stop(ctx); return idle() }
                return State(true, blockingNow = true, remainingMillis = remaining, label = "Time left")
            }
            MODE_POMO -> {
                val work = prefs.getInt("work", 25) * 60_000L
                val brk = prefs.getInt("break", 5) * 60_000L
                val rounds = prefs.getInt("rounds", 4)
                val cycle = work + brk
                val total = cycle * rounds
                val elapsed = SessionClock.elapsed(
                    prefs.getLong("rtStart", 0L),
                    prefs.getLong("wallStart", 0L),
                    prefs.getInt("bootCount", -1),
                    DeviceBoot.count(ctx),
                )
                if (elapsed >= total) { stop(ctx); return idle() }
                val pos = elapsed % cycle
                return if (pos < work) {
                    State(true, blockingNow = true, remainingMillis = work - pos, label = "Work")
                } else {
                    State(true, blockingNow = false, remainingMillis = cycle - pos, label = "Break")
                }
            }
            else -> return idle()
        }
    }

    private fun idle() = State(active = false, blockingNow = false, remainingMillis = 0L, label = "")

    /**
     * Whether Quick Block is enforcing right now — Strict beats every pause, then the
     * after-update pause, then a running session's own work/break phase, then the manual pause.
     *
     * Pure, and lifted out of the watcher's `quickBlockActive()` that used to be its only home,
     * because the UI now needs the same answer: which website words are live depends on it, and a
     * paraphrase of this in a second place is the "two sources of truth that drift" shape
     * docs/BLOCKING_INVARIANTS.md opens with.
     */
    fun enforcing(
        strictRemainingMs: Long,
        updatePaused: Boolean,
        session: State,
        quickBlockPaused: Boolean,
    ): Boolean = when {
        strictRemainingMs > 0L -> true
        updatePaused -> false
        session.active -> session.blockingNow
        else -> !quickBlockPaused
    }
}
