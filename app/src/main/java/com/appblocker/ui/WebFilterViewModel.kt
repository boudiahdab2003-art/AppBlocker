package com.appblocker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appblocker.data.BlockedKeyword
import com.appblocker.data.BlockerDatabase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WebFilterViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = BlockerDatabase.get(app).blockedKeywordDao()

    val keywords: StateFlow<List<String>> =
        dao.getAll()
            .map { list -> list.map { it.keyword } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Commit a staged keyword list: insert new ones, delete removed ones. */
    fun setKeywords(newList: List<String>) {
        val target = newList.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        viewModelScope.launch {
            val current = keywords.value.toSet()
            (target - current).forEach { dao.insert(BlockedKeyword(it)) }
            (current - target).forEach { dao.delete(BlockedKeyword(it)) }
        }
    }
}
