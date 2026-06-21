package com.appblocker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appblocker.data.BlockerDatabase
import com.appblocker.data.Schedule
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ScheduleViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = BlockerDatabase.get(app).scheduleDao()

    val schedules: StateFlow<List<Schedule>> =
        dao.getAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(schedule: Schedule) {
        viewModelScope.launch { dao.upsert(schedule) }
    }

    fun setEnabled(schedule: Schedule, enabled: Boolean) {
        viewModelScope.launch { dao.upsert(schedule.copy(enabled = enabled)) }
    }

    fun delete(schedule: Schedule) {
        viewModelScope.launch { dao.delete(schedule) }
    }
}
