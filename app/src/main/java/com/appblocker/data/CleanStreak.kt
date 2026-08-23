package com.appblocker.data

import android.content.Context
import org.json.JSONArray

/**
 * How long it has been since the last relapse — and, when there is one, the record of the ones
 * before it.
 *
 * **Why this is not another counter.** Everything else the app measures is *phone use*: screen
 * minutes, blocked opens, unlocks, a 0–100 mood check-in that gets pruned after 35 days. None of
 * it is the thing the blocking is actually for. This is the only number in the app that is about
 * the person rather than the device, and it is the only one that must never be pruned, rounded or
 * quietly recalculated.
 *
 * **One count, not one per behaviour** — the owner's own choice. A single "clean since" that
 * covers all of it; there is nothing to be gained by splitting a slip into categories, and a
 * screen carrying two numbers invites treating one of them as the good one.
 *
 * **The arithmetic is pure and lives here.** Everything that can be got wrong — a clock that moved
 * backwards, a relapse back-dated before the streak it ends, a "best ever" that banks a run that
 * never started — is a plain function of its arguments, so `CleanStreakTest` can pin each one
 * without a device. The stored half below is a thin shell over SharedPreferences, matching
 * [MoodStore] and [Goals] rather than adding a table for four scalars.
 *
 * Stored on this phone and nowhere else: not in a bug report ([BugReport]'s context is a closed
 * allow-list of named keys), not in the profile report, and not in anything the AI Coach sends.
 */
object CleanStreak {

    private const val PREFS = "recovery"
    private const val KEY_STARTED = "started_at"
    private const val KEY_BEST = "best_ms"
    private const val KEY_RESETS = "resets"
    private const val KEY_UNDO_AT = "undo_at"
    private const val KEY_UNDO_STARTED = "undo_started_at"
    private const val KEY_UNDO_BEST = "undo_best_ms"

    /** Relapse timestamps kept. Far more than anyone will ever look at, and small enough to stay
     *  a single preference string — the history is a few hundred longs, not a log. */
    private const val KEEP_RESETS = 300

    /**
     * How long "I relapsed" can be taken back.
     *
     * **Undo is not a nicety here.** One mis-tap would destroy a forty-day count with nothing on
     * earth to get it back, and it is the most valuable number in the app. The confirm dialog is
     * the first defence; this is the second. A day is long enough to notice a mis-tap, and short
     * enough that the button is not still sitting there a week later offering to undo something
     * that really happened.
     */
    const val UNDO_WINDOW_MS = 24 * 60 * 60_000L
    const val UNDO_WINDOW_LABEL = "24 hours"

    /** A duration split for display. [days] is unbounded; the rest stay inside their unit. */
    data class Elapsed(val days: Long, val hours: Long, val minutes: Long, val seconds: Long)

    // ───────────────────────────────────────────────────────────── pure arithmetic

    /**
     * Milliseconds held so far.
     *
     * **Zero when the count has never been started, and never negative.** Both matter. A missing
     * start is 0 in the preferences, and `now - 0` is fifty-six years, which the screen would
     * cheerfully print. And a device clock that moves backwards — a timezone change, a DST hour,
     * a phone correcting itself after a flat battery — would otherwise produce a count that runs
     * backwards or shows a negative day.
     */
    fun elapsed(startedAt: Long, now: Long): Long =
        if (startedAt <= 0L) 0L else (now - startedAt).coerceAtLeast(0L)

    /** Split into days / hours / minutes / seconds — the owner asked for all four, ticking. */
    fun breakdown(ms: Long): Elapsed {
        val t = (ms / 1000L).coerceAtLeast(0L)
        return Elapsed(
            days = t / 86_400L,
            hours = (t % 86_400L) / 3600L,
            minutes = (t % 3600L) / 60L,
            seconds = t % 60L,
        )
    }

    /** The longest run so far, once a run of [finished] has ended. A run that never started banks
     *  nothing — otherwise a first-ever "I relapsed" would record a record of zero. */
    fun bankBest(best: Long, finished: Long): Long = maxOf(best, finished.coerceAtLeast(0L))

    /** A moment the user picked for the start, held to the possible: never in the future. */
    fun clampToNow(at: Long, now: Long): Long = at.coerceIn(0L, maxOf(0L, now))

    /**
     * A moment the user picked for a relapse. It cannot be in the future, and it cannot fall
     * before the run it ends began — a relapse dated before its own streak would bank a negative
     * run and leave the next count already running.
     */
    fun clampRelapse(at: Long, now: Long, startedAt: Long): Long {
        val ceiling = maxOf(0L, now)
        val floor = startedAt.coerceIn(0L, ceiling)
        return at.coerceIn(floor, ceiling)
    }

    /** Whether the last relapse can still be taken back. [undoAt] is 0 when there is nothing. */
    fun canUndo(undoAt: Long, now: Long): Boolean =
        undoAt > 0L && now >= undoAt && now - undoAt <= UNDO_WINDOW_MS

    // ───────────────────────────────────────────────────────────── stored state

    /** When the current run began, or 0 if the count has never been started. */
    fun startedAt(ctx: Context): Long = p(ctx).getLong(KEY_STARTED, 0L)

    fun isRunning(ctx: Context): Boolean = startedAt(ctx) > 0L

    fun elapsedMs(ctx: Context, now: Long = System.currentTimeMillis()): Long =
        elapsed(startedAt(ctx), now)

    fun bestMs(ctx: Context): Long = p(ctx).getLong(KEY_BEST, 0L)

    /** Start counting, or correct the start of a run already going. Records no relapse — this is
     *  "I actually started on Tuesday", not "it happened again". */
    fun setStart(ctx: Context, at: Long, now: Long = System.currentTimeMillis()) {
        p(ctx).edit()
            .putLong(KEY_STARTED, clampToNow(at, now))
            // An edited start is a different history from the one an undo would restore.
            .remove(KEY_UNDO_AT).remove(KEY_UNDO_STARTED).remove(KEY_UNDO_BEST)
            .apply()
    }

    /**
     * It happened. Banks the run that just ended, records the date, and starts the next one from
     * [at] — the moment it happened, not the moment it was admitted; the two are often a morning
     * apart.
     */
    fun relapse(ctx: Context, at: Long, now: Long = System.currentTimeMillis()) {
        val prefs = p(ctx)
        val previousStart = prefs.getLong(KEY_STARTED, 0L)
        val previousBest = prefs.getLong(KEY_BEST, 0L)
        val moment = clampRelapse(at, now, previousStart)
        val finished = elapsed(previousStart, moment)
        val history = (listOf(moment) + resets(ctx)).take(KEEP_RESETS)
        prefs.edit()
            .putLong(KEY_STARTED, moment)
            .putLong(KEY_BEST, bankBest(previousBest, finished))
            .putString(KEY_RESETS, JSONArray(history).toString())
            .putLong(KEY_UNDO_AT, now)
            .putLong(KEY_UNDO_STARTED, previousStart)
            .putLong(KEY_UNDO_BEST, previousBest)
            .apply()
    }

    /** When the last relapse was recorded, or 0 if there is nothing to take back. Exposed so the
     *  screen can read it once and let the pure [canUndo] decide on every tick. */
    fun undoAt(ctx: Context): Long = p(ctx).getLong(KEY_UNDO_AT, 0L)

    fun canUndo(ctx: Context, now: Long = System.currentTimeMillis()): Boolean =
        canUndo(undoAt(ctx), now)

    /** Take the last relapse back, exactly: the old start, the old record, and the date gone from
     *  the history. Restoring "never started" is a real outcome and is handled. */
    fun undo(ctx: Context, now: Long = System.currentTimeMillis()) {
        val prefs = p(ctx)
        if (!canUndo(prefs.getLong(KEY_UNDO_AT, 0L), now)) return
        val history = resets(ctx).drop(1)
        prefs.edit()
            .putLong(KEY_STARTED, prefs.getLong(KEY_UNDO_STARTED, 0L))
            .putLong(KEY_BEST, prefs.getLong(KEY_UNDO_BEST, 0L))
            .putString(KEY_RESETS, JSONArray(history).toString())
            .remove(KEY_UNDO_AT).remove(KEY_UNDO_STARTED).remove(KEY_UNDO_BEST)
            .apply()
    }

    /** Every recorded relapse, newest first. */
    fun resets(ctx: Context): List<Long> {
        val raw = p(ctx).getString(KEY_RESETS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getLong(it) }
        }.getOrDefault(emptyList())
    }

    /** The days a relapse was recorded on, for the journal's margin marker. */
    fun resetDays(ctx: Context): Set<Int> = resets(ctx).map { dayStampOf(it) }.toSet()

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
