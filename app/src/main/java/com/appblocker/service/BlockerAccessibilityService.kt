package com.appblocker.service

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.appblocker.R
import com.appblocker.data.AppRule
import com.appblocker.data.AttemptCounter
import com.appblocker.data.BlockMode
import com.appblocker.data.BlockedKeyword
import com.appblocker.data.BlockerDatabase
import com.appblocker.data.DeviceBoot
import com.appblocker.data.FocusState
import com.appblocker.data.InstalledAppsRepository
import com.appblocker.data.LaunchCounter
import com.appblocker.data.OwnUi
import com.appblocker.data.QuickSession
import com.appblocker.data.SOCIAL_DOMAINS
import com.appblocker.data.Schedule
import com.appblocker.data.ScheduleType
import com.appblocker.data.ServiceHealth
import com.appblocker.data.SessionClock
import com.appblocker.data.SettingsStore
import com.appblocker.data.UnlockCounter
import com.appblocker.data.UpdatePause
import com.appblocker.ui.BlockScreenActivity
import java.util.Calendar
import kotlinx.coroutines.CoroutineExceptionHandler
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

    // The handler is the safety net for the background scans: without it, an exception inside a
    // launched coroutine reaches the thread's default handler and kills the process — taking all
    // blocking with it. See guarded() in Guarded.kt.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, t ->
                Log.w(TAG, "background scan failed — blocking continues", t)
                runCatching { ServiceHealth.recordError(applicationContext, "scan", t) }
            }
    )
    private val filter by lazy { WebContentFilter.get(applicationContext) }

    @Volatile private var rules: Map<String, AppRule> = emptyMap()
    // Strict/Focus deadline anchored to the monotonic clock (clock-change-proof) with a
    // wall-clock fallback. See SessionClock.
    @Volatile private var focusRealtimeStart: Long = 0L
    @Volatile private var focusRealtimeEnd: Long = 0L
    @Volatile private var focusWallStart: Long = 0L
    @Volatile private var focusWallEnd: Long = 0L
    @Volatile private var focusBootCount: Int = -1
    @Volatile private var userKeywords: List<String> = emptyList()
    @Volatile private var schedules: List<Schedule> = emptyList()
    // Browser packages installed on the device (for "Block unsupported browsers").
    @Volatile private var browserPackages: Set<String> = emptySet()
    // Home-screen (launcher) apps — never keyword-scanned, see findLauncherPackages(this).
    @Volatile private var launcherPackages: Set<String> = emptySet()
    // Negative cache for isLauncherPkg, so its throttled re-detection only runs on new packages.
    @Volatile private var knownNonLauncherPkgs: Set<String> = emptySet()
    @Volatile private var lastLauncherRefreshAt = 0L
    // Whether blocked words are matched in every app (default) or browsers only. Cached here
    // and refreshed by a prefs listener so the toggle applies without restarting the service.
    @Volatile private var keywordsEverywhere: Boolean = true
    // Whether the built-in adult word pack (English + Arabic) is enforced. Same freshness
    // mechanism as keywordsEverywhere.
    @Volatile private var adultPackOn: Boolean = true
    // Blocking paused after an app update, until the user reactivates (Blocking-tab banner).
    @Volatile private var updatePaused: Boolean = false
    // Quick Block mode: false = Blocklist (block chosen apps), true = Allowlist (allow only the
    // chosen apps, block everything else but the essentials). Refreshed by the prefs listener.
    @Volatile private var allowlistMode: Boolean = false
    // System apps that must never be blocked in Allowlist mode or the phone becomes unusable.
    // Resolved at connect (rarely change); the current IME is re-read live (see isEssentialAllowed).
    @Volatile private var essentialPackages: Set<String> = emptySet()
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** True while the after-update pause suspends blocking. An update also ENDS any running
     *  Strict session (UpdatePause zeroes the row); the strictRemaining() guard here only
     *  covers the brief moment before that async clear lands. */
    private fun updatePauseActive(): Boolean =
        updatePaused && strictRemaining() <= 0L

    private fun strictRemaining(): Long =
        SessionClock.remaining(
            focusRealtimeStart, focusRealtimeEnd, focusWallStart, focusWallEnd,
            focusBootCount, DeviceBoot.count(applicationContext),
        )

    // Zeroes the focus row once the session it describes has expired, so a stale deadline
    // can never resurrect after a reboot with a wrong wall clock. Re-checks at fire time:
    // only a genuinely expired session is cleared — Strict stays un-stoppable while active.
    private val focusClearRunnable = Runnable {
        guarded(applicationContext, "focusClear") {
            if (strictRemaining() <= 0L && (focusWallEnd > 0L || focusRealtimeEnd > 0L)) {
                scope.launch {
                    BlockerDatabase.get(applicationContext).focusDao().set(FocusState(id = 0))
                }
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

    // Instant block screen drawn as an overlay window (no Activity-launch lag). The window
    // mechanics live in BlockOverlay; it also tracks what the visible cover is counting
    // (one cover = one recorded attempt) and whether it's a whole-app block (vs web/
    // purchase/Shorts covers, which their own scans own and the re-check must leave alone).
    private val overlay by lazy { BlockOverlay(this) }
    // The just-dismissed cover's key + time: "Got it" goes HOME, but events from the still-
    // visible app during that transition must not instantly re-block/re-count the same entry.
    // (Volatile: also read by the background web-scan coroutine.)
    @Volatile private var dismissedKey: String? = null
    // The app that was BEHIND the dismissed cover, so its lockout re-show (keyed by package,
    // not by the dismissed counterKey) is suppressed for the same transition window.
    @Volatile private var dismissedPkg: String? = null
    @Volatile private var dismissedAt = 0L

    // The app the user is being taken out of after "Got it", or null when we're not mid-exit.
    // "Got it" does not mean "take the cover away", it means "get me out of here": the cover
    // stays up until the phone is really off the blocked app. It used to come down immediately
    // and independently of GLOBAL_ACTION_HOME — so whenever HOME didn't land (this phone's
    // gesture nav often reports nothing at all), the blocked app was left fully usable with no
    // cover, and the watcher then re-blocked it seconds later as a BRAND-NEW block: a second
    // cover, a fresh quote and a second counted attempt for one single open.
    @Volatile private var exitTarget: String? = null
    private var exitStartedAt = 0L
    private var exitHomeTries = 0
    private val exitRunnable = object : Runnable {
        override fun run() = guarded(applicationContext, "exit") { runGuarded() }

        private fun runGuarded() {
            val target = exitTarget ?: return
            val view = exitView(target)
            // Off the blocked app — the cover has done its job.
            if (view == ExitView.LEFT) return finishExit(left = true)
            // Another path already took the cover down (the launcher's own window event runs
            // through handleAppBlock), so there is nothing left to hold: stop asking for HOME.
            if (!overlay.isShowing) return finishExit(left = true)
            val since = System.currentTimeMillis() - exitStartedAt
            // Hold on for the full window only while we can SEE the app is still in front.
            // When we can't tell, let go quickly — holding a cover up on no evidence is what
            // left it sitting over the home screen.
            val confirmed = view == ExitView.STILL_THERE
            val limit = if (confirmed) EXIT_GIVE_UP_MS else EXIT_BLIND_GIVE_UP_MS
            // Never trap the user: give up and let the dismiss grace take over. (Staying in
            // the app then re-covers it once the grace lapses — but as the SAME block, so it
            // isn't counted again.)
            if (since >= limit) return finishExit(left = false, confirmed = confirmed)
            // HOME can be swallowed or arrive late (slow transition, split-screen, HyperOS).
            // Ask again at the two thresholds rather than on every poll.
            val tries = when {
                since >= EXIT_RETRY2_MS -> 3
                since >= EXIT_RETRY1_MS -> 2
                else -> 1
            }
            if (exitHomeTries < tries) {
                exitHomeTries = tries
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            handler.postDelayed(this, EXIT_POLL_MS)
        }
    }

    // Re-reads what is actually in front after a window-state event was rejected as unconfirmed
    // (see onForegroundChanged). Deliberately trusts the window tree rather than the event that
    // triggered it, so a genuine app switch that merely lost the race is picked up a beat later
    // instead of waiting for the next event — and a stray background event resolves to whatever
    // the user really has open. Cannot recurse: the re-entry's package IS the active window, so
    // it passes the confirmation.
    private val confirmForegroundRunnable = Runnable {
        guarded(applicationContext, "confirmForeground") {
            val actual = rootInActiveWindow?.packageName?.toString()
            if (actual != null && actual != packageName && actual != lastForegroundPkg) {
                onForegroundChanged(actual, null)
            }
        }
    }

    // The offence most recently recorded as an attempt, and when — so one open is one attempt
    // however many times the cover has to be redrawn to keep the user out. Keyed by offence
    // rather than by counter key, so a blocked word and the app lockout it creates count once
    // between them. See showBlockScreen and CoverGate.shouldCount.
    private var lastCountedOffence: String? = null
    private var lastCountedAt = 0L
    // Set when "Got it" failed to get the user out and the cover has to go back up for the same
    // offence — that redraw is one sitting, not a second attempt. Kept separate from the plain
    // cooldown so this allowance applies only where it's known to be needed, and so the ordinary
    // cooldown can stay short enough that a real second open still counts.
    private var resumingOffence: String? = null

    // Apps where a blocked word was caught: the whole app stays locked — any page, any
    // text — until the expiry time. Guarded by its own lock (written from the background
    // scan, read from the main thread); mirrored to prefs so restarts don't lift it.
    private val keywordLockouts = mutableMapOf<String, Long>()
    // The word that triggered each app's lockout, so re-entry can name it too. In-memory only
    // (guarded by the keywordLockouts lock) — after a reboot it falls back to a generic line.
    private val keywordLockoutWords = mutableMapOf<String, String>()

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
    // When the current pending burst's first event arrived (0 = none pending) — lets the
    // debounce cap how long a never-quiet page can keep postponing the scan.
    private var webScanQueuedAt = 0L
    private val webScanRunnable = Runnable {
        guarded(applicationContext, "webScan") {
            webScanQueuedAt = 0L
            webScanJob?.cancel()
            webScanJob = scope.launch { scanWebContent() }
        }
    }

    // YouTube Shorts: when on, cover the Shorts player but leave the rest of YouTube usable.
    @Volatile private var shortsScanJob: Job? = null
    private var shortsCovering = false
    private val shortsScanRunnable = Runnable {
        guarded(applicationContext, "shortsScan") {
            shortsScanJob?.cancel()
            shortsScanJob = scope.launch { scanShorts() }
        }
    }

    // Periodic re-check of the app the user is sitting in, so time-based conditions take
    // effect mid-use instead of only on the next app switch: a daily limit crossing, a time
    // schedule starting (or ending — the same tick releases a stale block overlay), a
    // Pomodoro break starting/ending, a Timer or Strict session running out.
    private val recheckRunnable = object : Runnable {
        override fun run() = guarded(applicationContext, "recheck") { runGuarded() }

        private fun runGuarded() {
            var pkg = lastForegroundPkg ?: return
            // Gesture-nav Home can arrive without a window-state event, leaving the cache on
            // the app the user already left — reconcile with the real active window first so
            // a stale package is never (re-)blocked over the home screen. (Our own overlay
            // window may report as the active window; never "reconcile" to ourselves.)
            val actual = rootInActiveWindow?.packageName?.toString()
            if (actual != null && actual != packageName && actual != pkg) {
                lastForegroundPkg = actual
                pkg = actual
            }
            if (isLauncherPkg(pkg)) {
                // On Home nothing should stay covered — this also releases the web/purchase/
                // guard covers (packageName == null) that no other path takes down when the
                // launcher window-state event went missing. Shorts covers stay owned by their
                // scan. The next window-state event re-arms this loop.
                if (overlay.isShowing && !shortsCovering) {
                    lastBlockedPkg = null
                    overlay.remove()
                }
                return
            }
            if (!overlay.isShowing) {
                // Draw a NEW cover only when the foreground is positively identified as the
                // cached app (post-reconcile that means a readable root that isn't our own
                // UI). With an unreadable root the cache may be stale (missed gesture-nav
                // Home event), and blocking blind used to flash the cover over the home
                // screen — wait for a tick that can actually see what's on screen.
                if (actual != null && actual != packageName) handleAppBlock(pkg)
            } else if (overlay.isAppBlock && !shouldBlock(pkg)) {
                // The condition ended while the cover was up → release without an app switch.
                // (Acting only on this transition also avoids re-recording an "attempt" every
                // tick; web/purchase/Shorts covers are owned by their own scans — hands off.)
                lastBlockedPkg = null
                overlay.remove()
            }
            if (recheckMatters(pkg)) handler.postDelayed(this, RECHECK_MS)
        }
    }

    /** Whether a later re-check of [pkg] could change the blocking outcome — i.e. the app is
     *  covered by any rule/schedule, a session is running, a keyword lockout is ticking, or
     *  a block cover is up. */
    private fun recheckMatters(pkg: String): Boolean =
        overlay.isShowing ||
            keywordLockoutRemaining(pkg) > 0L ||
            rules[pkg]?.isBlocked == true ||
            // Allowlist mode: any non-allowed app can flip (e.g. a Pomodoro break ending), so
            // keep re-checking while Quick Block is enforcing.
            (allowlistMode && quickBlockActive() && rules[pkg]?.isAllowed != true) ||
            QuickSession.state(this).active ||
            schedules.any { it.enabled && pkg in it.packages }

    /** Millis left on [pkg]'s keyword lockout, or 0 when it isn't locked. */
    private fun keywordLockoutRemaining(pkg: String): Long = synchronized(keywordLockouts) {
        ((keywordLockouts[pkg] ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /** The word that triggered [pkg]'s lockout, if still remembered (in-memory only). */
    private fun keywordLockoutWord(pkg: String): String? = synchronized(keywordLockouts) {
        keywordLockoutWords[pkg]
    }

    /** Locks [pkg] for [KEYWORD_LOCKOUT_MS] after a blocked [word] was caught in it. */
    private fun addKeywordLockout(pkg: String, word: String?) {
        val now = System.currentTimeMillis()
        synchronized(keywordLockouts) {
            keywordLockouts.entries.removeAll { it.value <= now }
            keywordLockoutWords.keys.retainAll(keywordLockouts.keys)
            keywordLockouts[pkg] = now + KEYWORD_LOCKOUT_MS
            if (word != null) keywordLockoutWords[pkg] = word else keywordLockoutWords.remove(pkg)
            SettingsStore.setKeywordLockouts(applicationContext, keywordLockouts.toMap())
        }
    }

    /** One-shot: templates used to inject app-name words (e.g. "youtube", "twitter") into the
     *  blocked-words table, where innocent mentions tripped blocks everywhere. Templates no
     *  longer carry words; this removes the historic nine once. Re-added words stay (flag). */
    private fun purgeTemplateWordsOnce() {
        if (SettingsStore.templateWordsPurged(this)) return
        scope.launch {
            val dao = BlockerDatabase.get(applicationContext).blockedKeywordDao()
            listOf(
                "youtube", "instagram", "tiktok", "facebook", "twitter",
                "reddit", "snapchat", "netflix", "twitch",
            ).forEach { dao.delete(BlockedKeyword(it)) }
            SettingsStore.setTemplateWordsPurged(applicationContext)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // The service is rebound right after an update installs, so detect it here too —
        // the pause arms even if the app itself isn't opened.
        UpdatePause.checkVersionChange(this)
        browserPackages = findBrowserPackages(this)
        launcherPackages = findLauncherPackages(this)
        keywordsEverywhere = SettingsStore.keywordsEverywhere(this)
        adultPackOn = SettingsStore.adultWordsPack(this)
        updatePaused = SettingsStore.updatePaused(this)
        allowlistMode = SettingsStore.quickBlockAllowlist(this)
        essentialPackages = findEssentialPackages(this)
        // Restore unexpired keyword lockouts — a service rebind must not unlock an app early.
        val now = System.currentTimeMillis()
        synchronized(keywordLockouts) {
            keywordLockouts.putAll(SettingsStore.keywordLockouts(this).filterValues { it > now })
        }
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
                SettingsStore.KEY_QUICK_BLOCK_ALLOWLIST ->
                    allowlistMode = SettingsStore.quickBlockAllowlist(this)
            }
        }.also { sp.registerOnSharedPreferenceChangeListener(it) }
        registerPackageChangeReceiver()
        registerUnlockReceiver()
        purgeTemplateWordsOnce()
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
            focusBootCount = focus?.bootCount ?: -1
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
        // Warm the block overlay off the connect path (inflate only, NOT addView), so the
        // very first block doesn't pay layout inflation while the blocked app is visible.
        handler.post { overlay.warmUp(::onCoverDismissed) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Everything below runs inside the safety net: an exception escaping this callback would
        // kill the process and end ALL blocking until the user noticed. See Guarded.kt.
        guarded(applicationContext, "event") { handleEvent(event) }
    }

    private fun handleEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString()
        if (pkg == packageName) return // never act on ourselves
        // Proof of life for the watchdog: "switched on" is not the same as "still working".
        // Self-throttled to one write a minute (ServiceHealth), so this costs a comparison.
        ServiceHealth.recordEvent(applicationContext)

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                onForegroundChanged(pkg, event.className?.toString())
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            // Scrolling doesn't emit content-change events in every app — listen to the
            // scroll itself so new text coming into view is scanned while the user scrolls.
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // Fast path: a NEW app's content events usually beat its window-state event,
                // so acting here covers a blocked app sooner (less visible flash). Confirm
                // with the real active window before trusting the event's package, so a
                // background repaint (widget host, notification) can't hijack the cache —
                // the binder call only happens on a package change, not per content event.
                if (pkg != null && pkg != lastForegroundPkg &&
                    rootInActiveWindow?.packageName?.toString() == pkg
                ) {
                    onForegroundChanged(pkg, event.className?.toString())
                } else {
                    // The dangerous Settings pages often fill in their title/labels on a
                    // content event after the window opens, so re-check the guard here too.
                    if (pkg != null) handleStrictSettingsGuard(pkg, event.className?.toString())
                    scheduleWebScan()
                    scheduleShortsScan()
                }
            }
        }
    }

    /** Everything that runs when the foreground app changes: launch counting, location
     *  freshness, the guard→purchase→app-block chain, re-check re-arm and the web/Shorts
     *  scan scheduling. Called from the window-state branch and from the content-changed
     *  fast path; the `pkg != lastForegroundPkg` guard keeps recordOpen at exactly one
     *  count per open regardless of which event type wins the race. */
    private fun onForegroundChanged(pkg: String?, className: String?) {
        // Keep a live app-block cover rock-solid. A blocked app runs behind the (non-focusable)
        // cover and can spit out stray windows — a system/floating popup, a splash, a different-
        // package sub-window — whose window-state event carries a package that isn't blocked.
        // Acting on it would set lastForegroundPkg to that stray package and tear the cover down
        // (handleAppBlock → blockReason null → removeBlockOverlay), then the blocked app's own
        // window returns and re-blocks: the "disappears ~2s then reblocks" flicker. Ignore any
        // such new package unless it's the confirmed active window (a real switch — our overlay
        // is FLAG_NOT_FOCUSABLE so it never holds focus) or a launcher (Home is always honored,
        // so the user can't be trapped).
        if (overlay.isShowing && overlay.isAppBlock &&
            pkg != null && pkg != lastForegroundPkg &&
            !isLauncherPkg(pkg) &&
            rootInActiveWindow?.packageName?.toString() != pkg
        ) return
        // A window-state event is NOT proof that its package is what the user is looking at.
        // Background apps emit them routinely — a message arriving, a service window, an
        // activity transitioning behind whatever is actually in front. The content-changed
        // branch in handleEvent has always confirmed against the real active window before
        // trusting a package ("so a background repaint can't hijack the cache"); this path
        // never did.
        //
        // In Blocklist mode that was survivable: a stray package usually has no rule, so it
        // only tore a cover down. In Allowlist mode EVERY app is blockable, so it cut both
        // ways — a stray event from a non-allowed app threw a cover over the user's real
        // screen (including our own UI), and one from an allowed app removed a legitimate
        // cover, which the real app's next event re-raised. That was the residual flashing.
        //
        // stillOnScreen is deliberately permissive: an unreadable window tree, or our own
        // cover, both count as yes — so this only rejects a package the tree positively
        // contradicts, and an app that hides its tree is never left unblocked. Launchers stay
        // exempt for the same reason as the guard above: Home must always be honored.
        // (Checked even when pkg == lastForegroundPkg: while our own UI is in front the cache is
        // deliberately stale — handleEvent ignores our package — so a stray event from the app
        // the cache still points at would otherwise sail straight through. isLauncherPkg is
        // second because it can cost a PackageManager query, and only a rejection needs it.)
        if (pkg != null && !stillOnScreen(pkg) && !isLauncherPkg(pkg)) {
            // The Strict escape-hatch guard still runs: it is about a dangerous page *opening*,
            // it has its own bounce throttle, and being early there is the safe direction.
            handleStrictSettingsGuard(pkg, className)
            // A real switch can simply have lost the race with the window tree, so re-read the
            // tree shortly rather than waiting for whatever event happens to come next.
            handler.removeCallbacks(confirmForegroundRunnable)
            handler.postDelayed(confirmForegroundRunnable, CONFIRM_MS)
            return
        }
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
            if (!handleStrictSettingsGuard(pkg, className) &&
                !handlePurchaseBlock(pkg, className)
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
        return !isLauncherPkg(pkg) && pkg !in KEYWORD_SCAN_EXCLUDED
    }

    /** Debounce: run the scan once events pause, but never later than the max-wait cap —
     *  a page that never goes quiet (animations, video, continuous scrolling) used to
     *  postpone the scan indefinitely because every event restarted the timer.
     *  Not scheduled for excluded packages (the launcher and System UI are the highest-churn
     *  event sources) or when nothing would be scanned — the scan would no-op there, and
     *  content/text events fire constantly in every app. */
    private fun scheduleWebScan() {
        handler.removeCallbacks(webScanRunnable)
        if (!shouldScanPkg(lastForegroundPkg)) {
            // Leaving a scannable app: a scan queued there (or already running) must not
            // put its cover up over Home/Settings.
            webScanJob?.cancel()
            webScanQueuedAt = 0L
            return
        }
        val now = System.currentTimeMillis()
        if (webScanQueuedAt == 0L) webScanQueuedAt = now
        val delay =
            if (now - webScanQueuedAt >= WEB_SCAN_MAX_WAIT_MS) 0L else WEB_SCAN_DEBOUNCE_MS
        handler.postDelayed(webScanRunnable, delay)
    }

    /** Debounced YouTube-Shorts check (quicker than the web scan so Shorts is caught fast).
     *  Only runs while Quick Block is active AND YouTube is foreground (the browser
     *  youtube.com/shorts case is the web scan's job), so other apps' events cost nothing. */
    private fun scheduleShortsScan() {
        if (!SettingsStore.blockYoutubeShorts(this) || !quickBlockActive() ||
            lastForegroundPkg != YOUTUBE_PKG
        ) {
            if (shortsCovering) { shortsCovering = false; overlay.remove() }
            return
        }
        handler.removeCallbacks(shortsScanRunnable)
        handler.postDelayed(shortsScanRunnable, 250)
    }

    // --- App blocking (unchanged behaviour) ---

    private fun handleAppBlock(pkg: String) {
        val reason = blockReason(pkg)
        if (reason == null) {
            // Keep a Shorts cover up even though the whole app isn't blocked — the shorts
            // scan owns adding/removing it as the user moves in and out of Shorts.
            if (pkg == YOUTUBE_PKG && shortsCovering) { lastBlockedPkg = null; return }
            lastBlockedPkg = null
            overlay.remove() // left the blocked app — take the cover down
            return
        }
        val now = System.currentTimeMillis()
        if (pkg == lastBlockedPkg && now - lastBlockAt < 1500) return
        lastBlockedPkg = pkg
        lastBlockAt = now
        showBlockScreen(title = reason.title, message = reason.message, packageName = pkg, counterKey = pkg)
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
        // Not while the exit watcher is holding a cover, though: guard covers aren't app blocks,
        // so this would pull one out from under a "Got it" that is still trying to get the user
        // out of Settings.
        handler.postDelayed({ if (!overlay.isAppBlock && !exiting()) overlay.remove() }, 1500)
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

    private fun shouldBlock(pkg: String): Boolean = blockReason(pkg) != null

    /**
     * Gathers the live state the decision needs and hands it to [decideBlock], which holds the
     * rules themselves (and is unit-tested — see BlockDecisionTest). Everything expensive is a
     * lambda, so a package with no rules and no schedules costs nothing beyond the flags.
     */
    private fun blockReason(pkg: String): BlockReason? {
        val session = QuickSession.state(this)
        val strict = strictRemaining() > 0L
        val now = System.currentTimeMillis()
        if (DEBUG) Log.d(TAG, "blockReason $pkg schedules=${schedules.size} opens=${LaunchCounter.opensToday(this, pkg)}")
        return decideBlock(
            BlockInputs(
                pkg = pkg,
                updatePaused = updatePauseActive(),
                lockoutRemainingMs = keywordLockoutRemaining(pkg),
                lockoutWord = keywordLockoutWord(pkg),
                strict = strict,
                // Enforcing when Strict, or a running Timer/Pomodoro says "block now", or
                // (no session) when not paused. Same gate in Blocklist and Allowlist mode.
                quickEnforcing = strict ||
                    if (session.active) session.blockingNow else !SettingsStore.quickBlockPaused(this),
                rule = rules[pkg],
                allowlistMode = allowlistMode,
                isEssential = { isEssentialAllowed(pkg) },
                schedules = schedules,
                isUnsupportedBrowser = pkg in browserPackages && pkg !in SUPPORTED_BROWSERS,
                unsupportedBrowserBlockingActive = {
                    SettingsStore.blockUnsupportedBrowsers(this) &&
                        (userKeywords.isNotEmpty() || adultPackOn || SettingsStore.blockAdult(this))
                },
                usedMinutesToday = { UsageTracker.usedMinutesToday(this, it) },
                opensToday = { LaunchCounter.opensToday(this, it) },
                scheduleConditionMet = { s ->
                    when (s.type) {
                        ScheduleType.TIME -> inTimeWindow(s, now)
                        ScheduleType.WIFI -> onMatchingWifi(s.wifiSsid)
                        ScheduleType.LOCATION -> inLocation(s)
                        else -> false // usage/launch limits are counted, not conditions
                    }
                },
                scheduleLabel = ::schedLabel,
                hourMinuteLabel = ::hm,
            )
        )
    }

    /** "your “Work” schedule", or just "your schedule" when it has no name. */
    private fun schedLabel(s: Schedule): String =
        if (s.name.isBlank()) "your schedule" else "your “${s.name.trim()}” schedule"

    /** Minutes-from-midnight → "9:00" / "17:30". */
    private fun hm(m: Int): String = "%d:%02d".format(m / 60, m % 60)

    /** Launcher check that self-heals after a default-launcher change: the set is built at
     *  service start, so on an unknown package it re-detects (throttled) before declaring it
     *  not-a-launcher, caching negatives to keep the hot paths cheap. Thread-safe. */
    private fun isLauncherPkg(pkg: String): Boolean {
        if (pkg in launcherPackages) return true
        if (pkg in knownNonLauncherPkgs) return false
        val now = SystemClock.elapsedRealtime()
        var looked = false
        if (now - lastLauncherRefreshAt >= LAUNCHER_REFRESH_MS) {
            lastLauncherRefreshAt = now
            val found = findLauncherPackages(this)
            if (found.isNotEmpty()) {
                looked = true
                launcherPackages = found
                // A fresh, trustworthy answer retires every earlier guess — otherwise a package
                // written off before the set was populated stays written off.
                knownNonLauncherPkgs = emptySet()
                if (pkg in found) return true
            }
        }
        // Only remember a NO that was actually established. Caching one from a throttled lookup
        // (we never asked) or an empty result (the query failed — findLauncherPackages swallows
        // errors as emptySet) is permanent: the cache is otherwise only cleared when a package
        // is installed or removed. Getting this wrong for the home package means Allowlist mode
        // blocks the home screen for the rest of the service's life.
        if (looked) knownNonLauncherPkgs = knownNonLauncherPkgs + pkg
        return false
    }

    /** True when [pkg] must never be blocked in Allowlist mode: ourselves, the launcher, the
     *  current keyboard, and the core system/dialer set. Keeps the phone usable. */
    private fun isEssentialAllowed(pkg: String): Boolean =
        pkg == packageName ||
            pkg in essentialPackages ||
            pkg == currentImePackage(this) ||
            isLauncherPkg(pkg)

    /**
     * Browsers can be installed/removed after the service starts; re-detect them on package
     * changes so "Block unsupported browsers" can't be bypassed by installing a new browser.
     */
    private fun registerPackageChangeReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                scope.launch {
                    browserPackages = findBrowserPackages(applicationContext)
                    launcherPackages = findLauncherPackages(applicationContext)
                    knownNonLauncherPkgs = emptySet() // a new install may be a launcher
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

    /** Counts phone unlocks (for Insights "pickups"), and — on screen-off — clears transient
     *  blocking state so an unlock never flashes a stale cover over Home. USER_PRESENT/
     *  SCREEN_OFF can't be static receivers on modern Android, so we register at runtime in
     *  this always-on service. onReceive runs on the main thread, so overlay ops are safe. */
    private fun registerUnlockReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_USER_PRESENT -> UnlockCounter.recordUnlock(applicationContext)
                    Intent.ACTION_SCREEN_OFF -> onScreenOff()
                }
            }
        }
        ContextCompat.registerReceiver(
            this, receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        unlockReceiver = receiver
    }

    /** Screen turned off: drop transient blocking state so the next unlock starts clean and
     *  can't flash a cover over Home from a package that was foreground before the lock. A
     *  genuinely blocked app is re-blocked by its own window-state event when reopened.
     *  Shorts covers are owned by their own scan — left alone. */
    private fun onScreenOff() {
        if (shortsCovering) return
        handler.removeCallbacks(webScanRunnable)
        handler.removeCallbacks(recheckRunnable)
        handler.removeCallbacks(confirmForegroundRunnable)
        webScanJob?.cancel()
        webScanQueuedAt = 0L
        // Stop trying to send a screened-off phone Home, and release the attempt dedup: the
        // next unlock starts clean, so a fresh open of the app is a fresh attempt.
        if (exiting()) finishExit(left = true)
        if (overlay.isShowing) {
            lastBlockedPkg = null
            overlay.remove()
        }
        lastForegroundPkg = null
        lastWebText = null
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
        val minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return scheduleWindowContains(
            s.daysMask, dayBit, minutes, s.startMinutes, s.endMinutes,
        )
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
        // Auto-blocking a blocked app's website is a Blocklist concept; in Allowlist mode the
        // allowed browser is allowed and the user's own keywords still apply.
        if (allowlistMode || !quickBlockActive()) return emptyList()
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
        // A full block cover is already up — everything beneath it is unreachable, so there
        // is nothing new to block; scanning behind it would only re-fire on the covered
        // page's churn (or on a keyword in the covered app's own UI) and re-count the entry.
        // Shorts covers stay with their own scan, which adds/removes them as the user scrolls.
        if (overlay.isShowing && !shortsCovering) return
        // Just dismissed a cover ("Got it" → HOME): the page stays on screen during the
        // transition. Skip everything — including the lockout fast path below, which used to
        // redraw the cover over the departing app (a visible flash) — WITHOUT touching
        // lastWebText, so detection re-arms the moment the suppression ends. Any dismissal
        // suppresses the scan (key-agnostic, as before), on the same extended window as
        // showBlockScreen (tablets: HOME can land slowly).
        if (dismissedKey != null && withinDismissWindow()) return
        // Under a keyword lockout the app is blocked outright — cover it on any content
        // event, no text matching. This path fires from cached state, so it demands positive
        // confirmation that the locked app is REALLY the visible one (an unreadable root is
        // NOT enough — after a missed gesture-nav Home event the cache goes stale and this
        // used to flash the cover over the home screen).
        if (keywordLockoutRemaining(pkg) > 0L) {
            withContext(Dispatchers.Main) {
                if (lastForegroundPkg == pkg &&
                    rootInActiveWindow?.packageName?.toString() == pkg
                ) handleAppBlock(pkg)
            }
            return
        }
        val isBrowser = pkg in browserPackages
        val text = extractVisibleText(::isLauncherPkg)
        if (DEBUG) Log.d(TAG, "scan[$pkg browser=$isBrowser]: ${text.length} chars: ${text.take(120)}")
        if (text.isBlank()) return
        if (text == lastWebText) return
        // The site the user is actually ON (browsers only) — keyword matching prefers it
        // over the page text so a page merely mentioning a blocked word doesn't block.
        val url = if (isBrowser) extractBrowserUrl(pkg) else null

        // YouTube Shorts opened in a browser (youtube.com/shorts) — while Quick Block is active.
        if (isBrowser && SettingsStore.blockYoutubeShorts(applicationContext) && quickBlockActive() &&
            (url ?: text.lowercase()).contains("youtube.com/shorts")
        ) {
            lastWebText = text
            withContext(Dispatchers.Main) {
                if (lastForegroundPkg == pkg && stillOnScreen(pkg)) {
                    showBlockScreen(title = "Shorts blocked",
                        message = "YouTube Shorts is blocked.", packageName = null,
                        counterKey = CoverGate.SHORTS_KEY)
                } else lastWebText = null // left during the scan — don't cover what's there now
            }
            return
        }

        // Browsers get the full filter (user words + adult word pack + blocked apps' domains +
        // adult site list); user + social keywords match the omnibox URL when one was read
        // (page text otherwise), while the adult layers always match the page text. Other
        // apps match the user's own words + the adult word pack — the adult domains/keywords
        // are URL/heuristic lists that don't make sense against arbitrary app UI text, but
        // the pack is whole-word matched so it's safe everywhere.
        // After-update pause: the user's own words pause with everything else; only the
        // adult layer (pack + adult sites) keeps matching.
        val ownWords = if (updatePauseActive()) emptyList() else userKeywords
        val hit = if (isBrowser) {
            filter.check(text, url, ownWords, autoSocialKeywords(), adultPackOn, SettingsStore.blockAdult(applicationContext))
        } else {
            filter.check(text, url = null, ownWords, siteKeywords = emptyList(), adultPackOn, blockAdult = false)
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
            if (lastForegroundPkg == pkg && stillOnScreen(pkg)) {
                // A blocked WORD (or adult content) locks the whole app for a while — "Got it"
                // must not be a free pass back in. A blocked WEBSITE is gentler: cover the page
                // so the site stays blocked every visit, but don't lock the whole browser.
                if (!hit.site) addKeywordLockout(pkg, hit.word)
                // Recorded under "web" (Insights shows one "Websites" row), but the OFFENCE is
                // this app: the lockout just added makes handleAppBlock raise a second,
                // package-keyed "Locked" cover moments from now, and one blocked word must not
                // count as two attempts. A site hit adds no lockout, so nothing follows it.
                showBlockScreen(
                    title = hit.title, message = hit.message, packageName = null,
                    counterKey = "web", offenceKey = pkg,
                )
            } else lastWebText = null // left during the scan — don't cover what's there now
        }
    }

    /** What the exit watcher can tell about the app it is trying to get the user out of. */
    private enum class ExitView {
        /** Positively somewhere else — the cover has done its job. */
        LEFT,

        /** Positively still in the app — worth holding the cover and asking for HOME again. */
        STILL_THERE,

        /** No idea. Must NOT be treated as STILL_THERE; see [exitView]. */
        BLIND,
    }

    /**
     * What we can see about whether the user is off [target]. Three answers, not two — and that
     * is the whole point. "Can't tell" used to be lumped in with "still there", so the cover was
     * held for the full give-up window on no evidence, which parked it over the home screen
     * after every "Got it" (worst in Allowlist mode, where nearly everything is blocked and
     * "Got it" gets tapped constantly).
     *
     * Both signals go blind together on this device at exactly the wrong moment: our own
     * (non-focusable) cover can itself report as the active window, which says nothing about
     * what is behind it, and the event-fed cache doesn't move because gesture-nav Home often
     * emits no window-state event at all. Hence BLIND, handled by letting go quickly rather
     * than holding on.
     *
     * Note this is not the inverse of [stillOnScreen], which answers a different question
     * ("may I cover this?", where unreadable must mean "yes, carry on blocking").
     * Main thread only.
     */
    private fun exitView(target: String): ExitView {
        val active = rootInActiveWindow?.packageName?.toString()
        // A readable window that isn't ours settles it either way.
        if (active != null && active != packageName) {
            return if (active == target) ExitView.STILL_THERE else ExitView.LEFT
        }
        // Our own UI is in front (not merely our cover): they are out of the app. See OwnUi.
        if (OwnUi.visible) return ExitView.LEFT
        // The event-fed cache is evidence only when it has actually moved off the target.
        lastForegroundPkg?.let { if (it != target) return ExitView.LEFT }
        // The active window is our own cover (or unreadable) and the cache hasn't moved:
        // nothing here says whether the app is still in front.
        return ExitView.BLIND
    }

    /** Best-effort "is [pkg] still what the user sees" — guards covers fired from cached state
     *  (the debounced scan) after a gesture-nav Home that produced no window-state event. An
     *  unreadable root counts as yes, and so does our own *cover* (the app is still behind it).
     *  Our own *UI* does not: the user walked into AppBlocker, so the cached app is not what
     *  they're looking at, and covering it here landed a block screen on our own screens. The
     *  two are the same package, so [OwnUi] is the only way to tell. Main thread only. */
    private fun stillOnScreen(pkg: String): Boolean {
        val actual = rootInActiveWindow?.packageName?.toString() ?: return true
        if (actual == pkg) return true
        return actual == packageName && !OwnUi.visible
    }

    /**
     * Covers the YouTube Shorts player (when the toggle is on) while leaving the rest of the
     * app usable. Adds the cover when a Short is on screen and removes it the moment the user
     * scrolls/navigates out of Shorts.
     */
    private suspend fun scanShorts() {
        // A full app-block cover is already up (YouTube itself is blocked) — Shorts-only
        // blocking is moot, and the overlay is not-focusable so this scan would still read
        // the YouTube UI behind it, re-keying the cover to "shorts": a visible repaint plus
        // a double-counted attempt. Leave the app-block cover alone.
        if (overlay.isShowing && overlay.isAppBlock) return
        if (!SettingsStore.blockYoutubeShorts(applicationContext) || !quickBlockActive() ||
            lastForegroundPkg != YOUTUBE_PKG
        ) {
            if (shortsCovering) {
                shortsCovering = false
                withContext(Dispatchers.Main) { overlay.remove() }
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
                    counterKey = CoverGate.SHORTS_KEY,
                )
            }
        } else if (!onShorts && shortsCovering) {
            shortsCovering = false
            withContext(Dispatchers.Main) { overlay.remove() }
        }
    }

    /** True while a just-dismissed cover's re-show should stay suppressed — see [CoverGate],
     *  which owns the rules (and is unit-tested, see CoverGateTest). */
    private fun dismissSuppressed(counterKey: String): Boolean = CoverGate.suppressed(
        counterKey, dismissedKey, dismissedPkg, lastForegroundPkg,
        System.currentTimeMillis() - dismissedAt,
    )

    /** The key-agnostic timing half, for the web scan. */
    private fun withinDismissWindow(): Boolean = CoverGate.inGrace(
        dismissedPkg, lastForegroundPkg, System.currentTimeMillis() - dismissedAt,
    )

    /**
     * Raises the block cover. [counterKey] is what the attempt is *recorded* under (a package,
     * or a synthetic key like "web" that Insights renders as its own row); [offenceKey] is what
     * counts as one event, and defaults to the same thing.
     *
     * They differ where one offence legitimately produces two covers under two names: a blocked
     * word raises the "web" cover AND locks the whole app, so the package-keyed "Locked" cover
     * follows seconds later. Passing the app as the offence for the word cover makes the pair
     * count once, while each still gets recorded where it belongs.
     */
    private fun showBlockScreen(
        title: String,
        message: String?,
        packageName: String?,
        counterKey: String,
        offenceKey: String = counterKey,
    ) {
        // One cover = one recorded entry. The page/app behind a cover keeps emitting events
        // (feeds churn, activities transition), and each used to re-record an "attempt" and
        // re-roll the quote — so a cover already up for this key means there's nothing to do.
        if (overlay.isShowing && overlay.counterKey == counterKey) return
        // Same guard for a JUST-dismissed cover: Close goes HOME, but the blocked app stays
        // on screen for the transition and would re-block/re-count immediately.
        if (dismissSuppressed(counterKey)) return
        // Is this a NEW block, or the same one being drawn again because the user never got
        // out of the app? Only a new one records an attempt (the cover reads the running total
        // itself) and rolls a fresh quote — otherwise one open read as two.
        val now = System.currentTimeMillis()
        val fresh = CoverGate.shouldCount(
            offenceKey, lastCountedOffence, now - lastCountedAt, resumingOffence,
        )
        if (offenceKey == resumingOffence) resumingOffence = null // one-shot: this was the redraw
        if (fresh) {
            AttemptCounter.record(applicationContext, counterKey)
            lastCountedOffence = offenceKey
            lastCountedAt = now
        }
        val label = packageName?.let { loadLabel(it) }
        val msg = message ?: label?.let { "$it is blocked" } ?: "This is blocked right now."
        // Instant overlay; fall back to the Activity only if the overlay can't be drawn.
        // Only app-rule blocks pass a package (see isAppBlock).
        val drawn = overlay.show(
            packageName = packageName,
            title = title,
            message = msg,
            counterKey = counterKey,
            isAppBlock = packageName != null,
            onClose = ::onCoverDismissed,
            iconLoader = ::loadIcon,
            freshBlock = fresh,
        )
        if (!drawn) {
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
                }
            )
        }
        // (Re)arm the mid-use re-check whenever a cover goes up: after a dismissal it
        // re-blocks a locked-out app even when the page is static and emits no events.
        handler.removeCallbacks(recheckRunnable)
        handler.postDelayed(recheckRunnable, RECHECK_MS)
    }

    /**
     * "Got it" on the cover: remember what was dismissed (so the app still on screen during
     * the trip Home can't instantly re-block), ask the phone to go Home, and keep the cover up
     * until it actually gets there — see [exitRunnable].
     */
    private fun onCoverDismissed() {
        // Read before anything is torn down — remove() clears it.
        val wasShorts = overlay.counterKey == CoverGate.SHORTS_KEY
        dismissedKey = overlay.counterKey
        dismissedPkg = lastForegroundPkg
        dismissedAt = System.currentTimeMillis()
        lastBlockedPkg = null
        // Re-arm word detection: coming back to the page that was just blocked must
        // block again, not read as "already handled" via the text dedup.
        lastWebText = null
        if (wasShorts) {
            // The Shorts cover is scoped to the player, so it must NOT be held until the user
            // leaves YouTube — the rest of the app is meant to keep working. Hand ownership
            // back to its scan instead: without clearing this flag the scan believed Shorts
            // was still covered when the cover had gone, and since it only acts on the
            // not-covered → covered transition it never drew it again. Shorts was then
            // browsable until the user navigated out of Shorts and back in.
            shortsCovering = false
            overlay.remove()
            performGlobalAction(GLOBAL_ACTION_HOME)
        } else {
            // Everything else — a whole app, a page, the purchase sheet, a Settings page we
            // bounced out of — is held until the user is really off the app it covered, so a
            // swallowed HOME can't leave the thing we were covering exposed.
            startExit(dismissedPkg)
        }
    }

    /** Begins the trip Home for [pkg], holding the cover until we're off it. With no app to
     *  watch for, the cover just goes down as before. */
    private fun startExit(pkg: String?) {
        if (pkg == null) {
            overlay.remove()
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }
        exitTarget = pkg
        exitStartedAt = System.currentTimeMillis()
        exitHomeTries = 1
        performGlobalAction(GLOBAL_ACTION_HOME)
        handler.removeCallbacks(exitRunnable)
        handler.postDelayed(exitRunnable, EXIT_POLL_MS)
    }

    /** Ends the exit watch and takes the cover down. [left] is whether the user actually got
     *  off the blocked app: on a real exit the attempt dedup is released, so a deliberate
     *  re-open counts as the new attempt it is. [confirmed] says whether a non-exit was
     *  *seen* (the app really is still in front) rather than merely un-disproved — only a seen
     *  one may schedule anything, since acting on a package we can't vouch for is how covers
     *  ended up over the home screen. */
    private fun finishExit(left: Boolean, confirmed: Boolean = false) {
        exitTarget = null
        handler.removeCallbacks(exitRunnable)
        if (left) {
            lastCountedOffence = null
            resumingOffence = null
        } else if (confirmed) {
            // HOME never landed and the user IS still in the blocked app. The cover has to come
            // down (never trap anyone), so schedule the re-check for just after the dismiss
            // grace lapses — otherwise the app would stay uncovered until the ordinary 30s
            // tick. Mark that redraw as a resume so it lands as the SAME block: no new attempt,
            // no new quote, without the plain cooldown having to be long enough to reach it.
            resumingOffence = lastCountedOffence
            val untilGraceEnds = CoverGate.DISMISS_GRACE_STUCK_MS -
                (System.currentTimeMillis() - dismissedAt)
            handler.removeCallbacks(recheckRunnable)
            handler.postDelayed(recheckRunnable, untilGraceEnds.coerceAtLeast(0L) + 250L)
        }
        // Blind non-exit: schedule nothing. If the app really is still there it keeps emitting
        // events, and the ordinary re-check armed when the cover went up is the backstop.
        lastBlockedPkg = null
        overlay.remove()
    }

    /** Whether the exit watcher is currently holding a cover up. */
    private fun exiting(): Boolean = exitTarget != null

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
        handler.removeCallbacks(exitRunnable)
        handler.removeCallbacks(confirmForegroundRunnable)
        exitTarget = null
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
        overlay.remove()
        scope.cancel()
    }

    companion object {
        private const val TAG = "AppBlocker"
        private const val DEBUG = false // flip to true to log scans/blocks for debugging

        // How often to re-check the app the user is currently inside (mid-use enforcement).
        private const val RECHECK_MS = 30_000L
        // Web scan pacing: run once events pause for the debounce, but a churning page that
        // never pauses is still scanned at least every max-wait.
        private const val WEB_SCAN_DEBOUNCE_MS = 250L
        private const val WEB_SCAN_MAX_WAIT_MS = 700L
        // How long an app stays fully locked after a blocked word was caught in it.
        private const val KEYWORD_LOCKOUT_MS = 30 * 60_000L

        // The trip Home after "Got it": how often to check whether we're off the blocked app
        // yet, when to re-ask for HOME, and when to give up so the user can never be trapped.
        // Two give-ups, because "the app is still in front" and "we can't tell" are different
        // situations (see ExitView). The blind one is short: it bounds how long the cover can
        // sit over the home screen when HOME worked but nothing can confirm it, which is what
        // made the cover flash up on Home after every "Got it".
        private const val EXIT_POLL_MS = 200L
        private const val EXIT_RETRY1_MS = 600L
        private const val EXIT_RETRY2_MS = 1_400L
        private const val EXIT_GIVE_UP_MS = 2_500L
        private const val EXIT_BLIND_GIVE_UP_MS = 800L
        // How soon to re-read the window tree after rejecting an unconfirmed window-state
        // event, so a real app switch that lost the race still blocks promptly.
        private const val CONFIRM_MS = 200L

        // Throttle for isLauncherPkg's on-miss launcher re-detection.
        private const val LAUNCHER_REFRESH_MS = 30_000L

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

    }
}
