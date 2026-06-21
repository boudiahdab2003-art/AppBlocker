package com.appblocker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appblocker.data.BlockerDatabase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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
}
