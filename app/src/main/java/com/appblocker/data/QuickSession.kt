package com.appblocker.data

import android.content.Context

/**
 * A running Quick Block session: either a one-shot Timer (block for N minutes) or a Pomodoro
 * (repeating work/break cycles, blocking during work). Stored in SharedPreferences so both
 * the UI and the accessibility service can read it. All times in millis.
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
        p(ctx).edit().clear()
            .putInt("mode", MODE_TIMER)
            .putLong("end", System.currentTimeMillis() + minutes * 60_000L)
            .apply()
    }

    fun startPomodoro(ctx: Context, workMin: Int, breakMin: Int, rounds: Int) {
        p(ctx).edit().clear()
            .putInt("mode", MODE_POMO)
            .putLong("start", System.currentTimeMillis())
            .putInt("work", workMin).putInt("break", breakMin).putInt("rounds", rounds)
            .apply()
    }

    fun stop(ctx: Context) = p(ctx).edit().clear().apply()

    /** Current session state; lazily clears a finished session. */
    fun state(ctx: Context): State {
        val prefs = p(ctx)
        val now = System.currentTimeMillis()
        when (prefs.getInt("mode", MODE_NONE)) {
            MODE_TIMER -> {
                val end = prefs.getLong("end", 0L)
                if (now >= end) { stop(ctx); return idle() }
                return State(true, blockingNow = true, remainingMillis = end - now, label = "Time left")
            }
            MODE_POMO -> {
                val start = prefs.getLong("start", 0L)
                val work = prefs.getInt("work", 25) * 60_000L
                val brk = prefs.getInt("break", 5) * 60_000L
                val rounds = prefs.getInt("rounds", 4)
                val cycle = work + brk
                val total = cycle * rounds
                val elapsed = now - start
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

    fun isBlockingNow(ctx: Context): Boolean = state(ctx).blockingNow

    private fun idle() = State(active = false, blockingNow = false, remainingMillis = 0L, label = "")
}
