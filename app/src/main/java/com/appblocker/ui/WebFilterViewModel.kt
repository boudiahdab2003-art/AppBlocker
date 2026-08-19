package com.appblocker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appblocker.data.AppRule
import com.appblocker.data.BlockedKeyword
import com.appblocker.data.BlockerDatabase
import com.appblocker.data.DeviceBoot
import com.appblocker.data.FocusState
import com.appblocker.data.QuickSession
import com.appblocker.data.SettingsStore
import com.appblocker.data.SiteWord
import com.appblocker.data.StrictEdits
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WebFilterViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = BlockerDatabase.get(app).blockedKeywordDao()
    private val ruleDao = BlockerDatabase.get(app).appRuleDao()
    private val focusDao = BlockerDatabase.get(app).focusDao()

    val keywords: StateFlow<List<String>> =
        dao.getAll()
            .map { list -> list.map { it.keyword } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The website words live right now because their app is blocked — the same answer the watcher
     * acts on ([StrictEdits.liveSiteWords]). A word this list already covers is one Strict Mode
     * can let go of, since removing it leaves every address it blocked still blocked.
     *
     * Re-derived on an `app_rules` or `focus_state` write only; the allowlist and pause settings
     * live in preferences and are not flows. That is sound rather than lucky: this value is only
     * *consulted* during a Strict session, and during one `enforcing` is unconditionally true and
     * the Blocklist/Allowlist chip is frozen, so neither input can change while it matters. And it
     * is never the guard — the real decision is re-taken against a fresh read in [setKeywords], so
     * a stale value here can only make a button look tappable that then politely declines.
     */
    val liveSiteWords: StateFlow<List<SiteWord>> =
        combine(ruleDao.getAll(), focusDao.get()) { rules, focus ->
            siteWordsFor(rules, strictRemaining(focus))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Commit a staged keyword list: insert new ones, delete removed ones.
     *
     * **Strict Mode is enforced here, not by the buttons that call this.** `BlockEditorScreen`
     * stages its list and diffs it on Save, so a word added on the other screen after that editor
     * was opened-and-edited reads to this function as a deletion — and a disabled bin cannot see
     * that coming. During a session a deletion goes through only when a live site rule already
     * blocks everything the word blocked. Insertions are never refused: adding a block is always
     * allowed (invariant 16).
     *
     * Everything is read fresh inside the coroutine rather than from the cached [keywords], which
     * can be five seconds stale, because the decision belongs at the write.
     */
    fun setKeywords(newList: List<String>) {
        val target = newList.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        viewModelScope.launch {
            val current = dao.getAll().first().map { it.keyword }.toSet()
            val remaining = strictRemaining(focusDao.get().first())
            val siteWords =
                if (remaining > 0L) siteWordsFor(ruleDao.getAll().first(), remaining)
                else emptyList()
            val deletable = StrictEdits.allowedDeletions(
                current, target, strict = remaining > 0L, siteWords = siteWords,
            )
            (target - current).forEach { dao.insert(BlockedKeyword(it)) }
            deletable.forEach { dao.delete(BlockedKeyword(it)) }
        }
    }

    /** Reads the three settings the derivation needs and hands them to the shared rule. */
    private fun siteWordsFor(rules: List<AppRule>, strictRemainingMs: Long): List<SiteWord> {
        val ctx = getApplication<Application>()
        return StrictEdits.liveSiteWords(
            rules,
            allowlistMode = SettingsStore.quickBlockAllowlist(ctx),
            enforcing = QuickSession.enforcing(
                strictRemainingMs,
                SettingsStore.updatePaused(ctx),
                QuickSession.state(ctx),
                SettingsStore.quickBlockPaused(ctx),
            ),
        )
    }

    /** The same reckoning [FocusViewModel] and the watcher use — never a third one. */
    private fun strictRemaining(state: FocusState?): Long =
        StrictEdits.strictRemaining(state, DeviceBoot.count(getApplication()))
}
