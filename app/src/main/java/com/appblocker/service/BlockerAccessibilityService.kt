package com.appblocker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.appblocker.data.AppRule
import com.appblocker.data.AttemptCounter
import com.appblocker.data.BlockMode
import com.appblocker.data.BlockerDatabase
import com.appblocker.data.Schedule
import com.appblocker.data.ScheduleType
import com.appblocker.data.SettingsStore
import java.util.Calendar
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
    @Volatile private var schedules: List<Schedule> = emptyList()

    private var lastBlockedPkg: String? = null
    private var lastBlockAt: Long = 0L
    private var lastWebText: String? = null

    // Debounced web scan: collapse the page-load event burst into one scan that runs
    // after events stop, so the *settled* page (when its text finally exists) is scanned.
    private val handler = Handler(Looper.getMainLooper())
    private val webScanRunnable = Runnable { scanWebContent() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val db = BlockerDatabase.get(applicationContext)
        combine(
            db.appRuleDao().getAll(),
            db.focusDao().get(),
            db.blockedKeywordDao().getAll(),
            db.scheduleDao().getAll(),
        ) { ruleList, focus, keywords, scheduleList ->
            rules = ruleList.associateBy { it.packageName }
            focusEndMillis = focus?.endTimeMillis ?: 0L
            userKeywords = keywords.map { it.keyword }
            schedules = scheduleList
        }.launchIn(scope)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString()
        if (pkg == packageName) return // never act on ourselves

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg != null) handleAppBlock(pkg)
                lastWebText = null // new page/app: force a fresh re-check
                scheduleWebScan()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> scheduleWebScan()
        }
    }

    /** Debounce: run the scan ~600ms after the last event, i.e. once the page settles. */
    private fun scheduleWebScan() {
        handler.removeCallbacks(webScanRunnable)
        handler.postDelayed(webScanRunnable, 600)
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
        showBlockScreen(title = "Blocked", message = null, packageName = pkg, counterKey = pkg)
    }

    private fun shouldBlock(pkg: String): Boolean {
        val now = System.currentTimeMillis()
        val strict = now < focusEndMillis

        // Quick Block — always-on app rules.
        val rule = rules[pkg]
        if (rule != null && rule.isBlocked) {
            if (strict) return true // Strict Mode blocks every chosen app outright.
            when (rule.mode) {
                BlockMode.HARD, BlockMode.SCHEDULE -> return true
                BlockMode.LIMIT ->
                    if (rule.dailyLimitMinutes >= 0 &&
                        UsageTracker.usedMinutesToday(this, pkg) >= rule.dailyLimitMinutes
                    ) return true
            }
        }

        // Schedules — block when the schedule's condition is currently met.
        for (s in schedules) {
            if (!s.enabled || pkg !in s.packages) continue
            val hit = when (s.type) {
                ScheduleType.TIME -> inTimeWindow(s, now)
                ScheduleType.USAGE_LIMIT ->
                    UsageTracker.usedMinutesToday(this, pkg) >= s.limitMinutes
            }
            if (hit) return true
        }
        return false
    }

    /** Whether [now] falls inside a TIME schedule's active day + hour window. */
    private fun inTimeWindow(s: Schedule, now: Long): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val dayBit = cal.get(Calendar.DAY_OF_WEEK) - 1 // SUNDAY(1) -> bit 0
        if ((s.daysMask shr dayBit) and 1 == 0) return false
        val minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return if (s.startMinutes <= s.endMinutes) {
            minutes in s.startMinutes until s.endMinutes
        } else {
            minutes >= s.startMinutes || minutes < s.endMinutes // wraps past midnight
        }
    }

    // --- Web / keyword filtering ---

    private fun scanWebContent() {
        val text = extractVisibleText()
        if (DEBUG) Log.d(TAG, "scan: ${text.length} chars: ${text.take(120)}")
        if (text.isBlank()) return
        if (text == lastWebText) return

        val hit = filter.check(text, userKeywords, SettingsStore.blockAdult(applicationContext))
        if (hit == null) {
            lastWebText = null
            return
        }
        lastWebText = text
        if (DEBUG) Log.d(TAG, "BLOCK: ${hit.title} / ${hit.message}")
        // Cover the offending page with the block screen. (No GLOBAL_ACTION_BACK — it
        // races with the just-launched activity and dismisses it, same as the
        // GLOBAL_ACTION_HOME race removed in M2. The block screen's Close goes home.)
        showBlockScreen(title = hit.title, message = hit.message, packageName = null, counterKey = "web")
    }

    /**
     * Collects on-screen text (URL bar, search fields, page) across all windows — not
     * just the active one, since Chrome's omnibox/page can sit in a non-active window
     * (suggestion popup, dialog). Capped for battery.
     */
    private fun extractVisibleText(): String {
        val sb = StringBuilder()
        val roots = ArrayList<AccessibilityNodeInfo>()
        windows?.forEach { it.root?.let(roots::add) }
        rootInActiveWindow?.let(roots::add)
        // Never scan our own windows (e.g. the block screen, whose message can itself
        // contain the keyword) — that would re-trigger the block in a loop.
        roots.removeAll { it.packageName == packageName }
        if (roots.isEmpty()) return ""

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        roots.forEach(queue::add)
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

    private fun showBlockScreen(
        title: String,
        message: String?,
        packageName: String?,
        counterKey: String,
    ) {
        val (today, total) = AttemptCounter.record(applicationContext, counterKey)
        startActivity(
            Intent(this, BlockScreenActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(BlockScreenActivity.EXTRA_TITLE, title)
                if (message != null) putExtra(BlockScreenActivity.EXTRA_MESSAGE, message)
                if (packageName != null) putExtra(BlockScreenActivity.EXTRA_PACKAGE, packageName)
                putExtra(BlockScreenActivity.EXTRA_TODAY, today)
                putExtra(BlockScreenActivity.EXTRA_TOTAL, total)
            }
        )
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(webScanRunnable)
        scope.cancel()
    }

    companion object {
        private const val TAG = "AppBlocker"
        private const val DEBUG = false // flip to true to log scans/blocks for debugging
    }
}
