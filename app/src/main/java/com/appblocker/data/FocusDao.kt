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

    // There was a clearStrictSessionCreatedBefore(currentVersion) here, used when an app update
    // ended a running Strict session. Updates no longer do that — the session survives and
    // suppresses the after-update pause instead (see UpdatePause) — so the query is gone rather
    // than left available for something to call by accident. FocusState.appVersionCode is kept:
    // it records which version created a session, which is worth having regardless.
}
