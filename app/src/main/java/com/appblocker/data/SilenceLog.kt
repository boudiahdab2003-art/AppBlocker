package com.appblocker.data

import android.content.Context
import com.appblocker.service.CoverGate

/**
 * What the blocker **declined to do**, which until now nothing in this app measured.
 *
 * **Every other instrument here records a success.** Blocked opens, minutes reclaimed, the attempt
 * counter, `BlockLog` — all of them are lists of covers that went up. The moments the watcher
 * decided *not* to cover something left no trace at all: two of the three declines wrote a line
 * only under `BlockerAccessibilityService.DEBUG`, which is false in every build the owner has ever
 * run, and the third was a bare `return`.
 *
 * That is why the eight-second dismiss-grace hole (invariant 20) survived from v1.135 to 26 Aug
 * 2026 and was found by the owner relapsing through it. `docs/BLOCKING_INVARIANTS.md` says at the
 * top that **under-blocking is invisible to him** — he notices a cover that should not be there,
 * never one that never came — and then gave him no dial that could have shown it. A count of
 * "the blocker went quiet while you were still in the app" would have been screaming for months.
 *
 * It follows the reasoning already used for the device profile reports: **the healthy ones are
 * filed too, because that is what makes silence mean something.** A zero here is information.
 *
 * **Counts only — never what was on screen.** No package, no host, no word. Same rule as
 * [BlockLog], and for the same reason: this travels in a bug report.
 */
object SilenceLog {

    private const val PREFS = "silence_log"

    /**
     * Whether a decline this long after a dismissal is the *suspicious* kind.
     *
     * [CoverGate.DISMISS_GRACE_MS] is unconditional and short, and absorbing the departing
     * screen's stragglers is exactly what it is for — declines inside it are the mechanism
     * working. Past it, the only thing still suppressing is the long "the app is stuck on screen"
     * extension, and a decline there means the watcher stayed quiet while the user was sitting in
     * the app it had just covered. That is the half worth counting.
     */
    fun isLate(sinceDismissMs: Long): Boolean = sinceDismissMs >= CoverGate.DISMISS_GRACE_MS

    /** One counter's today/total pair. */
    data class Count(val today: Int, val total: Int)

    /** Dismissals after which the watcher went quiet past the short grace — the shape of a
     *  cover that should have been raised and was not. */
    const val DEAF_DISMISSALS = "deaf_dismissals"

    /** Individual declines past the short grace. Context for [DEAF_DISMISSALS]: one deaf
     *  dismissal that declined forty times is a page the user was actively reading. */
    const val LATE_DECLINES = "late_declines"

    /** Binds during which a blocking decision had to be made before the rules had loaded.
     *  Non-zero means the window invariant 11's update is about is real on this phone. */
    const val UNREADY_DECISIONS = "unready_decisions"

    val KINDS = listOf(DEAF_DISMISSALS, LATE_DECLINES, UNREADY_DECISIONS)

    fun get(context: Context, kind: String): Count {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = todayStamp()
        val todayCount =
            if (prefs.getInt("day_$kind", -1) == today) prefs.getInt("today_$kind", 0) else 0
        return Count(todayCount, prefs.getInt("total_$kind", 0))
    }

    /** Wrapped by every caller: this runs on the watcher's hot path, and a counter is never
     *  worth failing a block for. */
    fun record(context: Context, kind: String) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val today = todayStamp()
            val storedDay = prefs.getInt("day_$kind", -1)
            prefs.edit()
                .putInt("total_$kind", prefs.getInt("total_$kind", 0) + 1)
                .putInt("today_$kind", if (storedDay == today) prefs.getInt("today_$kind", 0) + 1 else 1)
                .putInt("day_$kind", today)
                .apply()
        }
    }

    /** One line per counter for the diagnostics screen and the bug report body. */
    fun summary(context: Context): List<Pair<String, Count>> = KINDS.map { it to get(context, it) }
}
