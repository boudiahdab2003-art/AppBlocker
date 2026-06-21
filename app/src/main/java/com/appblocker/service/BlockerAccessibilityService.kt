package com.appblocker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.appblocker.data.AppRule
import com.appblocker.data.BlockMode
import com.appblocker.data.BlockerDatabase
import com.appblocker.data.SettingsStore
import com.appblocker.ui.BlockScreenActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn

/**
 * The watcher. Two jobs:
 *  - App blocking (M2/M4): on a foreground-app change, block per the app's rule.
 *  - Web/keyword filtering (Phase 2): on content/text changes, read the on-screen
 *    web address / search text and block adult sites or the user's keywords.
 */
class BlockerAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val filter by lazy { WebContentFilter.get(applicationContext) }

    @Volatile private var rules: Map<String, AppRule> = emptyMap()
    @Volatile private var focusEndMillis: Long = 0L
    @Volatile private var userKeywords: List<String> = emptyList()

    private var lastBlockedPkg: String? = null
    private var lastBlockAt: Long = 0L
    private var lastWebScanAt: Long = 0L
    private var lastWebText: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val db = BlockerDatabase.get(applicationContext)
        combine(
            db.appRuleDao().getAll(),
            db.focusDao().get(),
            db.blockedKeywordDao().getAll(),
        ) { ruleList, focus, keywords ->
            rules = ruleList.associateBy { it.packageName }
            focusEndMillis = focus?.endTimeMillis ?: 0L
            userKeywords = keywords.map { it.keyword }
        }.launchIn(scope)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString()
        if (pkg == packageName) return // never act on ourselves

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg != null) handleAppBlock(pkg)
                scanWebContent()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> scanWebContent()
        }
    }

    // --- App blocking (unchanged behaviour) ---

    private fun handleAppBlock(pkg: String) {
        if (!shouldBlock(pkg)) {
            lastBlockedPkg = null
            return
        }
        val now = System.currentTimeMillis()
        if (pkg == lastBlockedPkg && now - lastBlockAt < 1500) return
        lastBlockedPkg = pkg
        lastBlockAt = now
        showBlockScreen(title = "Blocked", message = null)
    }

    private fun shouldBlock(pkg: String): Boolean {
        val rule = rules[pkg] ?: return false
        if (!rule.isBlocked) return false
        if (System.currentTimeMillis() < focusEndMillis) return true
        return when (rule.mode) {
            BlockMode.HARD -> true
            BlockMode.LIMIT ->
                rule.dailyLimitMinutes >= 0 &&
                    UsageTracker.usedMinutesToday(this, pkg) >= rule.dailyLimitMinutes
            BlockMode.SCHEDULE -> true
        }
    }

    // --- Web / keyword filtering ---

    private fun scanWebContent() {
        val now = System.currentTimeMillis()
        if (now - lastWebScanAt < 400) return
        lastWebScanAt = now

        val text = extractVisibleText()
        if (text.isBlank()) return
        if (text == lastWebText) return

        val hit = filter.check(text, userKeywords, SettingsStore.blockAdult(applicationContext))
        if (hit == null) {
            lastWebText = null
            return
        }
        lastWebText = text
        // Leave the offending page, then cover it with the block screen.
        performGlobalAction(GLOBAL_ACTION_BACK)
        showBlockScreen(title = hit.title, message = hit.message)
    }

    /** Collects on-screen text (URL bar, search fields, page) — capped for battery. */
    private fun extractVisibleText(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 200 && sb.length < 2000) {
            val node = queue.removeFirst()
            visited++
            node.text?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
            node.contentDescription?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return sb.toString()
    }

    private fun showBlockScreen(title: String, message: String?) {
        startActivity(
            Intent(this, BlockScreenActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(BlockScreenActivity.EXTRA_TITLE, title)
                if (message != null) putExtra(BlockScreenActivity.EXTRA_MESSAGE, message)
            }
        )
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
