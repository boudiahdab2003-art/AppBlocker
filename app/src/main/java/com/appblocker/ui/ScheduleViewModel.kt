package com.appblocker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appblocker.data.BlockerDatabase
import com.appblocker.data.SavedPlace
import com.appblocker.data.Schedule
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ScheduleViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = BlockerDatabase.get(app).scheduleDao()
    private val placeDao = BlockerDatabase.get(app).savedPlaceDao()

    val schedules: StateFlow<List<Schedule>> =
        dao.getAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Reusable named locations, shown in the Location editor. */
    val savedPlaces: StateFlow<List<SavedPlace>> =
        placeDao.getAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(schedule: Schedule) {
        viewModelScope.launch { dao.upsert(schedule) }
    }

    fun setEnabled(schedule: Schedule, enabled: Boolean) {
        viewModelScope.launch { dao.upsert(schedule.copy(enabled = enabled)) }
    }

    fun delete(schedule: Schedule) {
        viewModelScope.launch { dao.delete(schedule) }
    }

    /** Saves a named place; re-using an existing name updates it instead of duplicating. */
    fun savePlace(name: String, lat: Double, lng: Double) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val existing = placeDao.findByName(clean)
            placeDao.upsert(
                (existing ?: SavedPlace(name = clean, latitude = lat, longitude = lng))
                    .copy(name = clean, latitude = lat, longitude = lng)
            )
        }
    }

    fun deletePlace(place: SavedPlace) {
        viewModelScope.launch { placeDao.delete(place) }
    }
}
