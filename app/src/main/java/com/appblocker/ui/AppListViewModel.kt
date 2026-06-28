package com.appblocker.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appblocker.data.AppCategories
import com.appblocker.data.AppRule
import com.appblocker.data.BlockMode
import com.appblocker.data.BlockerDatabase
import com.appblocker.data.InstalledAppsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row in the picker: an installed app + whether/how it's blocked. */
data class AppItem(
    val packageName: String,
    val label: String,
    val icon: Bitmap?,
    val isBlocked: Boolean,
    val mode: BlockMode = BlockMode.HARD,
    val dailyLimitMinutes: Int = -1,
)

class AppListViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = BlockerDatabase.get(app).appRuleDao()

    val loading = MutableStateFlow(true)

    /**
     * Installed launchable apps merged with saved block state, ordered most-worth-blocking
     * first: distraction category (Social/Video high, productivity low) plus real usage
     * time, alphabetical as the final tiebreaker. Blocked state is NOT part of the score,
     * so rows never jump when you tap a checkbox. Usage is warmed at app launch (in
     * [InstalledAppsRepository]) so the order is final by the time an editor opens.
     */
    val apps: StateFlow<List<AppItem>> =
        combine(InstalledAppsRepository.apps, dao.getAll(), InstalledAppsRepository.usage) { list, rules, usageMap ->
            val byPkg = rules.associateBy { it.packageName }
            list.map { app ->
                val r = byPkg[app.packageName]
                AppItem(
                    packageName = app.packageName,
                    label = app.label,
                    icon = app.icon,
                    isBlocked = r?.isBlocked == true,
                    mode = r?.mode ?: BlockMode.HARD,
                    dailyLimitMinutes = r?.dailyLimitMinutes ?: -1,
                )
            }.sortedWith(
                compareByDescending<AppItem> {
                    AppCategories.weightOf(it.packageName) + (usageMap[it.packageName] ?: 0)
                }.thenBy { it.label.lowercase() }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            InstalledAppsRepository.ensureLoaded(getApplication())
            loading.value = false
        }
        // Refresh the usage snapshot in the background so the order stays current; the
        // launch-warmed value is already present, so this never delays the first paint.
        InstalledAppsRepository.refreshUsage(getApplication())
    }

    /** Commit a staged Quick Block selection: block apps in [selected], unblock the rest. */
    fun commitBlocked(selected: Set<String>) {
        viewModelScope.launch {
            val changed = apps.value.mapNotNull { a ->
                val shouldBlock = a.packageName in selected
                if (a.isBlocked == shouldBlock) null
                else AppRule(
                    packageName = a.packageName,
                    appLabel = a.label,
                    isBlocked = shouldBlock,
                    mode = a.mode,
                    dailyLimitMinutes = a.dailyLimitMinutes,
                )
            }
            if (changed.isNotEmpty()) dao.upsertAll(changed)
        }
    }
}
