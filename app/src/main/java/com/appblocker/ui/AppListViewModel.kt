package com.appblocker.ui

import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appblocker.data.AppRule
import com.appblocker.data.BlockerDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row in the picker: an installed app + whether it's blocked. */
data class AppItem(
    val packageName: String,
    val label: String,
    val icon: Bitmap?,
    val isBlocked: Boolean,
)

private data class InstalledApp(val packageName: String, val label: String, val icon: Bitmap?)

class AppListViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = BlockerDatabase.get(app).appRuleDao()
    private val pm: PackageManager = app.packageManager

    private val installed = MutableStateFlow<List<InstalledApp>>(emptyList())
    val loading = MutableStateFlow(true)

    /** Installed launchable apps merged with saved block state, sorted by name. */
    val apps: StateFlow<List<AppItem>> =
        combine(installed, dao.getAll()) { list, rules ->
            val blocked = rules.filter { it.isBlocked }.map { it.packageName }.toSet()
            list.map { AppItem(it.packageName, it.label, it.icon, it.packageName in blocked) }
                .sortedBy { it.label.lowercase() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            installed.value = withContext(Dispatchers.IO) { loadLaunchableApps() }
            loading.value = false
        }
    }

    private fun loadLaunchableApps(): List<InstalledApp> =
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .filter { it.packageName != "com.appblocker" }
            .map { info ->
                val icon = runCatching {
                    pm.getApplicationIcon(info.packageName).toBitmap(96, 96)
                }.getOrNull()
                InstalledApp(info.packageName, pm.getApplicationLabel(info).toString(), icon)
            }
            .toList()

    fun toggle(item: AppItem) {
        viewModelScope.launch {
            dao.upsert(
                AppRule(
                    packageName = item.packageName,
                    appLabel = item.label,
                    isBlocked = !item.isBlocked,
                )
            )
        }
    }
}
