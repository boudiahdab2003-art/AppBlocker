package com.appblocker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appblocker.data.AppRule
import com.appblocker.data.BlockMode
import com.appblocker.data.BlockedKeyword
import com.appblocker.data.BlockerDatabase
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

    /** Apply a one-tap preset: block its apps + keywords, set the adult filter if asked. */
    fun applyTemplate(t: Template) {
        viewModelScope.launch {
            t.packages.forEach { (pkg, label) ->
                db.appRuleDao().upsert(
                    AppRule(packageName = pkg, appLabel = label, isBlocked = true, mode = BlockMode.HARD)
                )
            }
            t.keywords.forEach { db.blockedKeywordDao().insert(BlockedKeyword(it.lowercase().trim())) }
            if (t.enableAdult) SettingsStore.setBlockAdult(getApplication(), true)
        }
    }
}
