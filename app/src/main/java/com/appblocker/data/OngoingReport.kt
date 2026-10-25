package com.appblocker.data

/**
 * **Unprotected time is reported while it lasts, not only once it is over** (invariant 91).
 *
 * Every stoppage report was filed by [OutageLog.end] — when blocking came BACK. A stoppage the
 * silent repair could not end therefore said nothing for as long as it lasted, and the after-update
 * pause ([PauseLog]) said nothing at all. On 24 Oct 2026 he used Instagram from after work until
 * 2 am, unblocked, and the reports inbox had not heard from his main phone in two days. Silence was
 * the one reading both failures were guaranteed to produce.
 *
 * So once an open episode has lasted long enough, **with the phone in use**, one report is filed
 * mid-episode. The use test is what keeps this from being noise: a stoppage overnight on a table
 * costs nothing and is already in the list when it ends. Once per episode, by its dedupe key
 * (`ongoing:<kind>:<start>`) — the queue's own memory, not a second flag to forget to clear.
 *
 * Pure, so the thresholds are tested.
 */
object OngoingReport {

    /** What is still going on. Values are our own literals and go into the report as-is. */
    object Kind {
        /** Switched on, the watcher gone, and the repair has not brought it back. */
        const val STOPPED = "stopped"

        /** Paused after an update, waiting for a Reactivate tap nobody has made. */
        const val PAUSED = "paused"
    }

    /** A stoppage is given this long to be repaired first: the repair normally ends one in minutes. */
    const val STOPPED_AFTER_MS = 30 * 60_000L

    /** …and must have cost this much real use before it is worth an issue of its own. */
    const val STOPPED_USED_MIN = 10

    /** A pause is not something the app can repair, so only the use matters, from a short floor. */
    const val PAUSED_AFTER_MS = 15 * 60_000L

    const val PAUSED_USED_MIN = 15

    /**
     * Whether an episode open for [openMs], with [usedMin] minutes of use since it began, is due its
     * mid-episode report. A null or negative [usedMin] means "cannot tell" and is never due — the
     * same rule the watchdog's stale arm keeps: a guess about use must not file a report.
     */
    fun due(kind: String, openMs: Long, usedMin: Int?): Boolean {
        if (usedMin == null || usedMin < 0 || openMs < 0L) return false
        return when (kind) {
            Kind.STOPPED -> openMs >= STOPPED_AFTER_MS && usedMin >= STOPPED_USED_MIN
            Kind.PAUSED -> openMs >= PAUSED_AFTER_MS && usedMin >= PAUSED_USED_MIN
            else -> false
        }
    }

    /** Whether it is even worth walking the usage stream to ask [due] — the time half alone. */
    fun oldEnough(kind: String, openMs: Long): Boolean = when (kind) {
        Kind.STOPPED -> openMs >= STOPPED_AFTER_MS
        Kind.PAUSED -> openMs >= PAUSED_AFTER_MS
        else -> false
    }

    /** The queue key, one per episode. [BugReport.dedupeKey] spells it through this. */
    fun key(kind: String, startedAtSec: String): String = "ongoing:$kind:$startedAtSec"
}
