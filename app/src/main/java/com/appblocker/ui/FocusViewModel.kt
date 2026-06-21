package com.appblocker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appblocker.data.BlockerDatabase
import com.appblocker.data.FocusState
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

    /** Milliseconds left in the current focus session (0 if none active). */
    val remainingMillis: StateFlow<Long> =
        combine(ticker, dao.get()) { now, state ->
            ((state?.endTimeMillis ?: 0L) - now).coerceAtLeast(0L)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val isActive: StateFlow<Boolean> =
        remainingMillis.map { it > 0L }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Start an un-stoppable focus session. There is intentionally no "stop". */
    fun start(minutes: Int) {
        viewModelScope.launch {
            dao.set(FocusState(0, System.currentTimeMillis() + minutes * 60_000L))
        }
    }
}
