package com.appblocker.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appblocker.data.BlockerDatabase
import com.appblocker.data.AppVersion
import com.appblocker.data.DeviceBoot
import com.appblocker.data.FocusState
import com.appblocker.data.SessionClock
import com.appblocker.data.StatsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FocusViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = BlockerDatabase.get(app).focusDao()

    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1000)
        }
    }

    // Prevents the 1s ticker from re-issuing the expiry-clear write while the first
    // one is still in flight (the flow re-emits the zeroed row once it lands).
    @Volatile private var clearing = false

    /** Milliseconds left in the current focus session (0 if none active). */
    val remainingMillis: StateFlow<Long> =
        combine(ticker, dao.get()) { _, state ->
            if (state == null) 0L
            else {
                val remaining = SessionClock.remaining(
                    state.realtimeStartMillis, state.realtimeEndMillis,
                    state.startTimeMillis, state.endTimeMillis,
                    state.bootCount, DeviceBoot.count(getApplication()),
                )
                // Session over: zero the row so a stale deadline can never be resurrected
                // by a wrong clock after a reboot. Only ever fires when remaining == 0,
                // so an active session stays un-stoppable.
                val rowNotEmpty = state.endTimeMillis > 0L || state.realtimeEndMillis > 0L
                if (remaining <= 0L && rowNotEmpty && !clearing) {
                    clearing = true
                    viewModelScope.launch {
                        dao.set(FocusState(id = 0))
                        clearing = false
                    }
                }
                remaining
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val isActive: StateFlow<Boolean> =
        remainingMillis.map { it > 0L }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Start an un-stoppable Strict Mode session. There is intentionally no "stop". */
    fun start(minutes: Int) {
        StatsStore.addStrictMinutes(getApplication(), minutes)
        val duration = minutes * 60_000L
        val nowRt = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        viewModelScope.launch {
            dao.set(
                FocusState(
                    id = 0,
                    endTimeMillis = nowWall + duration,
                    realtimeStartMillis = nowRt,
                    realtimeEndMillis = nowRt + duration,
                    startTimeMillis = nowWall,
                    bootCount = DeviceBoot.count(getApplication()),
                    appVersionCode = AppVersion.code(getApplication()),
                )
            )
        }
    }
}
