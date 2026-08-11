package com.appblocker.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FocusDao {

    @Query("SELECT * FROM focus_state WHERE id = 0")
    fun get(): Flow<FocusState?>

    @Upsert
    suspend fun set(state: FocusState)

    /**
     * Push the current session's deadline **later** by [delta] millis. The only sanctioned way to
     * change a running Strict session.
     *
     * **One statement, deliberately — not a read-modify-write.** This row has three other writers,
     * two of which write a fully zeroed [FocusState] the moment a session expires
     * ([com.appblocker.ui.FocusViewModel]'s ticker and the watcher's `focusClearRunnable`). Reading
     * the row in Kotlin, adding to it and writing it back races both of them, and the race is not
     * symmetrical: losing it one way costs an extension the user asked for, losing it the other way
     * **resurrects a session that already ended** — precisely the failure the zeroing exists to
     * prevent. Doing the arithmetic inside SQLite removes the window rather than narrowing it.
     *
     * Every clause is load-bearing:
     * - **`+ :delta`, never `= :newEnd`.** The deadline can only move forward. An absolute end
     *   computed by a caller is the shape a shortening bug takes; this one cannot express it.
     * - **`:delta > 0`.** A negative "extension" is refused at the only place that could apply one.
     * - **`endTimeMillis > 0 OR realtimeEndMillis > 0`.** A zeroed row stays zeroed, so this can
     *   never bring a finished session back however it races a clear.
     * - **Both end fields together.** [SessionClock] uses the monotonic deadline within a boot and
     *   the wall-clock one after a reboot; bumping only one silently does nothing on the other path.
     *
     * The start anchors and `bootCount` are deliberately left alone. `SessionClock`'s post-reboot
     * path caps remaining at `endTimeMillis - startTimeMillis`, so raising the end raises that cap
     * by exactly the amount added and the wrong-clock guard keeps working; re-anchoring the start
     * would shrink the cap and could *shorten* the session after a reboot.
     * [GuardedDeadline.remaining] shifts a deadline the same way, for the same reason.
     */
    @Query(
        """
        UPDATE focus_state
           SET endTimeMillis = endTimeMillis + :delta,
               realtimeEndMillis = realtimeEndMillis + :delta
         WHERE id = 0
           AND :delta > 0
           AND (endTimeMillis > 0 OR realtimeEndMillis > 0)
        """
    )
    suspend fun extend(delta: Long)

    // There is deliberately no query here that can move a deadline EARLIER, or set one to an
    // absolute value. Strict Mode's whole promise is that a session cannot be cut short, and the
    // cheapest way to break that promise would be a general-purpose "update the deadline" method
    // that some future caller passes a smaller number to. [extend] adds; nothing subtracts.

    // There was a clearStrictSessionCreatedBefore(currentVersion) here, used when an app update
    // ended a running Strict session. Updates no longer do that — the session survives and
    // suppresses the after-update pause instead (see UpdatePause) — so the query is gone rather
    // than left available for something to call by accident. FocusState.appVersionCode is kept:
    // it records which version created a session, which is worth having regardless.
}
