package com.appblocker.service

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.LinearGradient
import android.graphics.PixelFormat
import android.graphics.Shader
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
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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
import com.appblocker.data.FocusState
import com.appblocker.data.InstalledAppsRepository
import com.appblocker.data.LaunchCounter
import com.appblocker.data.QuickSession
import com.appblocker.data.Quotes
import com.appblocker.data.SOCIAL_DOMAINS
import com.appblocker.data.Schedule
import com.appblocker.data.ScheduleType
import com.appblocker.data.SessionClock
import com.appblocker.data.SettingsStore
import com.appblocker.data.UnlockCounter
import com.appblocker.data.UpdatePause
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
 *    text and block adult sites (browsers) or the user's keywords (every app,
 *    minus a small exclusion set that keeps the phone usable).
 */
class BlockerAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val filter by lazy { WebContentFilter.get(applicationContext) }

    @Volatile private var rules: Map<String, AppRule> = emptyMap()
    // Strict/Focus deadline anchored to the monotonic clock (clock-change-proof) with a
    // wall-clock fallback. See SessionClock.
    @Volatile private var focusRealtimeStart: Long = 0L
    @Volatile private var focusRealtimeEnd: Long = 0L
    @Volatile private var focusWallStart: Long = 0L
    @Volatile private var focusWallEnd: Long = 0L
    @Volatile private var userKeywords: List<String> = emptyList()
    @Volatile private var schedules: List<Schedule> = emptyList()
    // Browser packages installed on the device (for "Block unsupported browsers").
    @Volatile private var browserPackages: Set<String> = emptySet()
    // Home-screen (launcher) apps — never keyword-scanned, see findLauncherPackages().
    @Volatile private var launcherPackages: Set<String> = emptySet()
    // Whether blocked words are matched in every app (default) or browsers only. Cached here
    // and refreshed by a prefs listener so the toggle applies without restarting the service.
    @Volatile private var keywordsEverywhere: Boolean = true
    // Whether the built-in adult word pack (English + Arabic) is enforced. Same freshness
    // mechanism as keywordsEverywhere.
    @Volatile private var adultPackOn: Boolean = true
    // Blocking paused after an app update, until the user reactivates (Blocking-tab banner).
    @Volatile private var updatePaused: Boolean = false
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** True while the after-update pause suspends blocking. An update also ENDS any running
     *  Strict session (UpdatePause zeroes the row); the strictRemaining() guard here only
     *  covers the brief moment before that async clear lands. */
    private fun updatePauseActive(): Boolean =
        updatePaused && strictRemaining() <= 0L

    private fun strictRemaining(): Long =
        SessionClock.remaining(focusRealtimeStart, focusRealtimeEnd, focusWallStart, focusWallEnd)

    // Zeroes the focus row once the session it describes has expired, so a stale deadline
    // can never resurrect after a reboot with a wrong wall clock. Re-checks at fire time:
    // only a genuinely expired session is cleared — Strict stays un-stoppable while active.
    private val focusClearRunnable = Runnable {
        if (strictRemaining() <= 0L && (focusWallEnd > 0L || focusRealtimeEnd > 0L)) {
            scope.launch {
                BlockerDatabase.get(applicationContext).focusDao().set(FocusState(id = 0))
            }
        }
    }

    private var lastBlockedPkg: String? = null
    private var lastBlockAt: Long = 0L
    // Throttles the Strict-Mode settings-guard bounce so a burst of content events on the same
    // dangerous page doesn't fire GLOBAL_ACTION_HOME over and over.
    private var lastGuardBounceAt: Long = 0L
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
        // The service is rebound right after an update installs, so detect it here too —
        // the pause arms even if the app itself isn't opened.
        UpdatePause.checkVersionChange(this)
        browserPackages = findBrowserPackages()
        launcherPackages = findLauncherPackages()
        keywordsEverywhere = SettingsStore.keywordsEverywhere(this)
        adultPackOn = SettingsStore.adultWordsPack(this)
        updatePaused = SettingsStore.updatePaused(this)
        // React to the user flipping the toggles on the Blocked-words screen without a restart.
        val sp = getSharedPreferences("appblocker_prefs", Context.MODE_PRIVATE)
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                SettingsStore.KEY_KEYWORDS_EVERYWHERE ->
                    keywordsEverywhere = SettingsStore.keywordsEverywhere(this)
                SettingsStore.KEY_ADULT_WORDS_PACK ->
                    adultPackOn = SettingsStore.adultWordsPack(this)
                SettingsStore.KEY_UPDATE_PAUSED ->
                    updatePaused = SettingsStore.updatePaused(this)
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
            focusWallStart = focus?.startTimeMillis ?: 0L
            focusWallEnd = focus?.endTimeMillis ?: 0L
            // Arm a one-shot clear for when this session expires (fires immediately for a
            // session that already expired, e.g. while the phone was off). See the runnable.
            handler.removeCallbacks(focusClearRunnable)
            if (focusWallEnd > 0L || focusRealtimeEnd > 0L) {
                handler.postDelayed(focusClearRunnable, strictRemaining() + 2_000L)
            }
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
                    // Strict Mode escape-hatch guard first (Accessibility/device-admin/app-info
                    // pages), then in-app purchase sheet, then normal app blocking.
                    if (!handleStrictSettingsGuard(pkg, event.className?.toString()) &&
                        !handlePurchaseBlock(pkg, event.className?.toString())
                    ) handleAppBlock(pkg)
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
                // The dangerous Settings pages often fill in their title/labels on a content
                // event after the window opens, so re-check the guard here too.
                if (pkg != null) handleStrictSettingsGuard(pkg, event.className?.toString())
                scheduleWebScan()
                scheduleShortsScan()
            }
        }
    }

    /** Whether [pkg]'s on-screen text should be scanned for blocked words. Browsers always
     *  (adult filter runs even with no user words). With words to match (the user's own or the
     *  built-in adult pack) + "every app" on: everywhere except ourselves, the launcher(s),
     *  System UI and Settings — a word matching an app's label there would block Home/Settings
     *  and make the phone unusable. */
    private fun shouldScanPkg(pkg: String?): Boolean {
        if (pkg == null || pkg == packageName) return false
        // After-update pause: only the adult layer keeps scanning (its off-switch is
        // deliberately hard — an update must not become the easy one), browsers only.
        if (updatePauseActive()) {
            return pkg in browserPackages && (adultPackOn || SettingsStore.blockAdult(this))
        }
        if (pkg in browserPackages) return true
        if ((userKeywords.isEmpty() && !adultPackOn) || !keywordsEverywhere) return false
        return pkg !in launcherPackages && pkg !in KEYWORD_SCAN_EXCLUDED
    }

    /** Debounce: run the scan ~600ms after the last event, i.e. once the screen settles.
     *  Not scheduled for excluded packages (the launcher and System UI are the highest-churn
     *  event sources) or when nothing would be scanned — the scan would no-op there, and
     *  content/text events fire constantly in every app. */
    private fun scheduleWebScan() {
        if (!shouldScanPkg(lastForegroundPkg)) return
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
     * While Strict Mode is active, stop the user from reaching the screens that would let them
     * turn AppBlocker off: the Accessibility settings (disable the service = kill all blocking),
     * the Device-admin page (deactivate = allow uninstall), and AppBlocker's own App-info page
     * (force-stop / uninstall). We bounce them to the home screen the instant such a page opens —
     * before they can reach the toggle. Returns true if it bounced.
     *
     * Detection is deliberately broad (like the purchase/Shorts matchers) because OEMs — Xiaomi
     * especially — name these activities unpredictably: a className fast-path for AOSP's aliased
     * activities, plus an on-screen-text fallback for MIUI's generic SubSettings/app-info screens.
     */
    private fun handleStrictSettingsGuard(pkg: String, className: String?): Boolean {
        val strict = strictRemaining() > 0L
        if (!strict) return false
        if (pkg !in GUARD_PACKAGES) return false

        val cn = className?.lowercase().orEmpty()
        val byClass = STRICT_GUARD_HINTS.any { cn.contains(it) }
        val danger = byClass || guardScreenIsDangerous()
        if (!danger) return false

        val now = System.currentTimeMillis()
        if (now - lastGuardBounceAt < 1500) return true // already bouncing this page
        lastGuardBounceAt = now
        if (DEBUG) Log.d(TAG, "strict guard bounce: pkg=$pkg class=$className byClass=$byClass")

        showBlockScreen(
            title = "Locked during Strict Mode",
            message = "You can't change this while Strict Mode is active.",
            packageName = null,
            counterKey = "strict_guard",
        )
        performGlobalAction(GLOBAL_ACTION_HOME)
        // Safety net: a null-package cover has no owner to auto-remove it, so if HOME is slow or
        // suppressed (MIUI), take it down anyway. The normal path is the launcher's window-state
        // event → handleAppBlock → removeBlockOverlay.
        handler.postDelayed({ if (!overlayIsAppBlock) removeBlockOverlay() }, 1500)
        return true
    }

    /** Lowercased visible text of the current page (rootInActiveWindow only). Kept separate from
     *  extractVisibleText(), which strips com.android.settings (it's in KEYWORD_SCAN_EXCLUDED). */
    private fun guardScreenText(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 300 && sb.length < 3000) {
            val node = queue.removeFirst()
            visited++
            node.text?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
            node.contentDescription?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return sb.toString().lowercase()
    }

    /** True if the current page is a Strict-Mode danger page — our app's name next to an
     *  accessibility / device-admin / uninstall / force-stop control. */
    private fun guardScreenIsDangerous(): Boolean {
        val text = guardScreenText()
        if (!text.contains("appblocker")) return false
        return GUARD_TEXT_MARKERS.any { text.contains(it) }
    }

    /**
     * Blocks the Google Play in-app purchase / billing sheet when that option is on. The buy flow
     * runs inside com.android.vending in an "acquire/billing" activity, so we match on that to avoid
     * blocking normal Play Store browsing. Returns true if it blocked.
     */
    private fun handlePurchaseBlock(pkg: String, className: String?): Boolean {
        if (updatePauseActive()) return false
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
        // After an update: nothing blocks until the user reactivates (the update also ends
        // any Strict session — see UpdatePause).
        if (updatePauseActive()) return false
        val now = System.currentTimeMillis()
        val strict = strictRemaining() > 0L

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
            (userKeywords.isNotEmpty() || adultPackOn || SettingsStore.blockAdult(this)) &&
            pkg in browserPackages && pkg !in SUPPORTED_BROWSERS
        ) return true

        return false
    }

    /** All installed home-screen (launcher) apps — never keyword-scanned: a keyword matching an
     *  app's label on the home screen would cover Home itself, and Close→home would loop forever. */
    private fun findLauncherPackages(): Set<String> = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }.getOrDefault(emptySet())

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
                scope.launch {
                    browserPackages = findBrowserPackages()
                    launcherPackages = findLauncherPackages()
                }
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
        if (strictRemaining() > 0L) return true
        if (updatePaused) return false // after-update pause (strict handled above)
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
        // Scan browsers (word + adult + social-domain filtering) and, when the user has blocked
        // words with "every app" on, every other app too (user words only). The word appearing
        // anywhere — including one the user types into another app's own text field — trips the
        // block; that's the chosen behavior. Our own windows (e.g. the keyword-entry field) are
        // skipped in extractVisibleText, and launcher/System UI/Settings are excluded.
        val pkg = lastForegroundPkg ?: return
        if (!shouldScanPkg(pkg)) return
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

        // Browsers get the full filter (user words + adult word pack + blocked apps' domains +
        // adult site list). Other apps match the user's own words + the adult word pack — the
        // adult domains/keywords are URL/heuristic lists that don't make sense against arbitrary
        // app UI text, but the pack is whole-word matched so it's safe everywhere.
        // After-update pause: the user's own words pause with everything else; only the
        // adult layer (pack + adult sites) keeps matching.
        val ownWords = if (updatePauseActive()) emptyList() else userKeywords
        val hit = if (isBrowser) {
            filter.check(text, ownWords + autoSocialKeywords(), adultPackOn, SettingsStore.blockAdult(applicationContext))
        } else {
            filter.check(text, ownWords, adultPackOn, blockAdult = false)
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
        windows?.forEach { w ->
            // Skip the keyboard: its suggestion strip shows a blocked word while it's being
            // typed. (windows is empty without flagRetrieveInteractiveWindows on stock builds,
            // but some OEMs populate it — this keeps them safe too.)
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return@forEach
            w.root?.let(roots::add)
        }
        rootInActiveWindow?.let(roots::add)
        // Never scan our own windows (e.g. the block screen, whose message can itself
        // contain the keyword) — that would re-trigger the block in a loop. Same for
        // launcher/System UI windows overlaying the scanned app on OEM builds.
        roots.removeAll {
            val p = it.packageName?.toString() ?: return@removeAll false
            p == packageName || p in launcherPackages || p in KEYWORD_SCAN_EXCLUDED
        }
        if (roots.isEmpty()) return ""

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        roots.forEach(queue::add)
        var visited = 0
        while (queue.isNotEmpty() && visited < 400 && sb.length < 4000) {
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
        // Masthead: every dodged open counts as ~3 minutes of life back.
        v.findViewById<TextView>(R.id.overlay_stat_number).text =
            (AttemptCounter.totalToday(applicationContext) * MINUTES_PER_DODGE).toString()
        // Fresh motivation every time the cover appears (the view is reused across blocks).
        val quote = Quotes.random()
        v.findViewById<TextView>(R.id.overlay_quote).apply {
            text = quote.text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Quotes.sizeSpFor(quote.text))
        }
        v.findViewById<TextView>(R.id.overlay_quote_author).apply {
            text = "— ${quote.author}"
            // Brand blue→violet sweep across the author line.
            val width = paint.measureText(text.toString()).coerceAtLeast(1f)
            paint.shader = LinearGradient(
                0f, 0f, width, 0f,
                0xFF2E7BFF.toInt(), 0xFF7C5CFF.toInt(), Shader.TileMode.CLAMP,
            )
        }
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
        handler.removeCallbacks(focusClearRunnable)
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

        // Average minutes of scrolling one dodged open would have cost — powers the
        // "minutes reclaimed today" masthead on the block screen.
        private const val MINUTES_PER_DODGE = 3

        // How often to re-check the app the user is currently inside (mid-use enforcement).
        private const val RECHECK_MS = 30_000L

        // Browsers whose on-screen content the web filter can read; others are "unsupported".
        private val SUPPORTED_BROWSERS = setOf("com.android.chrome")

        // Never keyword-scanned even with "every app" on: System UI (notification shade,
        // recents — shows app labels) and Settings (Settings→Apps lists every app's name; a
        // keyword that's also an app name would lock the user out of managing the phone).
        private val KEYWORD_SCAN_EXCLUDED = setOf("com.android.systemui", "com.android.settings")

        // Activity-name fragments that identify the Google Play purchase/billing sheet.
        private val PURCHASE_HINTS = listOf("acquire", "purchase", "billing")

        // Packages that host the Strict-Mode escape hatches (system Settings, MIUI's security
        // center, and the package uninstaller flows).
        private val GUARD_PACKAGES = setOf(
            "com.android.settings",
            "com.miui.securitycenter", "com.miui.securitycore",
            "com.miui.packageinstaller", "com.android.packageinstaller",
            "com.google.android.packageinstaller",
        )

        // Activity-name fragments for the dangerous Settings pages. The accessibility and
        // device-admin ones are AOSP aliased activities (verified on the emulator). The app-info
        // fragments are best-effort for the force-stop/uninstall page — specific enough not to
        // over-match generic containers (AOSP's SPA app-info uses a shared SpaActivity we can't
        // safely match, so on that build the text fallback / device-admin block cover it instead).
        private val STRICT_GUARD_HINTS = listOf(
            "accessibilit", "deviceadmin", "device_admin",
            "installedappdetails", "appinfodashboard",
        )

        // On-screen-text markers (paired with our app label) for MIUI's generic SubSettings /
        // app-info screens where the className alone doesn't identify the page. English only —
        // fine for this device; add localized markers if needed.
        private val GUARD_TEXT_MARKERS = listOf(
            "accessibilit", "device admin", "deactivate", "uninstall", "force stop", "force-stop",
        )

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
