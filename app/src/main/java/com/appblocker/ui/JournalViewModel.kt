package com.appblocker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appblocker.data.BlockerDatabase
import com.appblocker.data.JournalEntry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class JournalViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = BlockerDatabase.get(app).journalDao()

    val entries: StateFlow<List<JournalEntry>> =
        dao.getAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The text already on [day], or "" if the day is blank.
     *
     * **Read from the DAO rather than picked out of [entries].** That flow is empty for the first
     * frame or two, so a screen that filtered it would show an existing entry as blank — and then
     * autosave the blank over it. One suspend read is deterministic.
     */
    suspend fun load(day: Int): String = dao.find(day)?.text ?: ""

    /**
     * Writes [text] to [day], or removes the day entirely if nothing is left on it.
     *
     * **Blank means delete, deliberately.** A day opened by accident, or one whose writing was
     * selected and cleared, would otherwise sit in the list forever as a dated entry with nothing
     * in it — which reads as a day that was written about and then lost.
     *
     * `createdAt` survives an edit; `updatedAt` moves. Neither is shown anywhere yet, and both are
     * here because a diary that cannot say when it was written is a diary that cannot later be
     * trusted about its own order.
     */
    fun save(day: Int, text: String) {
        val clean = text.trim()
        viewModelScope.launch {
            if (clean.isEmpty()) {
                dao.deleteDay(day)
                return@launch
            }
            val now = System.currentTimeMillis()
            val existing = dao.find(day)
            dao.upsert(
                JournalEntry(
                    day = day,
                    text = clean,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                )
            )
        }
    }

    fun delete(day: Int) {
        viewModelScope.launch { dao.deleteDay(day) }
    }
}
