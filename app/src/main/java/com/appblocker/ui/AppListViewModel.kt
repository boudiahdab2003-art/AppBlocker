package com.appblocker.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appblocker.data.AppCategories
import com.appblocker.data.AppCategory
import com.appblocker.data.AppRule
import com.appblocker.data.BlockMode
import com.appblocker.data.BlockerDatabase
import com.appblocker.data.DeviceBoot
import com.appblocker.data.InstalledAppsRepository
import com.appblocker.data.POPULAR_APPS
import com.appblocker.data.SettingsStore
import com.appblocker.data.StrictEdits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row in the picker: an app + whether/how it's blocked. [installed] is false for a
 *  curated popular app the user can pre-block before installing it. */
data class AppItem(
    val packageName: String,
    val label: String,
    val icon: Bitmap?,
    val isBlocked: Boolean,
    // Allowlist-mode selection, kept independently of [isBlocked].
    val isAllowed: Boolean = false,
    val mode: BlockMode = BlockMode.HARD,
    val dailyLimitMinutes: Int = -1,
    val installed: Boolean = true,
    // Brand colour for a coloured-initial badge when there's no real icon (not-installed apps).
    val accentColor: Long? = null,
    val category: AppCategory = AppCategory.OTHER,
)

class AppListViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = BlockerDatabase.get(app).appRuleDao()
    private val focusDao = BlockerDatabase.get(app).focusDao()

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
            val installedItems = list.map { app ->
                val r = byPkg[app.packageName]
                AppItem(
                    packageName = app.packageName,
                    label = app.label,
                    icon = app.icon,
                    isBlocked = r?.isBlocked == true,
                    isAllowed = r?.isAllowed == true,
                    mode = r?.mode ?: BlockMode.HARD,
                    dailyLimitMinutes = r?.dailyLimitMinutes ?: -1,
                    installed = true,
                    category = app.category,
                )
            }
            // Curated popular apps that aren't installed yet, so they can be pre-blocked.
            val installedPkgs = list.mapTo(HashSet()) { it.packageName }
            val preBlockItems = POPULAR_APPS
                .filter { it.packageName !in installedPkgs }
                .map { p ->
                    val r = byPkg[p.packageName]
                    AppItem(
                        packageName = p.packageName,
                        label = p.label,
                        icon = null,
                        isBlocked = r?.isBlocked == true,
                        isAllowed = r?.isAllowed == true,
                        mode = r?.mode ?: BlockMode.HARD,
                        dailyLimitMinutes = r?.dailyLimitMinutes ?: -1,
                        installed = false,
                        accentColor = p.color,
                        category = AppCategories.categoryOf(p.packageName),
                    )
                }
            (installedItems + preBlockItems).sortedWith(
                compareByDescending<AppItem> { it.installed }
                    .thenByDescending { AppCategories.weightOf(it.packageName) + (usageMap[it.packageName] ?: 0) }
                    .thenBy { it.label.lowercase() }
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

    /**
     * Commit both Quick Block selections at once: an app is blocked iff it's in [blocked] and
     * allowed iff it's in [allowed]. Writing both fields in a single row per app keeps the two
     * selections independent and avoids a clobber when the same app is toggled in both lists.
     *
     * **Strict Mode is enforced here, not only by the checkbox that refuses to un-tick.** The
     * editor stages its selection and stops re-syncing once it has been touched, so an app blocked
     * elsewhere after it was opened — by `NewAppWatcher`, by a template, by the other screen —
     * reads to this function as "the user un-ticked this", and an upsert that clears `isBlocked`
     * removes protection exactly as effectively as a delete. During a session the weakening
     * direction of whichever list is enforcing is put back
     * ([StrictEdits.allowedSelections]); tightening always goes through.
     */
    fun commitQuickBlock(blocked: Set<String>, allowed: Set<String>) {
        viewModelScope.launch {
            val live = apps.value
            val remaining = StrictEdits.strictRemaining(
                focusDao.get().first(), DeviceBoot.count(getApplication()),
            )
            val (safeBlocked, safeAllowed) = StrictEdits.allowedSelections(
                currentBlocked = live.filter { it.isBlocked }.mapTo(mutableSetOf()) { it.packageName },
                currentAllowed = live.filter { it.isAllowed }.mapTo(mutableSetOf()) { it.packageName },
                targetBlocked = blocked,
                targetAllowed = allowed,
                strict = remaining > 0L,
                allowlistMode = SettingsStore.quickBlockAllowlist(getApplication()),
            )
            val changed = live.mapNotNull { a ->
                val shouldBlock = a.packageName in safeBlocked
                val shouldAllow = a.packageName in safeAllowed
                if (a.isBlocked == shouldBlock && a.isAllowed == shouldAllow) null
                else AppRule(
                    packageName = a.packageName,
                    appLabel = a.label,
                    isBlocked = shouldBlock,
                    isAllowed = shouldAllow,
                    mode = a.mode,
                    dailyLimitMinutes = a.dailyLimitMinutes,
                )
            }
            if (changed.isNotEmpty()) dao.upsertAll(changed)
        }
    }
}
