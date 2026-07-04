package com.appblocker.service

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.appblocker.R
import com.appblocker.data.AppRule
import com.appblocker.data.AttemptCounter
import com.appblocker.data.BlockMode
import com.appblocker.data.BlockerDatabase
import com.appblocker.data.InstalledAppsRepository
import com.appblocker.data.LaunchCounter
import com.appblocker.data.QuickSession
import com.appblocker.data.SOCIAL_DOMAINS
import com.appblocker.data.Schedule
import com.appblocker.data.ScheduleType
import com.appblocker.data.SessionClock
import com.appblocker.data.SettingsStore
import com.appblocker.data.UnlockCounter
import com.appblocker.ui.BlockScreenActivity
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    // Strict/Focus deadline anchored to the monotonic clock (clock-change-proof) with a
    // wall-clock fallback. See SessionClock.
    @Volatile private var focusRealtimeStart: Long = 0L
    @Volatile private var focusRealtimeEnd: Long = 0L
    @Volatile private var focusWallEnd: Long = 0L
    @Volatile private var userKeywords: List<String> = emptyList()
    @Volatile private var schedules: List<Schedule> = emptyList()
    // Browser packages installed on the device (for "Block unsupported browsers").
    @Volatile private var browserPackages: Set<String> = emptySet()
    // Non-browser apps the user opted in to keyword scanning (from SettingsStore). Cached here
    // and refreshed by a prefs listener so opt-in changes apply without restarting the service.
    @Volatile private var keywordScanApps: Set<String> = emptySet()
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private var lastBlockedPkg: String? = null
    private var lastBlockAt: Long = 0L
    // Read/written from the background web-scan coroutine as well as the main thread.
    @Volatile private var lastWebText: String? = null

    // Instant block screen drawn as an overlay window (no Activity-launch lag).
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private var overlayView: View? = null
    // Whether the current overlay is an app-rule block (vs web/purchase/Shorts, which are
    // owned by their own scans and must not be taken down by the periodic re-check).
    private var overlayIsAppBlock = false

    @Volatile private var lastForegroundPkg: String? = null
    @Volatile private var lastLocation: Location? = null
    private var locationRequested = false
    private var locationListener: LocationListener? = null
    private var lastLocationRefreshAt = 0L

    // Keeps browserPackages fresh when apps are installed/removed after the service starts.
    private var packageChangeReceiver: BroadcastReceiver? = null
    private var unlockReceiver: BroadcastReceiver? = null

    // Debounced web scan: collapse the page-load event burst into one scan that runs
    // after events stop, so the *settled* page (when its text finally exists) is scanned.
    // The scan itself runs off the main thread; only showing the block UI hops back to main.
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var webScanJob: Job? = null
    private val webScanRunnable = Runnable {
        webScanJob?.cancel()
        webScanJob = scope.launch { scanWebContent() }
    }

    // YouTube Shorts: when on, cover the Shorts player but leave the rest of YouTube usable.
    @Volatile private var shortsScanJob: Job? = null
    private var shortsCovering = false
    private val shortsScanRunnable = Runnable {
        shortsScanJob?.cancel()
        shortsScanJob = scope.launch { scanShorts() }
    }

    // Periodic re-check of the app the user is sitting in, so time-based conditions take
    // effect mid-use instead of only on the next app switch: a daily limit crossing, a time
    // schedule starting (or ending — the same tick releases a stale block overlay), a
    // Pomodoro break starting/ending, a Timer or Strict session running out.
    private val recheckRunnable = object : Runnable {
        override fun run() {
            val pkg = lastForegroundPkg ?: return
            if (overlayView == null) {
                // May block now (limit just crossed, schedule started, break ended…).
                handleAppBlock(pkg)
            } else if (overlayIsAppBlock && !shouldBlock(pkg)) {
                // The condition ended while the cover was up → release without an app switch.
                // (Acting only on this transition also avoids re-recording an "attempt" every
                // tick; web/purchase/Shorts covers are owned by their own scans — hands off.)
                lastBlockedPkg = null
                removeBlockOverlay()
            }
            if (recheckMatters(pkg)) handler.postDelayed(this, RECHECK_MS)
        }
    }

    /** Whether a later re-check of [pkg] could change the blocking outcome — i.e. the app is
     *  covered by any rule/schedule, a session is running, or a block cover is up. */
    private fun recheckMatters(pkg: String): Boolean =
        overlayView != null ||
            rules[pkg]?.isBlocked == true ||
            QuickSession.state(this).active ||
            schedules.any { it.enabled && pkg in it.packages }

    override fun onServiceConnected() {
        super.onServiceConnected()
        browserPackages = findBrowserPackages()
        keywordScanApps = SettingsStore.keywordScanApps(this)
        // React to the user opting apps in/out on the Blocked-words screen without a restart.
        val sp = getSharedPreferences("appblocker_prefs", Context.MODE_PRIVATE)
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SettingsStore.KEY_KEYWORD_SCAN_APPS) {
                keywordScanApps = SettingsStore.keywordScanApps(this)
            }
        }.also { sp.registerOnSharedPreferenceChangeListener(it) }
        registerPackageChangeReceiver()
        registerUnlockReceiver()
        val db = BlockerDatabase.get(applicationContext)
        combine(
            db.appRuleDao().getAll(),
            db.focusDao().get(),
            db.blockedKeywordDao().getAll(),
            db.scheduleDao().getAll(),
        ) { ruleList, focus, keywords, scheduleList ->
            rules = ruleList.associateBy { it.packageName }
            focusRealtimeStart = focus?.realtimeStartMillis ?: 0L
            focusRealtimeEnd = focus?.realtimeEndMillis ?: 0L
            focusWallEnd = focus?.endTimeMillis ?: 0L
            userKeywords = keywords.map { it.keyword }
            schedules = scheduleList
            // Location plumbing follows *enabled* location schedules only — and shuts down
            // when the last one is toggled off, so a disabled schedule can't keep GPS running.
            // Hop to the main thread: requestLocationUpdates needs a looper thread.
            if (scheduleList.any { it.enabled && it.type == ScheduleType.LOCATION }) {
                handler.post { ensureLocationUpdates() }
            } else {
                handler.post { stopLocationUpdates() }
            }
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
                // Keep the location fix current (and recover after a late permission grant).
                if (schedules.any { it.enabled && it.type == ScheduleType.LOCATION }) {
                    ensureLocationUpdates()
                }
                if (pkg != null) {
                    // In-app purchase sheet takes priority; otherwise normal app blocking.
                    if (!handlePurchaseBlock(pkg, event.className?.toString())) handleAppBlock(pkg)
                }
                // (Re)arm the mid-use re-check for the new foreground app; a neutral app
                // (no rules, no session, no cover) costs nothing.
                handler.removeCallbacks(recheckRunnable)
                if (pkg != null && recheckMatters(pkg)) handler.postDelayed(recheckRunnable, RECHECK_MS)
                lastWebText = null // new page/app: force a fresh re-check
                scheduleWebScan()
                scheduleShortsScan()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                scheduleWebScan()
                scheduleShortsScan()
            }
        }
    }

    /** Packages whose on-screen text we scan for blocked words: always browsers, plus any apps
     *  the user opted in — but only when there are words to look for (browsers still scan for
     *  adult content even with no user words, so they stay in unconditionally). */
    private fun scanTargets(): Set<String> =
        if (userKeywords.isEmpty()) browserPackages else browserPackages + keywordScanApps

    /** Debounce: run the scan ~600ms after the last event, i.e. once the screen settles.
     *  Only scheduled while a scan target (browser or an opted-in app) is foreground — the scan
     *  would no-op anywhere else, and content/text events fire constantly in every app. */
    private fun scheduleWebScan() {
        if (lastForegroundPkg !in scanTargets()) return
        handler.removeCallbacks(webScanRunnable)
        handler.postDelayed(webScanRunnable, 600)
    }

    /** Debounced YouTube-Shorts check (quicker than the web scan so Shorts is caught fast).
     *  Only runs while Quick Block is active AND YouTube is foreground (the browser
     *  youtube.com/shorts case is the web scan's job), so other apps' events cost nothing. */
    private fun scheduleShortsScan() {
        if (!SettingsStore.blockYoutubeShorts(this) || !quickBlockActive() ||
            lastForegroundPkg != YOUTUBE_PKG
        ) {
            if (shortsCovering) { shortsCovering = false; removeBlockOverlay() }
            return
        }
        handler.removeCallbacks(shortsScanRunnable)
        handler.postDelayed(shortsScanRunnable, 250)
    }

    // --- App blocking (unchanged behaviour) ---

    private fun handleAppBlock(pkg: String) {
        if (!shouldBlock(pkg)) {
            // Keep a Shorts cover up even though the whole app isn't blocked — the shorts
            // scan owns adding/removing it as the user moves in and out of Shorts.
            if (pkg == YOUTUBE_PKG && shortsCovering) { lastBlockedPkg = null; return }
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

    /**
     * Blocks the Google Play in-app purchase / billing sheet when that option is on. The buy flow
     * runs inside com.android.vending in an "acquire/billing" activity, so we match on that to avoid
     * blocking normal Play Store browsing. Returns true if it blocked.
     */
    private fun handlePurchaseBlock(pkg: String, className: String?): Boolean {
        if (!SettingsStore.blockPurchases(this)) return false
        if (pkg != "com.android.vending") return false
        val cn = className?.lowercase() ?: return false
        val isPurchase = PURCHASE_HINTS.any { cn.contains(it) }
        if (!isPurchase) return false
        showBlockScreen(
            title = "Purchase blocked",
            message = "In-app purchases are blocked.",
            packageName = null,
            counterKey = "purchase",
        )
        return true
    }

    private fun shouldBlock(pkg: String): Boolean {
        val now = System.currentTimeMillis()
        val strict = SessionClock.remaining(focusRealtimeStart, focusRealtimeEnd, focusWallEnd) > 0L

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

        // Unsupported browsers — if web filtering is on, block browsers we can't read so they
        // can't be used to bypass website/keyword filtering (e.g. Brave). Chrome is filterable.
        if (SettingsStore.blockUnsupportedBrowsers(this) &&
            (userKeywords.isNotEmpty() || SettingsStore.blockAdult(this)) &&
            pkg in browserPackages && pkg !in SUPPORTED_BROWSERS
        ) return true

        return false
    }

    /** All packages that can handle an https:// link — i.e. the device's browsers. */
    private fun findBrowserPackages(): Set<String> = runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .filter { it != packageName }
            .toSet()
    }.getOrDefault(emptySet())

    /**
     * Browsers can be installed/removed after the service starts; re-detect them on package
     * changes so "Block unsupported browsers" can't be bypassed by installing a new browser.
     */
    private fun registerPackageChangeReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                scope.launch { browserPackages = findBrowserPackages() }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        packageChangeReceiver = receiver
    }

    /** Counts phone unlocks (for Insights "pickups"). USER_PRESENT can't be a static receiver
     *  on modern Android, so we register it at runtime in this always-on service. */
    private fun registerUnlockReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_USER_PRESENT) {
                    UnlockCounter.recordUnlock(applicationContext)
                }
            }
        }
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        unlockReceiver = receiver
    }

    /** True if connected to Wi-Fi and (target empty = any, else SSID matches). */
    private fun onMatchingWifi(target: String): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
        if (target.isBlank()) return true
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager ?: return false
        @Suppress("DEPRECATION")
        val ssid = wm.connectionInfo?.ssid?.trim('"') ?: return false
        return ssid.equals(target, ignoreCase = true)
    }

    /** True if the last known location is within the schedule's radius. */
    private fun inLocation(s: Schedule): Boolean {
        val loc = lastLocation ?: return false
        val out = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, s.latitude, s.longitude, out)
        return out[0] <= s.radiusMeters
    }

    private fun ensureLocationUpdates() {
        // Needs the "Allow all the time" (background) grant to actually deliver fixes to a
        // background service on Android 10+; foreground-only location yields null here.
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return
        // Always (throttled) pull a fresh current fix — this also self-heals after the user
        // grants the permission later, since onDestroy/combine won't re-run on a grant.
        refreshCurrentLocation(lm)
        if (locationRequested) return
        locationRequested = true
        runCatching {
            // Seed from both providers; considerLocation keeps the freshest.
            considerLocation(lm.getLastKnownLocation(LocationManager.GPS_PROVIDER))
            considerLocation(lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))
            // Keep a reference so onDestroy can unregister it (otherwise updates leak past the
            // service's life — wasting battery and holding a location subscription).
            val listener = LocationListener { considerLocation(it) }
            locationListener = listener
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30_000L, 25f, listener)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 30_000L, 25f, listener)
        }
    }

    /** Unregisters location updates (no enabled location schedules left / service stopping). */
    private fun stopLocationUpdates() {
        val listener = locationListener
        if (listener != null) {
            (getSystemService(LOCATION_SERVICE) as? LocationManager)?.removeUpdates(listener)
            locationListener = null
        }
        locationRequested = false
    }

    /** Adopts [loc] only if it's at least as recent as the current fix (prevents a stale provider
     *  pinning the location so blocking never clears when you leave the area). */
    private fun considerLocation(loc: Location?) {
        loc ?: return
        val cur = lastLocation
        if (cur == null || loc.elapsedRealtimeNanos >= cur.elapsedRealtimeNanos) lastLocation = loc
    }

    /** Asks for a single up-to-date fix (≤ once/60s). API 30+; older relies on passive updates. */
    private fun refreshCurrentLocation(lm: LocationManager) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLocationRefreshAt < 60_000L) return
        lastLocationRefreshAt = now
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            // Prefer GPS for a fresh, movement-tracking fix; fall back to network if GPS is off.
            val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                LocationManager.GPS_PROVIDER
            else LocationManager.NETWORK_PROVIDER
            lm.getCurrentLocation(provider, null, mainExecutor) { loc -> considerLocation(loc) }
        }
    }

    /** Whether [now] falls inside a TIME schedule's active day + hour window. */
    private fun inTimeWindow(s: Schedule, now: Long): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val dayBit = cal.get(Calendar.DAY_OF_WEEK) - 1 // SUNDAY(1) -> bit 0
        if ((s.daysMask shr dayBit) and 1 == 0) return false
        val minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return timeWindowContains(minutes, s.startMinutes, s.endMinutes)
    }

    // --- Web / keyword filtering ---

    /** True when Quick Block is currently enforcing (Strict on, a session says block now, or
     *  not paused) — mirrors the gating used for app blocking in [shouldBlock]. */
    private fun quickBlockActive(): Boolean {
        if (SessionClock.remaining(focusRealtimeStart, focusRealtimeEnd, focusWallEnd) > 0L) return true
        val session = QuickSession.state(this)
        return if (session.active) session.blockingNow else !SettingsStore.quickBlockPaused(this)
    }

    /** Website keywords for the social apps the user has blocked, so a blocked app's site is
     *  blocked too. Empty while Quick Block is paused so pausing relieves the web block as well. */
    private fun autoSocialKeywords(): List<String> {
        if (!quickBlockActive()) return emptyList()
        return rules.values.asSequence()
            .filter { it.isBlocked && it.mode != BlockMode.LIMIT }
            .flatMap { (SOCIAL_DOMAINS[it.packageName] ?: emptyList()).asSequence() }
            .toList()
    }

    /** Runs on a background dispatcher (the node-tree walk is heavy); only the block UI hops to main. */
    private suspend fun scanWebContent() {
        // Scan browsers (word + adult + social-domain filtering) and any app the user explicitly
        // opted in (its own words only). We never scan arbitrary apps, so typing a blocked word in
        // Messages/Notes — or AppBlocker's own keyword field — can't trip a block.
        val pkg = lastForegroundPkg
        if (pkg == null || pkg !in scanTargets()) return
        val isBrowser = pkg in browserPackages
        val text = extractVisibleText()
        if (DEBUG) Log.d(TAG, "scan[$pkg browser=$isBrowser]: ${text.length} chars: ${text.take(120)}")
        if (text.isBlank()) return
        if (text == lastWebText) return

        // YouTube Shorts opened in a browser (youtube.com/shorts) — while Quick Block is active.
        if (isBrowser && SettingsStore.blockYoutubeShorts(applicationContext) && quickBlockActive() &&
            text.lowercase().contains("youtube.com/shorts")
        ) {
            lastWebText = text
            withContext(Dispatchers.Main) {
                showBlockScreen(title = "Shorts blocked",
                    message = "YouTube Shorts is blocked.", packageName = null, counterKey = "shorts")
            }
            return
        }

        // Browsers get the full filter (user words + blocked apps' domains + adult). Opted-in apps
        // match the user's own words only — adult domains/keywords are URL/heuristic lists that
        // don't make sense against arbitrary app UI text.
        val hit = if (isBrowser) {
            filter.check(text, userKeywords + autoSocialKeywords(), SettingsStore.blockAdult(applicationContext))
        } else {
            filter.check(text, userKeywords, blockAdult = false)
        }
        if (hit == null) {
            lastWebText = null
            return
        }
        lastWebText = text
        if (DEBUG) Log.d(TAG, "BLOCK: ${hit.title} / ${hit.message}")
        // Cover the offending page with the block screen. The overlay/Activity must be shown on
        // the main thread. (No GLOBAL_ACTION_BACK — it races with the just-launched activity and
        // dismisses it, same as the GLOBAL_ACTION_HOME race removed in M2. Close goes home.)
        withContext(Dispatchers.Main) {
            showBlockScreen(title = hit.title, message = hit.message, packageName = null, counterKey = "web")
        }
    }

    /**
     * Covers the YouTube Shorts player (when the toggle is on) while leaving the rest of the
     * app usable. Adds the cover when a Short is on screen and removes it the moment the user
     * scrolls/navigates out of Shorts.
     */
    private suspend fun scanShorts() {
        if (!SettingsStore.blockYoutubeShorts(applicationContext) || !quickBlockActive() ||
            lastForegroundPkg != YOUTUBE_PKG
        ) {
            if (shortsCovering) {
                shortsCovering = false
                withContext(Dispatchers.Main) { removeBlockOverlay() }
            }
            return
        }
        val onShorts = isShortsOnScreen()
        if (onShorts && !shortsCovering) {
            shortsCovering = true
            withContext(Dispatchers.Main) {
                showBlockScreen(
                    title = "Shorts blocked",
                    message = "YouTube Shorts is blocked. The rest of YouTube still works.",
                    packageName = null,
                    counterKey = "shorts",
                )
            }
        } else if (!onShorts && shortsCovering) {
            shortsCovering = false
            withContext(Dispatchers.Main) { removeBlockOverlay() }
        }
    }

    /** True if the YouTube Shorts player (the "reel" surface) is currently on screen. We match
     *  the player's view-ids, not the always-present "Shorts" nav tab. */
    private fun isShortsOnScreen(): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName != YOUTUBE_PKG) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 400) {
            val node = queue.removeFirst()
            visited++
            node.viewIdResourceName?.let { id ->
                if (SHORTS_ID_MARKERS.any { id.contains(it) }) return true
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return false
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
        // Only app-rule blocks pass a package; web/purchase/Shorts covers are managed by
        // their own scans and the periodic re-check must leave them alone.
        overlayIsAppBlock = packageName != null
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

    // Launch-warmed cache first (label + icon already decoded); PackageManager fallback for
    // packages that aren't in it (repo not loaded in this process, or non-launchable apps).
    private fun loadLabel(pkg: String): String? =
        InstalledAppsRepository.apps.value.firstOrNull { it.packageName == pkg }?.label
            ?: runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            }.getOrNull()

    private fun loadIcon(pkg: String) =
        InstalledAppsRepository.apps.value.firstOrNull { it.packageName == pkg }?.icon
            ?: runCatching { packageManager.getApplicationIcon(pkg).toBitmap(144, 144) }.getOrNull()

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(webScanRunnable)
        handler.removeCallbacks(shortsScanRunnable)
        handler.removeCallbacks(recheckRunnable)
        // Stop location updates so they don't leak past the service (battery + privacy).
        stopLocationUpdates()
        prefsListener?.let {
            runCatching {
                getSharedPreferences("appblocker_prefs", Context.MODE_PRIVATE)
                    .unregisterOnSharedPreferenceChangeListener(it)
            }
        }
        prefsListener = null
        packageChangeReceiver?.let { runCatching { unregisterReceiver(it) } }
        packageChangeReceiver = null
        unlockReceiver?.let { runCatching { unregisterReceiver(it) } }
        unlockReceiver = null
        removeBlockOverlay()
        scope.cancel()
    }

    companion object {
        private const val TAG = "AppBlocker"
        private const val DEBUG = false // flip to true to log scans/blocks for debugging

        // How often to re-check the app the user is currently inside (mid-use enforcement).
        private const val RECHECK_MS = 30_000L

        // Browsers whose on-screen content the web filter can read; others are "unsupported".
        private val SUPPORTED_BROWSERS = setOf("com.android.chrome")

        // Activity-name fragments that identify the Google Play purchase/billing sheet.
        private val PURCHASE_HINTS = listOf("acquire", "purchase", "billing")

        private const val YOUTUBE_PKG = "com.google.android.youtube"

        // YouTube Shorts player view-id fragments (Shorts is "reel" internally). These match the
        // full-screen Short player, NOT the always-present "Shorts" nav tab. Exact ids vary by
        // YouTube version, so this list is intentionally broad and may need tuning over time.
        private val SHORTS_ID_MARKERS = listOf(
            "reel_recycler", "reel_player", "reel_watch", "reels_player",
            "reel_progress", "shorts_",
        )
    }
}
