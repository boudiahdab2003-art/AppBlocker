package com.appblocker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appblocker.data.BlockedKeyword
import com.appblocker.data.BlockerDatabase
import com.appblocker.data.Schedule
import com.appblocker.data.ScheduleType
import com.appblocker.data.TemplateStore
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

    /**
     * Apply a one-tap preset: create a Time schedule for its apps (so they block within the
     * template's window), add its keywords globally, and set the adult filter if asked.
     */
    fun applyTemplate(t: Template) {
        viewModelScope.launch {
            // Use the user's chosen apps for this template if they've customised it.
            val packages = TemplateStore.packagesFor(getApplication(), t.id) ?: t.packages.map { it.first }
            if (packages.isNotEmpty()) {
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
                        packages = packages,
                    )
                )
            }
            t.keywords.forEach { db.blockedKeywordDao().insert(BlockedKeyword(it.lowercase().trim())) }
            // Switch on the template's Quick Block extra options (additive — never turns any off).
            t.effectiveOptions(getApplication()).forEach { it.turnOn(getApplication()) }
        }
    }
}
