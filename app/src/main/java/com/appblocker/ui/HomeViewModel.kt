package com.appblocker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appblocker.data.BlockedKeyword
import com.appblocker.data.BlockerDatabase
import com.appblocker.data.Schedule
import com.appblocker.data.ScheduleType
import com.appblocker.data.SettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val db = BlockerDatabase.get(app)

    val appsBlocked: StateFlow<Int> =
        db.appRuleDao().getAll()
            .map { list -> list.count { it.isBlocked } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val keywordCount: StateFlow<Int> =
        db.blockedKeywordDao().getAll()
            .map { it.size }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Sets used to tell whether a template is already fully applied. */
    val blockedPackages: StateFlow<Set<String>> =
        db.appRuleDao().getBlockedPackages()
            .map { it.toSet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val keywordSet: StateFlow<Set<String>> =
        db.blockedKeywordDao().getAll()
            .map { list -> list.map { it.keyword }.toSet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /**
     * Apply a one-tap preset: create a Time schedule for its apps (so they block within the
     * template's window), add its keywords globally, and set the adult filter if asked.
     */
    fun applyTemplate(t: Template) {
        viewModelScope.launch {
            if (t.packages.isNotEmpty()) {
                val existingId = db.scheduleDao().findByName(t.title)?.id ?: 0L
                db.scheduleDao().upsert(
                    Schedule(
                        id = existingId,
                        name = t.title,
                        type = ScheduleType.TIME,
                        enabled = true,
                        startMinutes = t.startMinutes,
                        endMinutes = t.endMinutes,
                        daysMask = t.daysMask,
                        packages = t.packages.map { it.first },
                    )
                )
            }
            t.keywords.forEach { db.blockedKeywordDao().insert(BlockedKeyword(it.lowercase().trim())) }
            if (t.enableAdult) SettingsStore.setBlockAdult(getApplication(), true)
        }
    }
}
