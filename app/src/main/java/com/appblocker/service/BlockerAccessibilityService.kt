package com.appblocker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.appblocker.data.AppRule
import com.appblocker.data.BlockMode
import com.appblocker.data.BlockerDatabase
import com.appblocker.ui.BlockScreenActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * The watcher (M2 core, extended in M4). On each foreground-app change it decides
 * whether to block, based on the app's rule:
 *  - HARD  -> always blocked
 *  - LIMIT -> blocked once today's usage passes the daily limit
 *  - any blocklisted app -> blocked while a Focus session is active
 */
class BlockerAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var rules: Map<String, AppRule> = emptyMap()
    @Volatile private var focusEndMillis: Long = 0L
    private var lastBlockedPkg: String? = null
    private var lastBlockAt: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        val db = BlockerDatabase.get(applicationContext)
        combine(db.appRuleDao().getAll(), db.focusDao().get()) { ruleList, focus ->
            rules = ruleList.associateBy { it.packageName }
            focusEndMillis = focus?.endTimeMillis ?: 0L
        }.launchIn(scope)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        if (!shouldBlock(pkg)) {
            lastBlockedPkg = null
            return
        }

        val now = System.currentTimeMillis()
        if (pkg == lastBlockedPkg && now - lastBlockAt < 1500) return
        lastBlockedPkg = pkg
        lastBlockAt = now

        startActivity(
            Intent(this, BlockScreenActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(BlockScreenActivity.EXTRA_PACKAGE, pkg)
            }
        )
    }

    private fun shouldBlock(pkg: String): Boolean {
        val rule = rules[pkg] ?: return false
        if (!rule.isBlocked) return false
        // While focusing, everything on the blocklist is locked.
        if (System.currentTimeMillis() < focusEndMillis) return true
        return when (rule.mode) {
            BlockMode.HARD -> true
            BlockMode.LIMIT ->
                rule.dailyLimitMinutes >= 0 &&
                    UsageTracker.usedMinutesToday(this, pkg) >= rule.dailyLimitMinutes
            BlockMode.SCHEDULE -> true // schedule UI not built yet; treat as always-on
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
