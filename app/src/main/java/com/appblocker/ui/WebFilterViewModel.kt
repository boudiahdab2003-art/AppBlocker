package com.appblocker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appblocker.data.BlockedKeyword
import com.appblocker.data.BlockerDatabase
import com.appblocker.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WebFilterViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = BlockerDatabase.get(app).blockedKeywordDao()
    private val context = app

    val keywords: StateFlow<List<String>> =
        dao.getAll()
            .map { list -> list.map { it.keyword } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val blockAdult = MutableStateFlow(SettingsStore.blockAdult(app))

    fun setBlockAdult(value: Boolean) {
        SettingsStore.setBlockAdult(context, value)
        blockAdult.value = value
    }

    fun addKeyword(raw: String) {
        val k = raw.trim().lowercase()
        if (k.isEmpty()) return
        viewModelScope.launch { dao.insert(BlockedKeyword(k)) }
    }

    fun removeKeyword(keyword: String) {
        viewModelScope.launch { dao.delete(BlockedKeyword(keyword)) }
    }

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
