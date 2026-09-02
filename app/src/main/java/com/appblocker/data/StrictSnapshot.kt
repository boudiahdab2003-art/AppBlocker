package com.appblocker.data

/**
 * The Strict session to enforce in the seconds before Room has answered.
 *
 * **[RuleSnapshot]'s problem, one field over, and it was still open.** That file closed the hole
 * for app rules: until the database's first emission the watcher's `rules` map is empty, and an
 * empty map is not "nothing is blocked", it is "we have not been told yet". But the rules,
 * the Strict session, the blocked words and the schedules all arrive on **one** `combine` flow,
 * so every one of them is empty in that window \u2014 and only the rules had a fallback.
 *
 * The Strict session is the one that matters most here. `strictRemaining()` reads five fields that
 * are zero until the flow lands, so `SessionClock.remaining` returns 0 and Strict Mode reads as
 * **not running**. On the owner's phone that is not a corner case: his block log is mostly
 * `why=strict`, and the 2 Sep 2026 reports show the service being revived 67 times, each revive
 * reopening the window. `unreadyDecisions` had reached 8 and was climbing.
 *
 * **The asymmetry is [RuleSnapshot]'s, deliberately.** A stale snapshot can only over-block, and
 * only until Room answers milliseconds later. It cannot resurrect an ended session either:
 * [SessionClock.remaining] is anchored to both clocks and the boot counter, so an expired or
 * pre-reboot session still returns 0. And Strict cannot be cancelled early by design, so there is
 * no "he turned it off and the snapshot put it back" case to worry about.
 */
internal object StrictSnapshot {

    /** The five values [SessionClock.remaining] needs, and nothing else. */
    data class Session(
        val realtimeStart: Long = 0L,
        val realtimeEnd: Long = 0L,
        val wallStart: Long = 0L,
        val wallEnd: Long = 0L,
        val bootCount: Int = -1,
    )

    val NONE = Session()

    /** True when this describes a session at all, as opposed to the zeroes of "not told yet". */
    fun isSet(s: Session): Boolean = s.realtimeEnd > 0L || s.wallEnd > 0L

    /**
     * Which session to enforce right now.
     *
     * Once [loaded] is true the live values are the only truth and the snapshot is never consulted
     * again \u2014 otherwise a session that has genuinely ended would keep enforcing until the next
     * restart, which is the mirror of the bug this fixes.
     */
    fun sessionFor(loaded: Boolean, live: Session, snapshot: Session): Session =
        if (loaded) live else if (isSet(live)) live else snapshot
}
