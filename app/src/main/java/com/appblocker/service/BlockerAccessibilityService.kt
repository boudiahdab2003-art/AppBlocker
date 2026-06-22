package com.appblocker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.drawable.toBitmap
import com.appblocker.R
import com.appblocker.data.AppRule
import com.appblocker.data.AttemptCounter
import com.appblocker.data.BlockMode
import com.appblocker.data.BlockerDatabase
import com.appblocker.data.LaunchCounter
import com.appblocker.data.QuickSession
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

    // Instant block screen drawn as an overlay window (no Activity-launch lag).
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private var overlayView: View? = null

    private var lastForegroundPkg: String? = null
    @Volatile private var lastLocation: android.location.Location? = null
    private var locationRequested = false

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
            if (scheduleList.any { it.type == ScheduleType.LOCATION }) ensureLocationUpdates()
        }.launchIn(scope)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString()
        if (pkg == packageName) return // never act on ourselves

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg != null && pkg != lastForegroundPkg) {
                    lastForegroundPkg = pkg
                    LaunchCounter.recordOpen(applicationContext, pkg) // for LAUNCH_COUNT
                }
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
            removeBlockOverlay() // left the blocked app — take the cover down
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

        // Quick Block — enforced when Strict, or a running Timer/Pomodoro says "block now",
        // or (no session) when not paused.
        val rule = rules[pkg]
        if (rule != null && rule.isBlocked) {
            if (strict) return true // Strict Mode blocks every chosen app outright.
            val session = QuickSession.state(this)
            val quickOn = if (session.active) session.blockingNow else !SettingsStore.quickBlockPaused(this)
            if (quickOn) when (rule.mode) {
                BlockMode.HARD, BlockMode.SCHEDULE -> return true
                BlockMode.LIMIT ->
                    if (rule.dailyLimitMinutes >= 0 &&
                        UsageTracker.usedMinutesToday(this, pkg) >= rule.dailyLimitMinutes
                    ) return true
            }
        }

        // Schedules — block when the schedule's condition is currently met.
        if (DEBUG) Log.d(TAG, "shouldBlock $pkg schedules=${schedules.size} opens=${LaunchCounter.opensToday(this, pkg)}")
        for (s in schedules) {
            if (!s.enabled || pkg !in s.packages) continue
            val hit = when (s.type) {
                ScheduleType.TIME -> inTimeWindow(s, now)
                ScheduleType.USAGE_LIMIT ->
                    UsageTracker.usedMinutesToday(this, pkg) >= s.limitMinutes
                ScheduleType.LAUNCH_COUNT ->
                    LaunchCounter.opensToday(this, pkg) >= s.limitCount
                ScheduleType.WIFI -> onMatchingWifi(s.wifiSsid)
                ScheduleType.LOCATION -> inLocation(s)
            }
            if (hit) return true
        }
        return false
    }

    /** True if connected to Wi-Fi and (target empty = any, else SSID matches). */
    private fun onMatchingWifi(target: String): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return false
        if (target.isBlank()) return true
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return false
        @Suppress("DEPRECATION")
        val ssid = wm.connectionInfo?.ssid?.trim('"') ?: return false
        return ssid.equals(target, ignoreCase = true)
    }

    /** True if the last known location is within the schedule's radius. */
    private fun inLocation(s: Schedule): Boolean {
        val loc = lastLocation ?: return false
        val out = FloatArray(1)
        android.location.Location.distanceBetween(loc.latitude, loc.longitude, s.latitude, s.longitude, out)
        return out[0] <= s.radiusMeters
    }

    private fun ensureLocationUpdates() {
        if (locationRequested) return
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val lm = getSystemService(LOCATION_SERVICE) as? android.location.LocationManager ?: return
        locationRequested = true
        runCatching {
            lastLocation = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
            val listener = android.location.LocationListener { lastLocation = it }
            lm.requestLocationUpdates(android.location.LocationManager.NETWORK_PROVIDER, 60_000L, 50f, listener)
            lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 60_000L, 50f, listener)
        }
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
        val label = packageName?.let { loadLabel(it) }
        val msg = message ?: label?.let { "$it is blocked" } ?: "This is blocked right now."
        // Instant overlay; fall back to the Activity only if the overlay can't be drawn.
        if (!showBlockOverlay(packageName, title, msg, today, total)) {
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
    }

    /** Draws/updates the full-screen block overlay instantly. Returns false if it can't. */
    private fun showBlockOverlay(
        packageName: String?, title: String, message: String, today: Int, total: Int,
    ): Boolean = try {
        val v = overlayView ?: LayoutInflater.from(this).inflate(R.layout.overlay_block, null).also {
            it.findViewById<Button>(R.id.overlay_close).setOnClickListener {
                lastBlockedPkg = null
                removeBlockOverlay()
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )
            windowManager.addView(it, params)
            overlayView = it
        }
        v.findViewById<TextView>(R.id.overlay_title).text = title
        v.findViewById<TextView>(R.id.overlay_subtitle).text = message
        v.findViewById<TextView>(R.id.overlay_counts).text = "$today× today  ·  $total× total"
        val iconView = v.findViewById<ImageView>(R.id.overlay_icon)
        val bmp = packageName?.let { loadIcon(it) }
        if (bmp != null) iconView.setImageBitmap(bmp)
        else iconView.setImageResource(R.mipmap.ic_launcher)
        true
    } catch (e: Exception) {
        Log.w(TAG, "overlay failed, falling back to activity", e)
        false
    }

    private fun removeBlockOverlay() {
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
    }

    private fun loadLabel(pkg: String): String? = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    private fun loadIcon(pkg: String) = runCatching {
        packageManager.getApplicationIcon(pkg).toBitmap(144, 144)
    }.getOrNull()

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(webScanRunnable)
        removeBlockOverlay()
        scope.cancel()
    }

    companion object {
        private const val TAG = "AppBlocker"
        private const val DEBUG = false // flip to true to log scans/blocks for debugging
    }
}
