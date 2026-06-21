package com.appblocker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.appblocker.data.BlockerDatabase
import com.appblocker.ui.BlockScreenActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * The watcher (M2 core). Listens for the foreground app changing; if the new
 * app is on the blocked list, it bounces the user home and shows the block screen.
 */
class BlockerAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var blocked: Set<String> = emptySet()
    private var lastBlockedPkg: String? = null
    private var lastBlockAt: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Keep the blocked-package set live from the database.
        BlockerDatabase.get(applicationContext).appRuleDao()
            .getBlockedPackages()
            .onEach { blocked = it.toSet() }
            .launchIn(scope)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return // never block ourselves

        if (pkg !in blocked) {
            lastBlockedPkg = null
            return
        }

        // Debounce: don't re-fire repeatedly for the same blocked app.
        val now = System.currentTimeMillis()
        if (pkg == lastBlockedPkg && now - lastBlockAt < 1500) return
        lastBlockedPkg = pkg
        lastBlockAt = now

        performGlobalAction(GLOBAL_ACTION_HOME)
        startActivity(
            Intent(this, BlockScreenActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(BlockScreenActivity.EXTRA_PACKAGE, pkg)
            }
        )
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
