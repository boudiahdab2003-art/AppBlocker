package com.appblocker.service

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
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
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.appblocker.BuildConfig
import com.appblocker.R
import com.appblocker.data.AppLocale
import com.appblocker.data.Words
import com.appblocker.data.AppRule
import com.appblocker.data.AdminPrompt
import com.appblocker.data.AdminScreens
import com.appblocker.data.InstallPrompt
import com.appblocker.data.AttemptCounter
import com.appblocker.data.BlockMode
import com.appblocker.data.BlockLatency
import com.appblocker.data.BlockLog
import com.appblocker.data.BlockedKeyword
import com.appblocker.data.BlockerDatabase
import com.appblocker.data.DangerZone
import com.appblocker.data.DeviceBoot
import com.appblocker.data.FilterState
import com.appblocker.data.NetworkFilter
import com.appblocker.data.FocusState
import com.appblocker.data.GuardedDeadline
import com.appblocker.data.InstalledAppsRepository
import com.appblocker.data.LaunchCounter
import com.appblocker.data.NewAppWatcher
import com.appblocker.data.OffSwitchGuard
import com.appblocker.data.OwnUi
import com.appblocker.data.QuickSession
import com.appblocker.data.Schedule
import com.appblocker.data.ScheduleType
import com.appblocker.data.ServiceHealth
import com.appblocker.data.SessionClock
import com.appblocker.data.RuleSnapshot
import com.appblocker.data.SettingsStore
import com.appblocker.data.SilenceLog
import com.appblocker.data.StrictEdits
import com.appblocker.data.UnlockCounter
import com.appblocker.data.UpdatePause
import com.appblocker.data.WatcherDiagnostics
import com.appblocker.ui.BlockScreenActivity
import java.util.Calendar
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.retryWhen
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

    /** The cover's wording in the app's language. A property so every cover in this file reads
     *  the same way; see [Words.of] for why a Service cannot simply call `getString`. */
    private val words: Words get() = Words.of(this)


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
    // Resolved per use rather than held in a `lazy`: WebContentFilter.get() refuses to cache a
    // filter whose word lists failed to load, and a lazy here would have pinned that broken one
    // for the service's whole life — undoing the retry. After a successful load this is one
    // volatile read.
    private val filter: WebContentFilter get() = WebContentFilter.get(applicationContext)

    @Volatile private var rules: Map<String, AppRule> = emptyMap()
    // Whether [rules] has heard from Room yet. Until it has, the empty map means "not told", not
    // "nothing is blocked" — see RuleSnapshot, and invariant 11.
    @Volatile private var rulesLoaded = false
    // What was blocked last time Room spoke, read synchronously on connect so a rebind (boot,
    // update, revive, Second Space switch) enforces from the first event rather than from the
    // first emission.
    @Volatile private var blockedSnapshot: Set<String> = emptySet()
    // The armed danger-zone hour, or null. Held in memory so a block decision never costs a
    // prefs read; the deadline itself is what decides whether it is still running.
    @Volatile private var dangerZone: GuardedDeadline? = null
    /** The 24-hour wider-list window. Outlives [dangerZone] by design. */
    @Volatile private var dangerWideList: GuardedDeadline? = null
    /** Hosts this phone caught in two different browsers — see [DangerZone.learns]. */
    @Volatile private var learnedDomains: Set<String> = emptySet()
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

    /**
     * The subset of [browserPackages] that is really a browser rather than an app that opens its
     * own links — see [findRealBrowserPackages]. Used only by the "block unsupported browsers"
     * switch, because that is the one consumer for which a false positive means an ordinary app
     * is blocked outright.
     */
    @Volatile private var realBrowserPackages: Set<String> = emptySet()

    /** When [isRealBrowserPkg] last re-read an empty browser set, monotonically. */
    @Volatile private var lastRealBrowserRefreshAt = Long.MIN_VALUE / 2
    // Home-screen (launcher) apps — never keyword-scanned, see findLauncherPackages(this).
    @Volatile private var launcherPackages: Set<String> = emptySet()

    // Negative cache for isLauncherPkg, so its throttled re-detection only runs on new packages.
    @Volatile private var knownNonLauncherPkgs: Set<String> = emptySet()
    @Volatile private var lastLauncherRefreshAt = 0L
    // The current keyboard, cached for isTransientSurface's hot path — see imePackage().
    @Volatile private var cachedImePkg: String? = null
    @Volatile private var lastImeRefreshAt = 0L
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

    /**
     * True while the after-update pause suspends blocking.
     *
     * Strict always wins. [UpdatePause] no longer arms the pause at all while a session is running
     * (an update used to end the session instead), so this guard is now for the other order: the
     * pause is armed, and *then* a Strict session is started before Reactivate is tapped. Starting
     * one has to mean blocking, whatever the pause says.
     */
    private fun updatePauseActive(): Boolean =
        updatePaused && strictRemaining() <= 0L

    private fun strictRemaining(): Long =
        SessionClock.remaining(
            focusRealtimeStart, focusRealtimeEnd, focusWallStart, focusWallEnd,
            focusBootCount, DeviceBoot.count(applicationContext),
        )

    /**
     * The clock every in-memory timer in this service measures elapsed time against.
     *
     * All of them are stopwatches — "have 1.5 seconds passed since the last bounce?" — and all
     * of them used to read `System.currentTimeMillis()`, which the user (and the network) can
     * move at will. A **backward** change makes every one of those subtractions negative, and
     * since a negative interval reads as "no time has passed at all", each timer sticks in
     * whichever state suppresses action:
     *
     *  - [lastGuardBounceAt]: the Strict-Mode guard believes it is already bouncing, so it stops
     *    bouncing — leaving the Accessibility page reachable and the whole service switchable
     *    off. The Strict *session* is clock-proof ([strictRemaining] uses [SessionClock]), so
     *    this throttle was the way around it.
     *  - [lastBlockAt]: `handleAppBlock` returns early, so the app-block cover is never raised.
     *  - [webScanQueuedAt]: the debounce's max-wait never fires, so a never-quiet page postpones
     *    the word/site scan indefinitely — the exact bug that cap was added to fix.
     *  - [dismissedAt] / [lastCountedAt]: the post-dismiss grace and the attempt-count dedup
     *    never lapse, so nothing re-blocks and nothing re-counts.
     *  - [exitStartedAt]: the exit watcher never reaches its give-up, holding the cover up
     *    indefinitely — the one thing invariant 7 says must never happen.
     *
     * A forward change does the opposite: every window lapses at once, which is the v1.95
     * double-block and double-count returning.
     *
     * Deliberately NOT for everything. Persisted deadlines can't use this (it resets on reboot) —
     * those go through [com.appblocker.data.GuardedDeadline] or [SessionClock], which anchor to
     * both clocks. Genuine calendar facts must stay on the wall clock: schedule windows (see
     * [blockReason]), day stamps, and the notification throttles, where a wrong clock only means
     * notifying again.
     */
    private fun stopwatchNow(): Long = SystemClock.elapsedRealtime()

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
    // WHERE inside that app the dismissed cover was — the window class name for an app cover,
    // the host for a page cover. The grace is released when the user is demonstrably somewhere
    // else in the same app, which is the only evidence available when HOME is swallowed (or,
    // for a page cover, when "Got it" deliberately keeps them in the browser). See
    // CoverGate.graceSpentBy; [currentDest] picks the matching half so a class name is never
    // compared against a host.
    @Volatile private var dismissedDest: String? = null
    // Whether this dismissal has already been counted as one the watcher went quiet through.
    // Per dismissal, not per event: a deaf spell that declines forty times is one page the user
    // was reading, and counting each decline would drown the number it exists to make legible.
    @Volatile private var dismissalCountedDeaf = false
    // Same, for the pre-rules window: one count per bind, not one per decision.
    @Volatile private var unreadyCounted = false
    // The current destination, tracked in the two units the covers use.
    @Volatile private var lastWindowClass: String? = null
    @Volatile private var lastBrowserHost: String? = null

    /** The app a page cover's BACK exit was last fired in, and when — so a second "Got it" there
     *  leaves instead of pressing back again. See [CoverGate.backExitFailed]. */
    @Volatile private var lastBackExitPkg: String? = null
    @Volatile private var lastBackExitAt: Long = 0L
    @Volatile private var dismissedAt = 0L

    /** Whether the last dismissal stepped BACK inside the app rather than asking the phone to go
     *  Home — which decides how long the grace after it runs. See [CoverGate.inGrace]. Recorded
     *  from the branch that actually fired, not derived from the key: a page cover falls back to
     *  HOME once BACK has failed, and then it needs the long window like any other trip Home. */
    @Volatile private var dismissedViaBack = false

    /** When the accessibility event being handled right now arrived, monotonically. The start of
     *  the interval [BlockLatency] records for a whole-app block. */
    @Volatile private var eventArrivedAt = 0L

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
            val since = stopwatchNow() - exitStartedAt
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
                // Carry the deferred event's className over — but ONLY when the tree resolved to
                // the same package it described, since a class name belongs to one app and must
                // never be applied to whatever else turned out to be in front.
                //
                // Trusting the tree over the event is right about the *package*; it was never
                // meant to discard the class name, which the tree cannot supply at all. Passing
                // null here silently disabled every className-dependent check on this path — and
                // handlePurchaseBlock is className-ONLY (`?: return false`), so an in-app purchase
                // sheet that lost the race with the window tree was never covered, and nothing
                // re-checks purchases afterwards. The Strict guard survived the same race only
                // because it has an on-screen-text fallback beside its className fast path.
                val carried = pendingClassName.takeIf { actual == pendingClassNamePkg }
                pendingClassName = null
                pendingClassNamePkg = null
                onForegroundChanged(actual, carried)
            }
        }
    }

    // The deferred event's class name and the package it described — see confirmForegroundRunnable.
    @Volatile private var pendingClassName: String? = null
    @Volatile private var pendingClassNamePkg: String? = null

    /**
     * The last ACTIVITY class seen for a package, and which package it was.
     *
     * Only a window-state event carries an activity name; a content or scroll event carries the
     * *view* that changed. Three of the four guard call sites are the latter, so the class the
     * guard was handed there said nothing — and `uninstallConfirmation` reads it to tell an
     * install from an uninstall. Nothing said "install", so the bounce went ahead on the only
     * evidence left: an installer screen naming us, which is what the "app installed" screen after
     * an update is (report #6, 21 Aug 2026).
     *
     * Remembered rather than re-derived, exactly as [pendingClassName] already is one path over:
     * a later event can confirm the package but can never recover the class.
     */
    @Volatile private var lastActivityClass: String? = null
    @Volatile private var lastActivityClassPkg: String? = null

    /** The activity class to reason about for [pkg]: this event's, when it named one, and the last
     *  one seen in the same package otherwise. See [namesAnActivity]. */
    private fun activityClassFor(pkg: String, className: String?): String? {
        val cn = className?.takeIf { namesAnActivity(it) }
        if (cn != null) {
            lastActivityClass = cn
            lastActivityClassPkg = pkg
            return cn
        }
        return lastActivityClass.takeIf { lastActivityClassPkg == pkg }
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

    // Apps where a blocked word was caught: the whole app stays locked — any page, any text —
    // until it expires. Guarded by its own lock (written from the background scan, read from the
    // main thread); mirrored to prefs so restarts don't lift it. The word that triggered each
    // one rides along in the record rather than in a second map, so the two can't drift.
    private val keywordLockouts = mutableMapOf<String, GuardedDeadline>()

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
            // Read before it is cleared: this is when the burst that led here began, which is
            // where the stopwatch for [BlockLatency] starts. The field already existed for the
            // debounce cap — the measurement needed no new bookkeeping, only a use for it.
            val queuedAt = webScanQueuedAt
            webScanQueuedAt = 0L
            webScanJob?.cancel()
            webScanJob = scope.launch { scanWebContent(queuedAt) }
        }
    }

    // The address-bar check that runs with NO debounce (see scheduleUrlScan). Separate job so
    // it can't be cancelled by, or cancel, the heavier text scan running alongside it.
    @Volatile private var urlScanJob: Job? = null

    /** Set when the address moved while a lookup was already in flight — see [scheduleUrlScan].
     *  Without it that event was simply dropped, and its page waited for the debounced scan. */
    @Volatile private var urlScanDirty = false
    // The omnibox text this fast path last decided on, so a page emitting a burst of content
    // events costs one node lookup rather than one per event. Null = decide again on the next
    // event; it is cleared everywhere lastWebText is, so coming back to a page re-checks it.
    @Volatile private var lastCheckedUrl: String? = null

    /**
     * When this service last connected, from `stopwatchNow()`. Only the settings guard reads it,
     * to recognise the seconds right after the user switched the service on — see
     * [OffSwitchGuard.justEnabled].
     *
     * `Long.MIN_VALUE / 2` rather than 0 so it reads as "long ago" before it is ever set: a device
     * a few seconds into its boot has a small `stopwatchNow()`, and starting from 0 would make
     * `now - it` small too, i.e. an accidental grace at boot.
     */
    @Volatile private var serviceConnectedAt = Long.MIN_VALUE / 2

    /**
     * **The last address actually read in a browser, kept for while the toolbar is hidden.**
     *
     * Browsers slide the address bar away as you scroll — Brave conspicuously so, which is how
     * this was found. With it off screen there is nothing to read, and [scanWebContent] handed the
     * filter a null URL, which it treats as *"skip the site layer"*. So scrolling down a blocked
     * site turned the site block off: the user is still on instagram.com, and the app has stopped
     * believing it. Chrome escaped this only because its toolbar is up when a page first loads, so
     * the block lands before there is anything to scroll.
     *
     * That is invariant 4 in `docs/BLOCKING_INVARIANTS.md`, in a new place: **a hidden toolbar is a
     * failed measurement, not evidence the user has left the site.** Remembering the last answer
     * is what turns "I can't see it" back into "I don't know, so assume what I knew".
     *
     * Bounded three ways so a memory can never become a phantom block: it is cleared on every
     * foreground change (a different app is a different question), replaced outright the moment a
     * different address IS read, and expired by [URL_MEMORY_MS]. In-browser navigation reveals the
     * toolbar in every Chromium build, so the replacement path is the one that normally ends it.
     */
    @Volatile private var rememberedUrl: String? = null
    @Volatile private var rememberedUrlPkg: String? = null
    @Volatile private var rememberedUrlAt = 0L

    // YouTube Shorts: when on, cover the Shorts player but leave the rest of YouTube usable.
    @Volatile private var shortsScanJob: Job? = null
    /**
     * Whether the cover currently up is the YouTube-Shorts one.
     *
     * Derived, never stored. This used to be a separate flag the Shorts scan set and cleared,
     * which made it a second source of truth about a thing the overlay already knows — and when
     * the two drifted, three different paths acted on the lie: scheduleShortsScan tore down a
     * real app-block cover, screen-off skipped its entire cleanup, and the re-check's
     * home-screen cleanup refused to run. Asking the overlay cannot drift.
     */
    private val shortsCovering: Boolean
        get() = overlay.isShowing && overlay.counterKey == CoverGate.SHORTS_KEY
    private val shortsScanRunnable = Runnable {
        guarded(applicationContext, "shortsScan") {
            shortsScanJob?.cancel()
            shortsScanJob = scope.launch { scanShorts() }
        }
    }

    /**
     * The walk that closes a Short after "Got it" and then leaves YouTube — see [ShortsExit].
     *
     * A job rather than a handler runnable because each step re-reads the window tree, which is a
     * bounded but real node walk (invariant 25) and has no business on the main thread. It reads on
     * a background thread and acts back on Main, the same shape as [scanShorts].
     */
    @Volatile private var shortsExitJob: Job? = null

    /**
     * Proof that the watcher is still alive **when nothing is happening on screen**.
     *
     * [ServiceHealth.recordEvent] only stamps from the event path, so a phone left alone for an
     * hour is indistinguishable from a watcher the phone killed an hour ago — which is why the
     * event-staleness check needs usage-stats and a two-hour wait before it dares say anything.
     * This ticks regardless.
     *
     * ⚠️ It writes its **own** key ([ServiceHealth.recordAlive]), never the event key. Stamping
     * the event key here would keep it permanently fresh and permanently disable the staleness
     * check in [protectionState] — a heartbeat that hides the very failure it was added to expose.
     *
     * It also re-posts our [AccessibilityServiceInfo] after a long silence. That is the one
     * recovery an app can attempt for a service that is still bound but has stopped receiving
     * events: it re-registers the event mask with the system. It cannot help when the service is
     * gone (this Runnable is gone with it) — that case is what [isConnected] is for.
     */
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            guarded(applicationContext, "heartbeat") {
                ServiceHealth.recordAlive(applicationContext)
                val silence = stopwatchNow() - lastEventReceivedAt
                // The end of the window the probe streak measures has to be wired to something,
                // or a streak of four sits at four for the life of the process and the next quiet
                // spell starts one failure from the verdict (invariant 33). An event arriving IS
                // the watcher working, and it is the only thing that may say so — see
                // ServiceHealth.probeFailStreak on why a successful nudge must not.
                if (silence < REVIVE_AFTER_SILENCE_MS) {
                    ServiceHealth.clearProbeStreak(applicationContext)
                }
                if (silence >= REVIVE_AFTER_SILENCE_MS &&
                    stopwatchNow() - lastReviveAttemptAt >= REVIVE_AFTER_SILENCE_MS
                ) {
                    lastReviveAttemptAt = stopwatchNow()
                    // Re-posting the info we already hold is a no-op on a healthy service, so
                    // this is safe to do blind; runCatching because an OEM throwing here must
                    // not take the heartbeat down with it.
                    //
                    // ⚠️ And it is COUNTED now. This is the app's only self-repair and it used to
                    // leave no trace whatsoever, so nothing could say whether the owner's watcher
                    // goes quiet often, or whether the nudge ever helps. It is the inside view of
                    // OutageLog's `aliveButDeaf`: a climbing count is a watcher that keeps going
                    // deaf while running, a flat zero alongside outages is one being killed
                    // outright. A nudge that THROWS is evidence too — the binding is already gone,
                    // not merely quiet.
                    // ⚠️ Read BEFORE the nudge. The nudge is the app trying to change the thing
                    // the probe is measuring, so measuring afterwards would be measuring the
                    // repair rather than the fault.
                    val readable = probeScreen()
                    val worked = runCatching { serviceInfo = serviceInfo }.isSuccess
                    ServiceHealth.recordRevive(applicationContext, worked)
                    // The pull half of liveness — see ServiceHealth.recordProbe. A nudge that
                    // threw is folded in here rather than counted as its own staleness arm: two
                    // counters that both mean "the watcher is deaf" is the drift shape this
                    // codebase has paid for more than any other.
                    ServiceHealth.recordProbe(applicationContext, ok = readable && worked)
                }
            }
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    /**
     * **Can this service still read the screen?** — asked, rather than inferred from silence.
     *
     * `rootInActiveWindow` is the cheapest question that only a service the framework is still
     * talking to can answer. Deliberately **not** `windows`: that returns an empty list without
     * `flagRetrieveInteractiveWindows`, which `accessibility_service_config.xml` does not declare,
     * so a probe built on it would report failure forever and look perfectly correct.
     *
     * ⚠️ **Every "can't tell" passes**, which is the same rule as `usedMinutesSinceLastEvent`
     * being null and the reason this can never cry wolf. There are exactly three, and each is a
     * state where a null tree is *expected* rather than evidence:
     * - the screen is off — nothing is drawing, so nothing is readable;
     * - the keyguard is up — the lock screen is not ours to read;
     * - no `PowerManager` at all, which is not a question about the watcher.
     *
     * Only a lit, unlocked screen with no readable window counts as a failure, and even then one
     * failure means nothing: `PROBE_FAIL_LIMIT` of them, three minutes apart, is the verdict.
     */
    private fun probeScreen(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return true
        if (!pm.isInteractive) return true
        val km = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        if (km?.isKeyguardLocked == true) return true
        return rootInActiveWindow != null
    }

    /** When an accessibility event last reached us, monotonically (invariant 9). */
    @Volatile private var lastEventReceivedAt = Long.MIN_VALUE / 2

    /** When the heartbeat last re-posted [serviceInfo], so a deaf service is nudged at a pace
     *  rather than on every single tick. */
    @Volatile private var lastReviveAttemptAt = Long.MIN_VALUE / 2

    // Periodic re-check of the app the user is sitting in, so time-based conditions take
    // effect mid-use instead of only on the next app switch: a daily limit crossing, a time
    // schedule starting (or ending — the same tick releases a stale block overlay), a
    // Pomodoro break starting/ending, a Timer or Strict session running out.
    private val recheckRunnable = object : Runnable {
        /**
         * ⚠️ **The re-post lives OUTSIDE the guard**, the way [heartbeatRunnable]'s does.
         *
         * It used to be the last line *inside* `runGuarded`, so a single swallowed throw anywhere
         * above it ended the loop for good — silently, and taking every mid-use enforcement with
         * it: a daily limit crossing, a schedule starting, a Pomodoro flip, and the release of a
         * cover whose reason had passed. The two runnables sat forty lines apart with opposite
         * answers to the same question.
         *
         * A swallowed error re-arms. An error that repeats then costs one wake every 30s and is
         * reported by [guarded] on the way past; the alternative was mid-use blocking quietly
         * stopping until the next app switch, which is the failure mode this app is least able to
         * see. Only a clean run may decide to stop.
         */
        override fun run() {
            var again = true
            guarded(applicationContext, "recheck") { again = runGuarded() }
            if (again) handler.postDelayed(this, RECHECK_MS)
        }

        /** @return whether a later tick could still change the outcome — i.e. keep the loop alive. */
        private fun runGuarded(): Boolean {
            var pkg = lastForegroundPkg ?: return false
            // Gesture-nav Home can arrive without a window-state event, leaving the cache on
            // the app the user already left — reconcile with the real active window first so
            // a stale package is never (re-)blocked over the home screen. (Our own overlay
            // window may report as the active window; never "reconcile" to ourselves.)
            val actual = rootInActiveWindow?.packageName?.toString()
            // A transient surface (shade, volume dialog, keyboard) may be the active window
            // while the app underneath is unchanged — reconciling to it would make this tick
            // decide the user had left, and remove a legitimate cover. Leave the cache alone
            // and let the next tick see the real app again. See isTransientSurface.
            if (actual != null && actual != packageName && actual != pkg &&
                !isTransientSurface(actual)
            ) {
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
                return false
            }
            if (!overlay.isShowing) {
                // Draw a NEW cover only when the foreground is positively identified as the
                // cached app (post-reconcile that means a readable root that isn't our own
                // UI). With an unreadable root the cache may be stale (missed gesture-nav
                // Home event), and blocking blind used to flash the cover over the home
                // screen — wait for a tick that can actually see what's on screen.
                //
                // A transient surface is not that evidence either, and this is where it leaked
                // back in: the reconcile above deliberately refuses to adopt the shade, the
                // volume dialog or the keyboard, because they sit OVER whatever is open and say
                // nothing about it. This test then read the very same window as proof that the
                // cached app was still in front. After a missed Home event that is exactly
                // wrong — pull down the shade on the home screen, or open a keyboard in another
                // app, and the stale package got covered for as long as it took the next event
                // to tear it down. Milliseconds, over apps that are not blocked.
                if (actual != null && actual != packageName && !isTransientSurface(actual)) {
                    handleAppBlock(pkg)
                }
                // Nothing is covered and this app is one we read: re-arm the page scan. A blocked
                // page that has finished loading emits no events, so if its cover ever came down
                // there would otherwise be nothing left to put it back until the user touched
                // something. Debounced and gated by shouldScanPkg, so a neutral app costs nothing.
                if (!overlay.isShowing && shouldScanPkg(pkg)) scheduleWebScan()
            } else if (overlay.isAppBlock && !shouldBlock(pkg)) {
                // The condition ended while the cover was up → release without an app switch.
                // (Acting only on this transition also avoids re-recording an "attempt" every
                // tick; web/purchase/Shorts covers are owned by their own scans — hands off.)
                lastBlockedPkg = null
                overlay.remove()
            }
            refreshNetFilter()
            return recheckMatters(pkg) || netFilterDown
        }
    }

    /** Whether a later re-check of [pkg] could change the blocking outcome — i.e. the app is
     *  covered by any rule/schedule, a session is running, a keyword lockout is ticking, or
     *  a block cover is up. */
    private fun recheckMatters(pkg: String): Boolean =
        overlay.isShowing ||
            keywordLockoutRemaining(pkg) > 0L ||
            ruleFor(pkg)?.isBlocked == true ||
            // Allowlist mode: any non-allowed app can flip (e.g. a Pomodoro break ending), so
            // keep re-checking while Quick Block is enforcing.
            (allowlistMode && quickBlockActive() && ruleFor(pkg)?.isAllowed != true) ||
            QuickSession.state(this).active ||
            schedules.any { it.enabled && pkg in it.packages }

    /**
     * **Room has finally spoken after a rebind — look at what is already on screen.**
     *
     * The window this closes is real and counted: `SilenceLog.UNREADY_DECISIONS` records decisions
     * taken before the rule flow emitted, and it is entered on every boot, every update, every
     * Second Space switch and every revive. `blockedSnapshot` carries HARD blocks through it, so
     * Quick Block survived; **a schedule or a daily limit did not**, and nothing re-asked afterwards.
     *
     * Two things had to be missing at once for that to be invisible. `handleAppBlock` returns
     * without a cover while `!rulesLoaded` (correctly — it does not know yet), and no re-check tick
     * can have been armed, because [recheckMatters] answers by reading `rules` and `schedules`,
     * which is the state that had not arrived. So the app stayed open until the user left it and
     * came back. Same family as invariants 29 and 30: not a wrong answer, an answer nobody asked
     * for a second time.
     */
    private fun redecideAfterRulesArrived() {
        val actual = rootInActiveWindow?.packageName?.toString()
        val pkg = pkgToRedecide(
            cached = lastForegroundPkg,
            actual = actual,
            own = packageName,
            actualIsTransient = actual != null && isTransientSurface(actual),
        ) ?: return
        if (isLauncherPkg(pkg)) return
        lastForegroundPkg = pkg
        // A cover already up was raised by the snapshot or by a scan; leave it to its owner.
        if (!overlay.isShowing) handleAppBlock(pkg)
        // Arm the mid-use loop, which could not have been armed a moment ago for the same reason.
        if (recheckMatters(pkg)) {
            handler.removeCallbacks(recheckRunnable)
            handler.postDelayed(recheckRunnable, RECHECK_MS)
        }
    }

    /** Millis left on [pkg]'s keyword lockout, or 0 when it isn't locked. Uses the same
     *  clock-change-proof reckoning as sessions, so winding the device clock forward can no
     *  longer lift the lockout early — see [GuardedDeadline]. */
    /** Adult words caught recently, keyed by [DangerZone.key] — a hash, never the word. */
    private val dangerStrikes = mutableMapOf<String, GuardedDeadline>()

    private fun keywordLockoutRemaining(pkg: String): Long = synchronized(keywordLockouts) {
        keywordLockouts[pkg]?.remaining(DeviceBoot.count(applicationContext)) ?: 0L
    }

    /** The word that triggered [pkg]'s lockout, if still remembered (in-memory only). */
    private fun keywordLockoutWord(pkg: String): String? = synchronized(keywordLockouts) {
        keywordLockouts[pkg]?.note
    }

    /** Locks [pkg] for [KEYWORD_LOCKOUT_MS] after a blocked [word] was caught in it. */
    private fun addKeywordLockout(pkg: String, word: String?) {
        val boot = DeviceBoot.count(applicationContext)
        synchronized(keywordLockouts) {
            keywordLockouts.entries.removeAll { it.value.remaining(boot) <= 0L }
            keywordLockouts[pkg] =
                GuardedDeadline.starting(KEYWORD_LOCKOUT_MS, boot, note = word)
            SettingsStore.setKeywordLockouts(applicationContext, keywordLockouts.toMap(), boot)
        }
    }

    /** Millis left on the danger zone, 0 when it is not armed. */
    private fun dangerZoneRemaining(): Long =
        dangerZone?.remaining(DeviceBoot.count(applicationContext)) ?: 0L

    /**
     * Whether the phone's family DNS filter is down far enough to shut the browsers.
     *
     * Cached rather than read per decision: [NetworkFilter.read] is three binder calls into
     * ConnectivityManager and `decideBlock` runs on every foreground change. Refreshed by
     * [refreshNetFilter] on the recheck tick and whenever the watcher reconnects, which is well
     * inside the [NetworkFilter.SETTLE_MS] the decision needs anyway.
     */
    @Volatile private var netFilterDown = false

    /**
     * Watches the network for the filter being switched off.
     *
     * **The recheck tick is not enough on its own, and assuming it was is the bug this exists to
     * fix.** `recheckRunnable` only re-arms while [recheckMatters] holds — a rule, a lockout, a
     * schedule or a cover — so on a plain browser with no rule on it the tick never runs and the
     * cached reading never refreshes. Switching Private DNS off in Settings and opening a browser
     * would have cost nothing at all, which is the one case the whole feature is about.
     *
     * A default-network callback is the right shape anyway: changing Private DNS *is* a link-
     * properties change, so this fires within moments of him touching the setting, costs nothing
     * while nothing changes, and needs no polling of its own.
     */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private fun registerNetworkWatch() {
        if (networkCallback != null) return
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            // All four, because "the filter is protecting me" can stop being true through any of
            // them: the setting changing (link properties), the network going away or arriving,
            // and a captive portal being signed into (capabilities).
            override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) = onNetChanged()
            override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) = onNetChanged()
            override fun onAvailable(n: Network) = onNetChanged()
            override fun onLost(n: Network) = onNetChanged()
        }
        // Guarded: registering can throw on an OEM that has no default network yet, and a watcher
        // that cannot register a listener must still be a watcher.
        if (runCatching { cm.registerDefaultNetworkCallback(cb) }.isSuccess) networkCallback = cb
    }

    /** Callbacks arrive on a binder thread; every decision below belongs on the main thread. */
    private fun onNetChanged() {
        handler.post {
            val was = netFilterDown
            refreshNetFilter()
            // Only chase when it actually flipped. The cover is raised by the recheck pass rather
            // than here, so there is one place that decides what covers what.
            if (netFilterDown != was) {
                handler.removeCallbacks(recheckRunnable)
                handler.post(recheckRunnable)
            }
        }
    }

    /**
     * Re-reads the filter and keeps the "off since" anchor honest.
     *
     * The anchor is **cleared** whenever the filter is protecting *or* unreadable, so the settle
     * time only ever accumulates while it is genuinely off on a working network. Letting an
     * unreadable moment count would mean a flight, a dead spot or an OEM throwing from
     * ConnectivityManager silently paying down the wait — the state that costs him his browsers
     * should only be reached by the filter actually being off.
     */
    private fun refreshNetFilter() {
        val reading = NetworkFilter.read(applicationContext)
        val ctx = applicationContext
        if (NetworkFilter.protecting(reading.state)) {
            // The one place arming happens: the app has now watched it work.
            if (!SettingsStore.netFilterSeen(ctx)) SettingsStore.setNetFilterSeen(ctx)
        }
        if (NetworkFilter.protecting(reading.state) ||
            reading.state == FilterState.CANT_TELL ||
            !reading.validated
        ) {
            SettingsStore.clearNetFilterOffSince(ctx)
            netFilterDown = false
            return
        }
        val boot = DeviceBoot.count(ctx)
        if (SettingsStore.netFilterOffSince(ctx) == null) {
            SettingsStore.startNetFilterOffSince(ctx, boot)
        }
        val left = SettingsStore.netFilterOffSince(ctx)?.remaining(boot) ?: NetworkFilter.SETTLE_MS
        netFilterDown = NetworkFilter.shouldShutBrowsers(
            reading.state,
            networkValidated = reading.validated,
            offForMs = NetworkFilter.SETTLE_MS - left,
            armed = SettingsStore.netFilterSeen(ctx) && SettingsStore.netFilterGuard(ctx),
        )
    }

    /** Last backstop read, monotonic (invariant 9). */
    @Volatile private var netFilterCheckedAt = 0L

    /**
     * The backstop, on the path every foreground change already takes.
     *
     * The callback above is the fast route and this is the one that makes a *missed* callback cost
     * a minute rather than a reboot — OEM connectivity stacks drop them, and this layer's failure
     * is silent by nature: nothing looks wrong, the browsers simply stop being defended.
     * Throttled because [NetworkFilter.read] is three binder calls and app switches are frequent.
     */
    private fun refreshNetFilterIfStale() {
        val now = stopwatchNow()
        if (now - netFilterCheckedAt < NET_FILTER_POLL_MS) return
        netFilterCheckedAt = now
        refreshNetFilter()
    }

    /** Last backstop re-read of the listener-fed settings, monotonic (invariant 9). */
    @Volatile private var cachedSettingsCheckedAt = 0L

    /**
     * **The same backstop, for the four settings a prefs listener keeps fresh.**
     *
     * `keywordsEverywhere`, `adultPackOn`, `updatePaused` and `allowlistMode` are seeded once at
     * connect and thereafter refreshed *only* by `prefsListener`. That is invariant 29's shape
     * exactly — a value whose refresh sits inside a callback — and the DNS filter, which had the
     * same shape, was given both a callback **and** this re-read after the owner found the hole.
     * These four were left with only the callback.
     *
     * Android holds preference-change listeners weakly, so one that is collected, or an OEM prefs
     * implementation that drops a delivery, freezes them silently. A frozen `updatePaused = true`
     * is exactly the symptom he reports: the switch says ON and nothing blocks. A frozen
     * `allowlistMode` is worse in the other direction.
     *
     * Cost is four prefs reads a minute at most, off one already-throttled path. Contrast
     * `OffSwitchGuard.armed`, which re-reads on every call and says in its KDoc that it does.
     */
    private fun refreshCachedSettingsIfStale() {
        val now = stopwatchNow()
        if (now - cachedSettingsCheckedAt < CACHED_SETTINGS_POLL_MS) return
        cachedSettingsCheckedAt = now
        keywordsEverywhere = SettingsStore.keywordsEverywhere(this)
        adultPackOn = SettingsStore.adultWordsPack(this)
        updatePaused = SettingsStore.updatePaused(this)
        allowlistMode = SettingsStore.quickBlockAllowlist(this)
    }

    /** Millis left on the 24-hour wider-list window, 0 when it is not armed. */
    private fun wideListRemaining(): Long =
        dangerWideList?.remaining(DeviceBoot.count(applicationContext)) ?: 0L

    /** Whether `danger_words.txt` should be matched right now — either window does it. */
    private fun wideListOn(): Boolean = dangerZoneRemaining() > 0L || wideListRemaining() > 0L

    /**
     * The adult layer caught [word]. Three DIFFERENT ones inside half an hour close every browser
     * for an hour — see [DangerZone] for the owner's choices and why they are not to be re-asked.
     *
     * Called for adult-pack hits only, never for his own blocked words: this is meant to fire when
     * the *content* says he is hunting, not when a word he added for his own reasons turns up.
     */
    private fun recordDangerStrike(word: String?) {
        if (word.isNullOrBlank()) return
        val boot = DeviceBoot.count(applicationContext)
        synchronized(dangerStrikes) {
            val live = DangerZone.prunedAt(
                dangerStrikes, boot, stopwatchNow(), System.currentTimeMillis(),
            ).toMutableMap()
            // Keyed by hash, so the same word again refreshes its own strike rather than adding
            // a second one. That IS the "three different words" rule.
            live[DangerZone.key(word)] =
                GuardedDeadline.starting(DangerZone.STRIKE_WINDOW_MS, boot)
            dangerStrikes.clear()
            dangerStrikes.putAll(live)
            val trips = DangerZone.tripsAt(
                live, boot, stopwatchNow(), System.currentTimeMillis(),
            )
            // **The flat hour is protected here rather than by refusing to count.** Strikes have
            // to keep accumulating past three or the fifth could never arrive — but re-arming the
            // lockdown while one is running would roll it over forever, which is the escalation
            // he explicitly turned down. So the refusal sits on the arming, not on the counting.
            if (trips && dangerZoneRemaining() <= 0L) {
                dangerZone = GuardedDeadline.starting(DangerZone.LOCKDOWN_MS, boot)
                SettingsStore.setDangerZone(applicationContext, dangerZone)
            }
            // Five different words: the wider list stays for a day. Fixed, not rolling — being
            // caught again inside the day does not extend it.
            //
            // The board is deliberately NOT wiped when either tier arms. It used to be, which
            // made a fifth strike unreachable. Nothing is needed in its place: every strike is a
            // 30-minute GuardedDeadline and the lockdown is 60, so by the time an hour ends the
            // strikes that armed it have already expired on their own.
            if (DangerZone.widensAt(live, boot, stopwatchNow(), System.currentTimeMillis()) &&
                wideListRemaining() <= 0L
            ) {
                dangerWideList = GuardedDeadline.starting(DangerZone.WIDE_LIST_MS, boot)
                SettingsStore.setDangerWideList(applicationContext, dangerWideList)
            }
            SettingsStore.setDangerStrikes(applicationContext, dangerStrikes.toMap(), boot)
        }
    }

    /**
     * An adult hit landed on [host] in browser [pkg]. Two DIFFERENT browsers and the site is
     * blocked outright from then on — his idea and his condition, see [DangerZone.learns].
     */
    private fun recordSiteEvidence(pkg: String, host: String?) {
        val h = host?.lowercase()?.takeIf { it.isNotBlank() } ?: return
        if (pkg !in browserPackages) return
        if (h in learnedDomains) return
        synchronized(learnedLock) {
            val evidence = SettingsStore.siteEvidence(applicationContext).toMutableMap()
            val browsers = evidence[h].orEmpty() + pkg
            if (DangerZone.learns(browsers)) {
                learnedDomains = learnedDomains + h
                SettingsStore.setLearnedDomains(applicationContext, learnedDomains)
                evidence.remove(h) // graduated; the evidence has done its job
            } else {
                evidence[h] = browsers
            }
            SettingsStore.setSiteEvidence(applicationContext, evidence)
        }
    }

    private val learnedLock = Any()

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
        // When the watcher started running, monotonically (invariant 9). Used by the settings
        // guard to tell "the user is switching me ON" from "the user is switching me off" — see
        // OffSwitchGuard.justEnabled. Set first, before anything below can take time.
        serviceConnectedAt = stopwatchNow()
        // The same fact, readable from outside this object: "switched on" and "actually running"
        // are different questions, and until now nothing could ask the second one. See [isConnected].
        connected = true
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_MS)
        // The service is rebound right after an update installs, so detect it here too —
        // the pause arms even if the app itself isn't opened.
        UpdatePause.checkVersionChange(this)
        refreshPackageSets()
        // Before the first decision, not after it: the service is rebound after every update and
        // every space switch, and a stale "the filter is fine" would survive both.
        refreshNetFilter()
        registerNetworkWatch()
        keywordsEverywhere = SettingsStore.keywordsEverywhere(this)
        adultPackOn = SettingsStore.adultWordsPack(this)
        updatePaused = SettingsStore.updatePaused(this)
        allowlistMode = SettingsStore.quickBlockAllowlist(this)
        essentialPackages = findEssentialPackages(this)
        // Before the rule flow below has emitted anything. Cheap by design: one string set.
        blockedSnapshot = SettingsStore.blockedSnapshot(this)
        unreadyCounted = false // one count per bind, and this is a bind
        // A fresh bind is a fresh question. Without this a streak left behind by a process that
        // died deaf would combine with a new `connected = true` and condemn a watcher that has
        // just started working.
        runCatching { ServiceHealth.clearProbeStreak(applicationContext) }
        // Re-arm the alarm that watches WorkManager. An inexact alarm is re-armed only by its
        // own firing, so a single missed one would end the chain for the life of the install --
        // and a rebind is a free chance to repair that, right after the events most likely to
        // have broken it (a boot, an update, a space switch).
        runCatching { ProtectionScheduler.ensureAlarmScheduled(applicationContext) }
        // Restore unexpired keyword lockouts — a service rebind must not unlock an app early.
        // Filtered on the same reckoning that decides whether one is running, not on the wall
        // clock, or a clock change could drop a live lockout here instead of at the check.
        val boot = DeviceBoot.count(this)
        synchronized(keywordLockouts) {
            keywordLockouts.putAll(
                SettingsStore.keywordLockouts(this).filterValues { it.remaining(boot) > 0L }
            )
        }
        // The danger zone survives a restart for the same reason the lockouts do: an hour that a
        // reboot, an update or a Second Space switch could end is not an hour.
        synchronized(dangerStrikes) {
            dangerStrikes.putAll(
                SettingsStore.dangerStrikes(this).filterValues { it.remaining(boot) > 0L }
            )
        }
        dangerZone = SettingsStore.dangerZone(this)?.takeIf { it.remaining(boot) > 0L }
        dangerWideList = SettingsStore.dangerWideList(this)?.takeIf { it.remaining(boot) > 0L }
        learnedDomains = SettingsStore.learnedDomains(this)
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
            val firstEmission = !rulesLoaded
            rules = ruleList.associateBy { it.packageName }
            rulesLoaded = true
            // Keep the pre-connect fallback current. Off the main thread already (this flow runs
            // on Dispatchers.Default), and only written when it actually changed.
            val snapshot = RuleSnapshot.encode(ruleList)
            if (snapshot != blockedSnapshot) {
                blockedSnapshot = snapshot
                runCatching { SettingsStore.setBlockedSnapshot(applicationContext, snapshot) }
            }
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
            // The unready window is now over — go and look at whatever is already on screen.
            // Until this existed, nothing did: see redecideAfterRulesArrived.
            if (firstEmission) handler.post { redecideAfterRulesArrived() }
        }
            // This flow IS the watcher's view of reality — the rules, the Strict session, the
            // blocked words and the schedules. Without a retry, one throw anywhere in it (a bad
            // row, a database hiccup, anything in the transform above) ended the collection for
            // good: the scope's handler logged it, blocking carried on with whatever it last
            // saw, and nothing ever refreshed again. A newly blocked app would never block, an
            // ended Strict session would never lift, and the watchdog would report everything
            // healthy because events were still arriving. Silent, total, and permanent until the
            // service happened to be restarted.
            //
            // So: log it, wait, and re-collect. Room re-reads current state on re-subscribe, so
            // a retry always resyncs rather than resuming from a stale point. The delay grows to
            // a cap so a persistently failing database can't spin.
            .retryWhen { cause, attempt ->
                Log.w(TAG, "rule flow failed (attempt $attempt) — retrying", cause)
                runCatching { ServiceHealth.recordError(applicationContext, "ruleFlow", cause) }
                delay((RULE_FLOW_RETRY_MS * (attempt + 1)).coerceAtMost(RULE_FLOW_RETRY_MAX_MS))
                true
            }
            .launchIn(scope)
        // Catch up on any app installed while we weren't listening. PACKAGE_ADDED is a single
        // delivery with no retry, and Android withholds it from an app in the stopped state — the
        // state a force stop produces, which v1.109 stopped guarding. Service start is the right
        // moment: it happens at boot, after an update, and after the service is revived.
        scope.launch { runCatching { NewAppWatcher.catchUp(applicationContext) } }
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
        // Proof of life for the watchdog: "switched on" is not the same as "still working".
        // Self-throttled to one write a minute (ServiceHealth), so this costs a comparison.
        //
        // ⚠️ **Above the own-package return, and that is the point.** The return below is about
        // *acting* — we never block ourselves — but an event carrying our own package name is
        // proof the framework is still delivering to us, exactly like any other. Stamping
        // liveness after it meant time spent in our own screens recorded nothing, while
        // `UsageTracker` counts this app like every other ("every package **including AppBlocker
        // itself**", its own KDoc). So reading the journal for twenty minutes fed
        // `usedMinutesSinceLastEvent` while `lastEventAt` stood still — the two halves of the
        // staleness arm pulling in opposite directions. Only `STALE_AFTER_MS` being two hours
        // stopped that ever surfacing as a false "blocking has stopped".
        ServiceHealth.recordEvent(applicationContext)
        // The same fact monotonically, for the heartbeat's "have I gone deaf?" question. Not a
        // disk write, and never the wall clock (invariant 9).
        lastEventReceivedAt = stopwatchNow()
        if (pkg == packageName) return // never act on ourselves
        // Where the stopwatch starts for a whole-app block, which is decided and covered inside
        // this same turn of the main thread. The scans cannot use it — they answer long after the
        // event that woke them, by which time later events have moved it on — so they carry their
        // own start instead. See [BlockLatency].
        eventArrivedAt = lastEventReceivedAt

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
                    if (pkg != null) handleSettingsGuard(pkg, event.className?.toString())
                    scheduleUrlScan()
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
        // A transient surface is not a foreground app — the notification shade, volume dialog,
        // heads-up notifications and the keyboard sit ON TOP of whatever is already open. They
        // do genuinely become the active window, so v1.97's confirmation lets them through
        // (correctly — they really are in front), and everything downstream then treated them
        // as "the user went somewhere else":
        //   - handleAppBlock found them unblocked and tore a live cover down, exposing the
        //     blocked app the moment the shade closed. The re-block that followed counted as a
        //     fresh attempt — confirmed on device: opening the shade over a block screen
        //     "counted a new block and added 3 mins".
        //   - they became lastForegroundPkg, so returning to the app counted a phantom open
        //     against its daily open limit, and the mid-use re-check was cancelled.
        // None of that is a foreground change, so stop before any of it. Whatever is underneath
        // has not stopped being blocked, and the surface is drawn over our cover anyway.
        if (pkg != null && isTransientSurface(pkg)) return
        // Keep a live cover rock-solid — ANY cover, which is the part this used to get wrong. A
        // covered app runs behind the (non-focusable) cover and can spit out stray windows — a
        // system/floating popup, a splash, a different-package sub-window — whose window-state
        // event carries a package that isn't blocked, and background apps emit them routinely.
        // Acting on one would set lastForegroundPkg to that stray package and tear the cover down
        // (handleAppBlock → blockReason null → removeBlockOverlay), then the covered app's own
        // window returns and re-blocks: the "disappears ~2s then reblocks" flicker. Ignore any
        // such new package unless it's the confirmed active window (a real switch — our overlay
        // is FLAG_NOT_FOCUSABLE so it never holds focus) or a launcher (Home is always honored,
        // so the user can't be trapped).
        //
        // It used to stop at `overlay.isAppBlock`, so a page or word cover — the only kind with no
        // rule of its own to put it back, since a blocked site arms no lockout — had no protection
        // at all. See CoverGate.strayWindowEvent and invariant 18.
        // (isLauncherPkg can cost a PackageManager query, so the cheap tests stay out here and
        // the rule is only asked on a package change while a cover is up — exactly when the old
        // inline version paid for it.)
        if (overlay.isShowing && pkg != null && pkg != lastForegroundPkg &&
            CoverGate.strayWindowEvent(
                coverShowing = true,
                pkg = pkg,
                currentPkg = lastForegroundPkg,
                isLauncher = isLauncherPkg(pkg),
                activeWindowPkg = rootInActiveWindow?.packageName?.toString(),
            )
        ) {
            // The escape-hatch guard still runs, exactly as on the unconfirmed-package path
            // below: it is about a dangerous page *opening*, it reads the real screen rather than
            // trusting this event's package, it has its own bounce throttle, and being early
            // there is the safe direction.
            handleSettingsGuard(pkg, className)
            // A real switch can simply have lost the race with the window tree, so re-read it
            // shortly rather than waiting for whatever event happens to come next — the same
            // recovery the unconfirmed-package path below uses, class name carried with it.
            //
            // An app-block cover could afford to just return: the app underneath stays blocked
            // whatever this event was. A page cover cannot, which is why this guard no longer
            // stops at `overlay.isAppBlock` — nothing else would put it back if a stray event tore
            // it down, and nothing else takes it down if the user really did leave.
            pendingClassNamePkg = pkg
            pendingClassName = className
            handler.removeCallbacks(confirmForegroundRunnable)
            handler.postDelayed(confirmForegroundRunnable, CONFIRM_MS)
            return
        }
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
            // The escape-hatch guard still runs: it is about a dangerous page *opening*,
            // it has its own bounce throttle, and being early there is the safe direction.
            handleSettingsGuard(pkg, className)
            // A real switch can simply have lost the race with the window tree, so re-read the
            // tree shortly rather than waiting for whatever event happens to come next. Keep the
            // class name with it: the re-read can confirm the package but can never recover the
            // class, and losing it disables the purchase check entirely (see the runnable).
            pendingClassNamePkg = pkg
            pendingClassName = className
            handler.removeCallbacks(confirmForegroundRunnable)
            handler.postDelayed(confirmForegroundRunnable, CONFIRM_MS)
            return
        }
        if (pkg != null && pkg != lastForegroundPkg) {
            lastForegroundPkg = pkg
            LaunchCounter.recordOpen(applicationContext, pkg) // for LAUNCH_COUNT
            // A host read in the app we just left says nothing about where we are now.
            lastBrowserHost = null
        }
        // The dismiss grace is released the moment the user has demonstrably moved on: a
        // different app, OR a different screen inside the same one. Deliberately OUTSIDE the
        // package check above — the case the owner relapsed through never changes package
        // (HOME swallowed on HyperOS, he simply opens the next screen), and that is precisely
        // the case the package-only rule could not see. See CoverGate.graceSpentBy.
        if (pkg != null && className != null) lastWindowClass = className
        if (dismissedKey != null &&
            CoverGate.graceSpentBy(pkg, dismissedPkg, currentDest(), dismissedDest)
        ) {
            if (DEBUG) Log.d(TAG, "grace spent (foreground): $dismissedDest -> ${currentDest()}")
            clearDismissState()
        }
        // Keep the location fix current (and recover after a late permission grant).
        if (schedules.any { it.enabled && it.type == ScheduleType.LOCATION }) {
            ensureLocationUpdates()
        }
        if (pkg != null) {
            // Off-switch escape-hatch guard first (Accessibility/device-admin/app-info
            // pages), then in-app purchase sheet, then normal app blocking.
            if (!handleSettingsGuard(pkg, className) &&
                !handlePurchaseBlock(pkg, className)
            ) handleAppBlock(pkg, eventArrivedAt)
        }
        // (Re)arm the mid-use re-check for the new foreground app; a neutral app
        // (no rules, no session, no cover) costs nothing.
        handler.removeCallbacks(recheckRunnable)
        if (pkg != null && recheckMatters(pkg)) handler.postDelayed(recheckRunnable, RECHECK_MS)
        lastWebText = null // new page/app: force a fresh re-check
        lastCheckedUrl = null
        forgetBrowserUrl()
        // A different app is a different question here too: the remembered activity class is only
        // ever consulted for the package it was seen in, and holding one across apps would let a
        // stale name answer for a screen it was never on.
        if (lastActivityClassPkg != pkg) {
            lastActivityClass = null
            lastActivityClassPkg = null
        }
        // Same reasoning for the back-exit memory: it exists to notice BACK not moving him
        // *within one app*, so leaving that app ends the question.
        if (lastBackExitPkg != null && lastBackExitPkg != pkg) {
            lastBackExitPkg = null
            lastBackExitAt = 0L
        }
        // Record what the watcher makes of this app BEFORE any scan runs, because the most
        // important thing it can report is the case where no scan runs at all: a browser missing
        // from browserPackages is exempt from every web-filtering layer there is, and from the
        // outside that looks exactly like a browser whose address bar cannot be read. The scan
        // overwrites this a moment later with the address, if it gets one.
        pkg?.let {
            WatcherDiagnostics.record(
                this, it, isBrowser = it in browserPackages, host = null,
                siteWords = autoSocialKeywords(), throttle = false,
            )
        }
        scheduleUrlScan()
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
        // While the danger zone is armed, every app is scanned whether or not "check every app"
        // is on the rest of the time. Browsers are already shut outright for that hour, so this
        // is where the widened word list actually does its work — and an hour that only watched
        // the browsers he cannot reach would be watching nothing.
        if (dangerZoneRemaining() <= 0L &&
            ((userKeywords.isEmpty() && !adultPackOn) || !keywordsEverywhere)
        ) return false
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
            urlScanJob?.cancel()
            webScanQueuedAt = 0L
            return
        }
        val now = stopwatchNow()
        if (webScanQueuedAt == 0L) webScanQueuedAt = now
        val delay =
            if (now - webScanQueuedAt >= WEB_SCAN_MAX_WAIT_MS) 0L else WEB_SCAN_DEBOUNCE_MS
        handler.postDelayed(webScanRunnable, delay)
    }

    /**
     * The address bar, checked immediately — no debounce, no waiting for the page to settle.
     *
     * [scheduleWebScan]'s wait plus a 400-node text walk is the right price for finding a word
     * somewhere inside a page, but it is the wrong price for "which site is this": the omnibox
     * says so in one node lookup, before the page has drawn anything. Paying the full price for
     * a blocked site meant every visit showed real content first, and leaving and coming
     * straight back showed it again — a dismissed block that reliably pays out is a block worth
     * retrying forever.
     *
     * Only browsers have an omnibox, so no other app schedules this. One lookup at a time: a
     * scrolling page fires content events continuously, and the in-flight check already covers
     * the burst it belongs to.
     */
    private fun scheduleUrlScan() {
        val pkg = lastForegroundPkg ?: return
        if (pkg !in browserPackages || !shouldScanPkg(pkg)) return
        // One lookup at a time, because a scrolling page fires content events continuously and
        // the in-flight check already covers the burst it belongs to. But an event arriving
        // *during* a read is not part of that burst — it is the address having moved since, and
        // dropping it outright meant a page navigated to mid-read waited for the debounced scan
        // several hundred milliseconds later, or for the user to touch something. Remember it and
        // read once more instead.
        if (urlScanJob?.isActive == true) {
            urlScanDirty = true
            return
        }
        val queuedAt = stopwatchNow()
        urlScanJob = scope.launch {
            urlScanDirty = false
            scanBrowserUrl(pkg, queuedAt)
            // **One repeat at most.** The flag means "it moved while we were reading", and one
            // more read settles that; anything arriving after belongs to the next burst and will
            // schedule its own. Looping until the flag stays clear would let a churning page turn
            // the fast path into a spin, which is the cost this single-flight exists to prevent.
            if (urlScanDirty && lastForegroundPkg == pkg) {
                urlScanDirty = false
                scanBrowserUrl(pkg, stopwatchNow())
            }
        }
    }

    /** Debounced YouTube-Shorts check (quicker than the web scan so Shorts is caught fast).
     *  Only runs while Quick Block is active AND YouTube is foreground (the browser
     *  youtube.com/shorts case is the web scan's job), so other apps' events cost nothing. */
    private fun scheduleShortsScan() {
        if (!SettingsStore.blockYoutubeShorts(this) || !quickBlockActive() ||
            lastForegroundPkg != YOUTUBE_PKG
        ) {
            // Leaving YouTube: a scan already running there must not put its cover up over
            // whatever came next. scheduleWebScan has cancelled its job here all along; this one
            // never did, which is how a Shorts cover could land on the home screen.
            shortsScanJob?.cancel()
            // Only ever takes down the Shorts cover itself — shortsCovering is now derived from
            // what is actually up, so this can no longer remove an app-block cover that replaced
            // it (which happened when YouTube became fully blocked while Shorts was covered).
            if (shortsCovering) overlay.remove()
            return
        }
        handler.removeCallbacks(shortsScanRunnable)
        handler.postDelayed(shortsScanRunnable, 250)
    }

    // --- App blocking (unchanged behaviour) ---

    private fun handleAppBlock(pkg: String, startedAt: Long = 0L) {
        val reason = blockReason(pkg)
        if (reason == null) {
            // "No reason" is only an answer once we have been told the rules. Before Room's first
            // emission it means "we do not know yet", and taking a live cover down on it is how a
            // Second Space switch used to uncover a blocked app (RuleSnapshot, invariant 11). The
            // snapshot above usually answers, but it carries only HARD blocks — a schedule or a
            // limit still reads as null here, so hold what is up and re-decide when the flow
            // lands. Nothing is stranded: the ordinary re-check tick re-runs this.
            if (!rulesLoaded) {
                // The window invariant 11's update is about, now counted rather than merely
                // survived: a non-zero total here says a rebind on this phone really does make
                // decisions before Room has answered.
                if (!unreadyCounted) {
                    unreadyCounted = true
                    SilenceLog.record(applicationContext, SilenceLog.UNREADY_DECISIONS)
                }
                if (overlay.isShowing) return
            }
            lastBlockedPkg = null
            // Not this path's cover to remove. It answers one question — is this whole app
            // blocked? — and a "no" used to tear down whatever was up, including a cover raised by
            // a different question entirely (is this PAGE blocked?). So a blocked page inside an
            // unblocked browser was uncovered again by the browser's very next window event, which
            // is the flicker the owner reported for Shorts in a browser. The rule used to exist
            // here for the Shorts cover alone, by name; CoverGate.ownedByScan is the same rule for
            // every scan's cover, and it still hands the cover back the moment the user is
            // genuinely somewhere else (owner != pkg), so nothing is stranded.
            if (CoverGate.ownedByScan(
                    overlay.isShowing, overlay.isAppBlock, overlay.ownerPkg, pkg,
                )
            ) return
            overlay.remove() // left the blocked app — take the cover down
            return
        }
        val now = stopwatchNow()
        if (pkg == lastBlockedPkg && now - lastBlockAt < 1500) return
        lastBlockedPkg = pkg
        lastBlockAt = now
        showBlockScreen(
            title = reason.title, message = reason.message, packageName = pkg,
            counterKey = pkg, why = reason.why, startedAt = startedAt,
        )
    }

    /**
     * Stop the user from reaching the screens that would let them turn AppBlocker off: the
     * Accessibility settings (disable the service = kill all blocking), the Device-admin page
     * (deactivate = allow uninstall), and AppBlocker's own App-info page (force-stop / uninstall).
     * We bounce them to the home screen the instant such a page opens — before they can reach the
     * toggle. Returns true if it bounced.
     *
     * **This used to run only during Strict Mode**, which left the toggle completely undefended
     * the rest of the time — and killing the service kills every block in the app, because all of
     * them are enforced from here. That was a known, written-down gap
     * (`docs/BLOCKING_INVARIANTS.md`: "Nothing else defends that toggle") until the owner walked
     * through it on a bad day. It now also runs whenever [OffSwitchGuard] is armed.
     *
     * Strict is still checked **first**, and still bounces regardless of whether the always-on
     * guard is switched on or standing in an open unlock window — that is what keeps a Strict
     * session stronger than the everyday guard. What it no longer does is bounce a *wider set of
     * pages*: see below.
     *
     * **Both modes now guard exactly the same three screens.** Strict used to take the broad rule
     * — the *kind* of page was enough, whoever it was about — on the reasoning that a Strict
     * session is opt-in and ends by itself, so over-blocking was affordable there. It isn't: that
     * rule bounced the whole Accessibility section (every other service with it) and *every* app's
     * App-info page, and Strict is the mode the owner runs when he most needs the phone to keep
     * working. He asked for it narrowed, and this is the same target v1.109 settled on for the
     * everyday guard: the screens that actually END protection, and nothing that merely sits near
     * them.
     */
    private fun handleSettingsGuard(pkg: String, className: String?): Boolean {
        val strict = strictRemaining() > 0L
        // Package check before the guard read: this runs on every foreground change, and the
        // overwhelming majority are not Settings at all.
        if (pkg !in GUARD_PACKAGES) return false
        if (!strict && !OffSwitchGuard.armed(applicationContext)) return false

        // The event's own class when it named an activity, the last one seen in this package
        // otherwise — a view class from a content event is not evidence, and reading it as though
        // it were is what bounced the owner's own update (see [activityClassFor]).
        val cn = activityClassFor(pkg, className)?.lowercase().orEmpty()
        // **The gate and the evidence have to describe the same window.**
        //
        // The check above is the *event's* package; everything below is read from
        // `rootInActiveWindow`, which is whatever is really on screen. Nothing tied the two
        // together, and the comments at the stray-event call sites said so approvingly — "it reads
        // the real screen rather than trusting this event's package". It does both: the real screen
        // for the evidence, the event for the gate, and they are not always the same window.
        //
        // So a background Settings window announcing itself (it sits in Recents, and MIUI is
        // generous with these) opened the gate, while the text came from the app actually in front.
        // The accessibility-page test is `text.contains("appblocker uses this to detect")`, chosen
        // because Android renders our own service description "there and nowhere else" — true of
        // Settings, and not true of a chat window in which that sentence is being discussed. The
        // owner was reading this app's own strings.xml in a Claude conversation when his phone
        // covered it and threw him home (report #7, 21 Aug 2026; report #5 in Claude Code is very
        // likely the same thing, from before the log could name the rule).
        //
        // Invariant 1, which every other cover path already honours and this one never did: a
        // window tree that positively names a DIFFERENT package is a contradiction, so decline.
        // Unreadable is not a contradiction and changes nothing — `guardScreenText` already
        // answers "" there, and the content-event re-check follows with a populated tree.
        val activePkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        if (activePkg != null && activePkg != pkg) return false
        // Read the window ONCE and pass it down. Each of the three checks below used to walk the
        // node tree for itself, so a single decision could walk it three times — on a screen the
        // budget deliberately lets run to 800 nodes, on every foreground change inside Settings.
        val text = guardScreenText()
        // Three screens in both modes: the ones that actually END protection. Everything else the
        // old broad rule covered was collateral.
        //
        // The lesson of six attempts is that the App-info page is the wrong target *in general*.
        // It is a HUB — battery, permissions, storage, notifications and uninstall on one page —
        // so guarding it for everyone costs the owner all the rest, and it is self-defeating:
        // this app *asks* him to set battery to "no restrictions" so blocking survives, and then
        // blocked the page where that is done.
        //
        // Force-stop was given up with it, on the reasoning that "the service restarts by itself".
        // MEASURED, and that reasoning is false: force-stopping us does not merely kill the
        // process, Android REMOVES us from enabled_accessibility_services. Checked at +5s, +15s,
        // +30s and +60s — process dead, the setting reads null, no events, no recovery. Force
        // stop is a permanent off-switch, not a pause.
        //
        // It is also a SILENT one. A force stop puts us in Android's stopped state (see
        // onServiceConnected), where the WorkManager watchdog and BootReceiver cannot run either,
        // so the "protection turned off" alert cannot fire. The owner hit exactly this during a
        // Strict session: "no protection anymore".
        //
        // So the fourth screen is added back at the narrowest scope that fixes it, which is the
        // scope the owner chose: OUR page, and only while a Strict session is running. Every
        // other app's App-info page stays open, and outside a session nothing changes at all —
        // so the battery setting he is told to change is reachable whenever no session is on,
        // which is what made the broad rule intolerable.
        // The accessibility page is the one screen a user is NECESSARILY on at the moment they
        // switch the service on, and the guard used to bounce them for it — the service starts,
        // sees its own off-switch page, and fires HOME at somebody who just turned protection on.
        // Reported from the owner's tablet, and it broke the first run for every new user, since
        // the disclosure screen sends them to exactly this page. See OffSwitchGuard.justEnabled.
        //
        // The grace is scoped to this arm alone. Uninstall, device-admin removal and the App-info
        // page during Strict are nothing to do with enabling the service, so they keep bouncing
        // from the first millisecond — a blanket "guard off after connect" would weaken three
        // screens to fix one.
        val justEnabled = OffSwitchGuard.justEnabled(stopwatchNow() - serviceConnectedAt)
        // Which of the four, not merely whether. Same order and same short-circuiting as the `||`
        // chain this replaces — the only thing added is that the answer survives into the log.
        //
        // `why=guard` was one code for four screens that fail differently and get fixed
        // differently, so report #7 ("i dont know why it blocked") could not say which one had
        // fired: the accessibility page is the v1.127 shape, an uninstall confirmation is report
        // #6's, device-admin is v1.107's, App-info-during-Strict is force-stop protection. That is
        // exactly the ambiguity `BlockWhy` was created to remove for report #5, left one level too
        // shallow. Invariant 17, and the same split v1.132/v1.133 made for word/site/adult.
        val why = when {
            ourOwnServicePage(text) && !justEnabled -> BlockWhy.GUARD_SERVICE
            uninstallConfirmation(pkg, cn, text) -> BlockWhy.GUARD_UNINSTALL
            deviceAdminRemoval(cn, text) -> BlockWhy.GUARD_ADMIN
            strict && ourOwnAppInfoPage(cn, text) -> BlockWhy.GUARD_APPINFO
            else -> return false
        }

        // Monotonic, not the wall clock: this throttle claims "already bouncing" WITHOUT
        // bouncing, so a backward clock change used to turn it into a permanent "yes" — and
        // with the guard silent, the Accessibility toggle it exists to defend was reachable.
        val now = stopwatchNow()
        if (now - lastGuardBounceAt < 1500) return true // already bouncing this page
        lastGuardBounceAt = now
        if (DEBUG) Log.d(TAG, "settings guard bounce: pkg=$pkg class=$className strict=$strict")

        // Name the actual reason. A cover saying "Strict Mode" on a day with no session running
        // reads as a bug, and sends the owner looking for a session to end that doesn't exist.
        showBlockScreen(
            title = words.get(
                if (strict) R.string.block_offswitch_strict_title
                else R.string.block_offswitch_title,
            ),
            message = if (strict) {
                words.get(R.string.block_offswitch_strict_message)
            } else {
                words.get(R.string.block_offswitch_message, OffSwitchGuard.DELAY_LABEL)
            },
            packageName = null,
            counterKey = "strict_guard",
            why = why,
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

    /**
     * Lowercased visible text of the current page (rootInActiveWindow only). Kept separate from
     * extractVisibleText(), which strips com.android.settings (it's in KEYWORD_SCAN_EXCLUDED).
     *
     * The budget is deliberately far larger than the keyword scanner's. This walks *settings
     * lists*, where the one row that decides the answer — ours — can sit well down a long list of
     * apps or services, and running out of nodes before reaching it reads as "not our page" and
     * lets the off-switch through. It only ever runs on [GUARD_PACKAGES], so the cost lands on
     * Settings screens and nowhere else.
     */
    private fun guardScreenText(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 800 && sb.length < 8000) {
            val node = queue.removeFirst()
            visited++
            node.text?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
            node.contentDescription?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        // Folded through the same Arabic normalizer the word filter uses, so the Arabic markers
        // can be stored in one spelling and still match the alef/ta-marbuta variants and the
        // diacritics a Settings app actually renders.
        return WebContentFilter.normalizeArabic(sb.toString().lowercase())
    }

    /**
     * Whether the screen is **AppBlocker's own accessibility page**, decided by our own text.
     *
     * Android renders a service's `android:description` on that service's detail page. Ours is
     * [com.appblocker.R.string.accessibility_description], and it appears there and nowhere else:
     * not on the services list, not on another service's page.
     *
     * **This is the point of the fourth attempt at this check.** The first three matched
     * system-supplied text — a class name, then our app label, then other services' labels — and
     * system text differs by OEM, by Android version and by language. Matching a string this app
     * itself ships removes all three variables at once. It is also the first version that is
     * locale-proof: the app has no translations, so Android shows this description in English on
     * an Arabic phone too, while every system label around it changes.
     *
     * The fragment is taken from the START of the description so an OEM that truncates it behind
     * a "More" link still matches. Unreadable screen answers false: the page has only just
     * opened, nothing can have been tapped yet, and the content-event re-check follows in
     * milliseconds with a populated tree (see the v1.104 note in aboutUs).
     */
    private fun ourOwnServicePage(text: String): Boolean =
        text.contains(OUR_SERVICE_DESCRIPTION_FRAGMENT)

    /**
     * Whether the dangerous page in front of us is about **AppBlocker**, rather than some other
     * app or service that merely lives on the same kind of screen.
     *
     * This is what lets the guard leave the rest of the phone alone: without it, every app's
     * App-info page and the whole Accessibility section get bounced, so force-stopping a frozen
     * app or clearing a cache costs the full unlock wait. That is what Strict Mode used to do to
     * the owner, and why both modes now go through here.
     *
     * **An unreadable screen answers `null` — "can't tell" — and null must not bounce.**
     *
     * This started out answering `true` there, reasoning that a wrong bounce is visible and
     * waitable while a wrong pass is invisible. That reasoning was wrong about *when* this runs.
     * The call happens on the window-state event, which is the exact moment the window is being
     * built and `rootInActiveWindow` is most often null or empty — so "unreadable" is not a rare
     * failure here, it is the common case at that instant. Answering `true` meant opening *any*
     * app's App-info page could raise a cover and bounce, intermittently, depending on a race.
     * That is over-blocking, and it flashes.
     *
     * Nothing is lost by waiting: the page has only just opened, no toggle can have been reached
     * yet, and Settings emits content events within milliseconds. The re-check on those events
     * (see handleEvent) runs with a populated tree and catches a genuine off-switch page. Null
     * therefore changes nothing, exactly as `isShortsOnScreen()` does when it cannot read the tree
     * (docs/BLOCKING_INVARIANTS.md, sweep 5) — the same primitive, and this is its third appearance.
     *
     * Known limit, deliberately accepted: in a long scrollable list Android only builds nodes for
     * rendered rows, so our row genuinely is not in the tree until it is scrolled into view. The
     * content-event re-check is what catches that too.
     */
    private fun aboutUs(text: String): Boolean? {
        if (text.isBlank()) return null
        return text.contains("appblocker")
    }

    /**
     * **Our own** App-info page — the one carrying Force stop. Strict-only; see the call site.
     *
     * Two conditions, and the order matters. [aboutUs] must answer a definite `true`: a page that
     * cannot be read yet answers null, and null must not bounce (v1.104 — answering "yes" on an
     * unbuilt window is what threw covers over other apps' App-info pages). [AppInfoScreen] then
     * says whether this is the App-info page at all, which is what keeps the Settings app list —
     * which names every app, including us — from matching.
     */
    private fun ourOwnAppInfoPage(cn: String, text: String): Boolean =
        aboutUs(text) == true &&
            AppInfoScreen.looksLikeAppInfo(cn, text, BuildConfig.VERSION_NAME)

    /**
     * The system's "do you want to uninstall this app?" dialog, for us.
     *
     * Matched by *package* rather than by wording: the installer packages exist for one purpose,
     * so being in one of them and naming us is enough, and nothing here depends on a string that
     * an OEM translates or rephrases.
     */
    private fun uninstallConfirmation(pkg: String, cn: String, text: String): Boolean {
        if (pkg !in INSTALLER_PACKAGES) return false
        // We opened this ourselves to install an update. The installer screen for an update is the
        // same package showing the same app name as the one for a removal, so without this the
        // guard bounced its own updates — and a guard that blocks its own updates blocks the fix
        // for itself. Knowing we opened it beats reading it, in any language. See InstallPrompt.
        if (InstallPrompt.recentlyRequested(applicationContext)) return false
        // Second, independent signal for an install we did NOT open — sideloading the APK from a
        // browser or a file manager. Activity CLASS names are not translated, so unlike the screen
        // text they mean the same thing on every phone and in every language. Only a name that
        // says install *without* saying uninstall counts; "uninstall" contains "install", which is
        // exactly the trap the device-admin check fell into with "deactivate"/"activate".
        if (cn.contains("install") && !cn.contains("uninstall") && !cn.contains("delete")) {
            return false
        }
        return aboutUs(text) == true
    }

    /**
     * The device-admin screen, when it is about *removing* our admin rather than adding it.
     *
     * [AdminPrompt] is what separates the two, and it is knowledge rather than a guess: the
     * activation prompt is one WE opened, moments earlier. Reading the screen cannot do this —
     * "deactivate" contains "activate", and both are translated. Guarding the activation prompt
     * meant uninstall protection could never be switched on at all (v1.107).
     *
     * The class name is a fast path, not the whole test. On MIUI this screen is routinely a
     * generic `SubSettings` whose name says nothing, and the only thing catching it there was the
     * broad Strict rule that this version deletes — so removing that rule without widening this
     * one would have handed back the v1.107 hole. [ADMIN_TEXT_MARKERS] is the fallback, and it is
     * deliberately far narrower than the list it replaces: device-admin wording only, so it cannot
     * match the Accessibility list or an App-info page the way `uninstall` and `accessibilit` did.
     */
    private fun deviceAdminRemoval(cn: String, text: String): Boolean {
        if (AdminPrompt.recentlyRequested()) return false
        // Unreadable screen answers no, exactly as aboutUs() does: the page has only just opened,
        // no toggle can have been reached yet, and the content-event re-check follows with a
        // populated tree (v1.104 — answering "yes" here is what made covers flash).
        if (aboutUs(text) != true) return false
        return cn.contains("deviceadmin") || cn.contains("device_admin") ||
            AdminScreens.looksLikeAdminScreen(text)
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
            title = words.get(R.string.block_purchase_title),
            message = words.get(R.string.block_purchase_message),
            packageName = null,
            counterKey = "purchase",
            why = BlockWhy.PURCHASE,
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
        // Throttled, so this is a comparison on all but one call a minute. Here rather than only
        // on the recheck tick because that tick does not run for an app with no rule on it - the
        // exact case this layer exists for.
        refreshNetFilterIfStale()
        refreshCachedSettingsIfStale()
        val session = QuickSession.state(this)
        val strict = strictRemaining() > 0L
        val now = System.currentTimeMillis()
        if (DEBUG) Log.d(TAG, "blockReason $pkg schedules=${schedules.size} opens=${LaunchCounter.opensToday(this, pkg)}")
        return decideBlock(
            BlockInputs(
                pkg = pkg,
                updatePaused = updatePauseActive(),
                lockoutRemainingMs = keywordLockoutRemaining(pkg),
                dangerZoneRemainingMs = dangerZoneRemaining(),
                netFilterDown = netFilterDown,
                isRealBrowser = isRealBrowserPkg(pkg),
                lockoutWord = keywordLockoutWord(pkg),
                strict = strict,
                // Enforcing when Strict, or a running Timer/Pomodoro says "block now", or
                // (no session) when not paused. Same gate in Blocklist and Allowlist mode.
                quickEnforcing = strict ||
                    if (session.active) session.blockingNow else !SettingsStore.quickBlockPaused(this),
                rule = ruleFor(pkg),
                allowlistMode = allowlistMode,
                isEssential = { isEssentialAllowed(pkg) },
                schedules = schedules,
                // "Unsupported" now means "we have never managed to read this one", not "it isn't
                // Chrome". KNOWN_READABLE_BROWSERS seeds the answer with the toolbars we know how
                // to read — needed because a blanket-blocked browser is never scanned, so without
                // a seed a filterable browser could never demonstrate that it is one — and every
                // other browser earns it by being read once. Unknown still counts as unsupported.
                isUnsupportedBrowser = isRealBrowserPkg(pkg) &&
                    pkg !in KNOWN_READABLE_BROWSERS &&
                    pkg !in SettingsStore.readableBrowsers(this),
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
                words = words,
            )
        )
    }

    /** "your “Work” schedule", or just "your schedule" when it has no name. */
    private fun schedLabel(s: Schedule): String =
        if (s.name.isBlank()) "your schedule" else "your “${s.name.trim()}” schedule"

    /** Minutes-from-midnight → "9:00" / "17:30". */
    private fun hm(m: Int): String = "%d:%02d".format(m / 60, m % 60)

    /**
     * Re-detects the browser and launcher sets, treating an **empty** answer as a failure rather
     * than as data. Both finders swallow errors as `emptySet` (see PackageSets), and neither "this
     * phone has no browser" nor "no home screen" is ever true — so an empty result means the query
     * failed, and adopting it silently switches protections off:
     *
     *  - an empty browser set makes every browser look like an ordinary app: no adult site list,
     *    no blocked-app websites, no unsupported-browser block, and during the after-update pause
     *    no scanning at all — which is exactly when the adult layer is the only one still meant to
     *    be running. Nothing else re-detects browsers, so it would stay wrong until the next app
     *    install or removal.
     *  - an empty launcher set makes Allowlist mode block the home screen.
     *
     * [isLauncherPkg] already applies this rule to its own refresh (v1.96); this carries it to the
     * two places that assign the sets directly, which is where it was missed.
     */
    private fun refreshPackageSets() {
        findBrowserPackages(applicationContext).takeIf { it.isNotEmpty() }
            ?.let { browserPackages = it }
        // The blanket-block set is allowed to be empty — "no app on this phone is really a
        // browser" is a possible truth, and adopting it costs only the blunt block, never
        // filtering. The loose set above keeps the non-empty rule because an empty one there
        // would silently disable every web layer.
        //
        // ⚠️ That reasoning is now out of date about the cost, which is why [isRealBrowserPkg]
        // exists: since v1.139 and v1.142 this set also gates the **danger-zone hour** and the
        // **DNS-filter browser shutdown** (BlockDecision's first two layers, both `isRealBrowser`).
        // An empty answer no longer costs "only the blunt block" — it silently switches off two
        // whole protections. The set may still legitimately be empty; it may no longer be *assumed*
        // empty for the rest of the service's life on the strength of one reading.
        realBrowserPackages = findRealBrowserPackages(applicationContext)
        lastRealBrowserRefreshAt = SystemClock.elapsedRealtime()
        findLauncherPackages(applicationContext).takeIf { it.isNotEmpty() }?.let {
            launcherPackages = it
            knownNonLauncherPkgs = emptySet() // a fresh answer retires every earlier guess
        }
    }

    /**
     * "Is this really a browser?", with the empty set treated as **unanswered rather than no**.
     *
     * `findRealBrowserPackages` swallows every PackageManager failure into nothing, and it runs at
     * connect — one hiccup there, and for the whole lifetime of the service the danger zone never
     * arms and the DNS guard never shuts a browser, with nothing on screen saying so. Same shape as
     * [isLauncherPkg], which learned this in v1.96, and the same fix: re-detect, throttled.
     *
     * Only the empty set triggers a re-read. A populated set that is missing one package is a
     * different question (a newly installed browser), and `PackageInstallReceiver` already answers
     * it.
     */
    private fun isRealBrowserPkg(pkg: String): Boolean {
        if (pkg in realBrowserPackages) return true
        if (realBrowserPackages.isNotEmpty()) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastRealBrowserRefreshAt < REAL_BROWSER_REFRESH_MS) return false
        lastRealBrowserRefreshAt = now
        val found = findRealBrowserPackages(applicationContext)
        if (found.isNotEmpty()) realBrowserPackages = found
        return pkg in found
    }

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

    /**
     * True when [pkg] is a surface that appears *over* the current app rather than replacing
     * it — the notification shade, volume dialog and heads-up notifications (System UI), system
     * dialogs ("android"), and the keyboard. Seeing one of these is not the user leaving.
     *
     * Deliberately much narrower than [isEssentialAllowed]: Settings and the dialer are also
     * essentials, but navigating to them IS leaving the app, so they must still take a cover
     * down. The keyboard is read live, like everywhere else, since it can be swapped.
     */
    private fun isTransientSurface(pkg: String): Boolean =
        pkg in TRANSIENT_SURFACES || pkg == imePackage()

    /**
     * The current keyboard, cached on a throttle. [isTransientSurface] is asked on every
     * window-state event and [currentImePackage] reads Settings.Secure through a content
     * provider, which is too much for that path.
     *
     * [isEssentialAllowed] deliberately keeps reading it live: there, staleness could block a
     * just-swapped keyboard and stop the user typing, whereas here the worst case is a cover
     * briefly coming down for a keyboard we haven't noticed yet.
     */
    private fun imePackage(): String? {
        val now = SystemClock.elapsedRealtime()
        if (now - lastImeRefreshAt >= IME_REFRESH_MS) {
            lastImeRefreshAt = now
            cachedImePkg = currentImePackage(this)
        }
        return cachedImePkg
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
                    knownNonLauncherPkgs = emptySet() // a new install may be a launcher
                    refreshPackageSets()
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
        // This used to bail out entirely while a Shorts cover was up, to leave the Shorts scan's
        // state alone. The effect was that locking the phone mid-Shorts skipped ALL of the
        // cleanup below: the cover stayed attached, the timers stayed armed and the foreground
        // cache kept pointing at YouTube, so the next unlock could show a stranded cover over
        // whatever was on screen. Screen-off now always cleans up; the Shorts scan re-covers on
        // its next tick if the user really is still on a Short.
        handler.removeCallbacks(webScanRunnable)
        handler.removeCallbacks(shortsScanRunnable)
        handler.removeCallbacks(recheckRunnable)
        handler.removeCallbacks(confirmForegroundRunnable)
        webScanJob?.cancel()
        urlScanJob?.cancel()
        // The Shorts scan was the one thing screen-off never stopped — so it could finish after
        // all the cleanup below and raise a cover with nothing left to take it down.
        shortsScanJob?.cancel()
        // Same rule, one job later: a Shorts exit left running here would keep pressing BACK into
        // a phone that has just been locked. Every scan job is cancelled in both this method and
        // onDestroy, and CodeShapeTest fails the build if a new one is not.
        shortsExitJob?.cancel()
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
        lastCheckedUrl = null
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
        // Android hands back "<unknown ssid>" rather than the real name unless we hold location
        // access (and location services are on). Comparing that against the target just quietly
        // returns false, so a named-network schedule looks fine and never blocks — the schedule
        // editor now checks the permission up front and says so, because there is nothing useful
        // to do about it here: blocking on an unreadable name would block on EVERY Wi-Fi.
        if (ssid.equals(UNKNOWN_SSID, ignoreCase = true) || ssid.isBlank()) return false
        return ssid.equals(target, ignoreCase = true)
    }

    /**
     * The last fix, or null when there is none **or it is too old to mean anything** — see
     * [locationFixUsable], which is where the rule and the reasoning live (and is unit-tested).
     *
     * A fix with no expiry is not "the phone's location", it is "a place the phone once was".
     */
    private fun freshLocation(): Location? = lastLocation?.takeIf {
        locationFixUsable(it.elapsedRealtimeNanos, SystemClock.elapsedRealtimeNanos())
    }

    /** True if the last *usable* fix is within the schedule's radius. An unusable one answers
     *  false — the same as no fix — because a location we can't vouch for must not decide either
     *  way, and blocking on an unknown position would block the app everywhere. */
    private fun inLocation(s: Schedule): Boolean {
        val loc = freshLocation() ?: return false
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
            // NETWORK has no displacement filter on purpose. With one, a STATIONARY phone gets no
            // updates at all — the filter needs 25m of movement — so the fix only stayed current
            // thanks to refreshCurrentLocation, which is API 30+ only. Below that (minSdk is 24)
            // the fix could age indefinitely while sitting still, which is exactly the state the
            // freshness ceiling now rejects: without this the ceiling would turn a stale-but-right
            // fix into no blocking at all on older phones. Network fixes are cell/Wi-Fi derived,
            // so time-based updates here are cheap.
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30_000L, 0f, listener)
            // GPS keeps the filter: that radio is the expensive one, and NETWORK above already
            // guarantees a heartbeat.
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
        // Drop the position too. We have stopped tracking, so holding one means a schedule
        // re-enabled later would decide from where the phone was before it was switched off.
        lastLocation = null
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
    private fun quickBlockActive(): Boolean = QuickSession.enforcing(
        strictRemaining(), updatePaused, QuickSession.state(this),
        SettingsStore.quickBlockPaused(this),
    )

    /** Website keywords for the social apps the user has blocked, so a blocked app's site is
     *  blocked too. Empty while Quick Block is paused so pausing relieves the web block as well.
     *
     *  Auto-blocking a blocked app's website is a Blocklist concept; in Allowlist mode the allowed
     *  browser is allowed and the user's own keywords still apply. The derivation itself lives in
     *  [StrictEdits.liveSiteWords] because the Blocked-words screen needs the same answer — a word
     *  this list already covers is one Strict Mode can afford to let go. */
    private fun autoSocialKeywords(): List<String> =
        StrictEdits.liveSiteWords(rules.values, allowlistMode, quickBlockActive()).map { it.word }

    /**
     * Covers a blocked site the moment the address bar says we're on one — the fast half of
     * [scanWebContent], carrying the same guards in the same order.
     *
     * Deliberately does NOT replace the full scan: it only ever sees the address bar, which is
     * hidden in fullscreen video and absent in browsers that don't expose it, so page text,
     * search boxes and adult pages that don't name themselves in their URL all remain
     * [scanWebContent]'s job. This only removes the wait in the case where the answer was
     * already on screen — and because the full scan is untouched, this one is free to decline
     * whenever it is unsure: the worst case is the old speed, never a miss.
     */
    private suspend fun scanBrowserUrl(pkg: String, startedAt: Long = 0L) {
        if (lastForegroundPkg != pkg || !shouldScanPkg(pkg)) return
        // A cover is already up, so everything under it is unreachable and there is nothing
        // new to block.
        if (overlay.isShowing && !shortsCovering) return
        // Settled addresses only: acting on half-typed omnibox text would cover the screen
        // mid-word, and the owner asked for the block to wait until he has actually gone to the
        // site. Typing a blocked word is still caught by the page scan, at its usual speed.
        //
        // **Read BEFORE the dismiss grace is consulted**, which is the fix for the browser half
        // of the eight-second hole. On a page cover "Got it" steps BACK and deliberately leaves
        // the user in the browser (v1.135), so the package never changes and the only evidence
        // that they have moved on is a different host — evidence this function used to return
        // before ever looking for. Every website block therefore bought a browser nobody was
        // watching until the grace lapsed. One omnibox lookup during the grace is the price of
        // not being deaf; the full page scan still waits, as it always did.
        val url = extractSettledBrowserUrl(pkg)
        val host = WatcherDiagnostics.hostOf(url)
        if (host != null) lastBrowserHost = host
        if (dismissedKey != null &&
            CoverGate.graceSpentBy(pkg, dismissedPkg, host, dismissedDest)
        ) {
            if (DEBUG) Log.d(TAG, "grace spent (new host): $dismissedDest -> $host")
            clearDismissState()
        }
        // Mid-dismissal: the page is still on screen for the step back or the trip Home.
        if (dismissedKey != null && withinDismissWindow()) {
            if (DEBUG) Log.d(TAG, "urlScan[$pkg]: suppressed by dismiss grace")
            return
        }
        // No settled address: a start page, an empty bar, a toolbar behind a fullscreen video.
        // Forget what was last checked here — the dedup below is keyed on the address alone, so
        // leaving it set means coming back to the same blocked site without leaving the browser
        // reads as "already handled" and this path skips it. The debounced scan still catches
        // that, a beat later; an under-block is the half he never sees.
        if (url == null) {
            lastCheckedUrl = null
            return
        }
        if (url == lastCheckedUrl) return
        lastCheckedUrl = url
        if (DEBUG) Log.d(TAG, "urlScan[$pkg]: $url")
        // The user's own words pause after an update exactly as they do in the full scan; the
        // adult layer deliberately does not, since an update must not become the easy way out.
        val ownWords = if (updatePauseActive()) emptyList() else userKeywords
        val hit = filter.checkUrl(url, ownWords, autoSocialKeywords())
            ?: filter.checkUrlAdult(
                url, adultPackOn, SettingsStore.blockAdult(applicationContext), learnedDomains,
            )
            ?: return
        if (DEBUG) Log.d(TAG, "URL BLOCK[$url]: ${hit.title}")
        withContext(Dispatchers.Main) {
            if (lastForegroundPkg == pkg && stillOnScreen(pkg)) {
                // Identical to the full scan's cover path on purpose — a blocked WORD still
                // locks the app so "Got it" isn't a free pass back in, a blocked WEBSITE still
                // just covers the page. Arriving sooner must not change what happens.
                if (!hit.site) addKeywordLockout(pkg, hit.word)
                if (hit.adult) {
                    recordDangerStrike(hit.word)
                    recordSiteEvidence(pkg, lastBrowserHost)
                }
                showBlockScreen(
                    title = hit.title, message = hit.message, packageName = null,
                    counterKey = CoverGate.WEB_KEY, offenceKey = pkg, why = BlockWhy.ofWebHit(hit.site, hit.adult),
                    startedAt = startedAt,
                )
            } else lastCheckedUrl = null // left during the lookup — re-decide on the way back
        }
    }

    /**
     * The address bar for [pkg], falling back to the last one read there while it is **hidden** —
     * and never while it is merely empty.
     *
     * See [rememberedUrl] for why the memory exists. The order matters and is the whole safety
     * argument: a live read always wins and always replaces the memory, so the remembered value is
     * only ever consulted in the one case it is for — the toolbar is not on screen and there is
     * nothing to read.
     */
    private fun rememberedBrowserAddress(pkg: String): BrowserAddress {
        val read = extractBrowserAddress(pkg)
        val now = stopwatchNow()
        if (read is BrowserAddress.At) {
            rememberedUrl = read.url
            rememberedUrlPkg = pkg
            rememberedUrlAt = now
            // This browser has now demonstrably been read, so it stops counting as one we
            // "can't filter" — see SettingsStore.readableBrowsers. Cheap: a set lookup unless
            // it is the first time.
            SettingsStore.addReadableBrowser(applicationContext, pkg)
            return read
        }
        // **An empty address bar is an answer, and the memory must give way to it.** Invariant 4
        // cuts both ways: a *hidden* toolbar is a failed measurement, so answering from memory is
        // right; a *visible empty* one is a successful measurement of "he is not on a page", and
        // answering from memory there is how a new tab inherited the site he had just left for
        // the next ten minutes. The bar was still found by its id, so this browser is readable —
        // that much is recorded exactly as a live address would be.
        if (read is BrowserAddress.Blank) {
            forgetBrowserUrl()
            SettingsStore.addReadableBrowser(applicationContext, pkg)
            return read
        }
        if (rememberedUrlPkg != pkg) return BrowserAddress.Unreadable
        if (now - rememberedUrlAt > URL_MEMORY_MS) {
            rememberedUrl = null
            return BrowserAddress.Unreadable
        }
        return rememberedUrl?.let { BrowserAddress.At(it) } ?: BrowserAddress.Unreadable
    }

    /** Forgets the remembered address. Called on every foreground change: a different app is a
     *  different question, and this must never answer for a page the user has left. */
    private fun forgetBrowserUrl() {
        rememberedUrl = null
        rememberedUrlPkg = null
        rememberedUrlAt = 0L
    }

    /** Runs on a background dispatcher (the node-tree walk is heavy); only the block UI hops to main. */
    private suspend fun scanWebContent(startedAt: Long = 0L) {
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
        if (dismissedKey != null && withinDismissWindow()) {
            if (DEBUG) Log.d(TAG, "scan[$pkg]: suppressed by dismiss grace")
            return
        }
        // Under a keyword lockout the app is blocked outright — cover it on any content
        // event, no text matching. This path fires from cached state, so it demands positive
        // confirmation that the locked app is REALLY the visible one (an unreadable root is
        // NOT enough — after a missed gesture-nav Home event the cache goes stale and this
        // used to flash the cover over the home screen).
        if (keywordLockoutRemaining(pkg) > 0L) {
            withContext(Dispatchers.Main) {
                if (lastForegroundPkg == pkg &&
                    rootInActiveWindow?.packageName?.toString() == pkg
                ) handleAppBlock(pkg, startedAt)
            }
            return
        }
        val isBrowser = pkg in browserPackages
        val text = extractVisibleText(::isLauncherPkg)
        if (DEBUG) Log.d(TAG, "scan[$pkg browser=$isBrowser]: ${text.length} chars: ${text.take(120)}")
        if (text.isBlank()) return
        // Same text as the last thing we blocked on — skip only while that block is still ON
        // SCREEN. lastWebText is only ever set for text that HIT (a miss nulls it below), so
        // without the second half this dedup outlives the cover: any path that takes the cover
        // down early leaves the page reading as "already handled" and it is never covered again.
        // That is what turned a one-frame flicker into a page sitting there unblocked, and it is
        // the invisible half of the bug — the owner sees the flash, never the silence after it.
        // The cost of dropping it is one bounded walk over a page already known to be blocked;
        // showBlockScreen dedups the cover and CoverGate dedups the count.
        if (text == lastWebText && overlay.isShowing) return
        // The site the user is actually ON (browsers only) — keyword matching prefers it
        // over the page text so a page merely mentioning a blocked word doesn't block. A
        // non-browser has no address bar at all, which is Unreadable: exactly the null it used
        // to pass, so its own words and the pack still match its text.
        val address = if (isBrowser) rememberedBrowserAddress(pkg) else BrowserAddress.Unreadable
        // "Browser, but no address" is the shape this record exists to make visible — it is the
        // whole difference between a Chrome that blocks a site and a Brave that says nothing.
        // Only the host is kept; see WatcherDiagnostics.
        WatcherDiagnostics.record(
            applicationContext, pkg, isBrowser, WatcherDiagnostics.hostOf(address.urlOrNull),
            autoSocialKeywords(),
        )

        // YouTube Shorts opened in a browser (youtube.com/shorts) — while Quick Block is active.
        // Same rule as the filter's, applied to the one address test that lives out here: an
        // address answers for itself, an unreadable one falls back to the page (no bypass), and a
        // start page answers nothing — a shorts link sitting in his history is not a page he is on.
        val shortsText = when (address) {
            is BrowserAddress.At -> address.url
            BrowserAddress.Blank -> ""
            BrowserAddress.Unreadable -> text.lowercase()
        }
        if (isBrowser && SettingsStore.blockYoutubeShorts(applicationContext) && quickBlockActive() &&
            shortsText.contains("youtube.com/shorts")
        ) {
            lastWebText = text
            withContext(Dispatchers.Main) {
                if (lastForegroundPkg == pkg && stillOnScreen(pkg)) {
                    // Keyed as the page block it is, NOT with CoverGate.SHORTS_KEY. That key is
                    // the YouTube-player scan's ownership marker: `shortsCovering` is derived from
                    // it alone, so a browser cover wearing it was removed by scheduleShortsScan on
                    // every content event ("a Shorts cover outside YouTube") and re-raised by the
                    // next scan of the churning page — the flashing the owner reported, with the
                    // page usable in between. As "web" it behaves like every other page block:
                    // held while he is on the page, dismiss-suppressed after "Got it", counted in
                    // Insights' Websites row. The log still says why=shorts.
                    showBlockScreen(title = words.get(R.string.block_shorts_title),
                        message = words.get(R.string.block_shorts_message), packageName = null,
                        counterKey = CoverGate.WEB_KEY, offenceKey = pkg, why = BlockWhy.SHORTS)
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
            filter.check(
                text, address, ownWords, autoSocialKeywords(), adultPackOn,
                SettingsStore.blockAdult(applicationContext),
                wideList = wideListOn(),
            )
        } else {
            filter.check(
                text, BrowserAddress.Unreadable, ownWords, siteKeywords = emptyList(),
                adultPackOn, blockAdult = false,
                wideList = wideListOn(),
            )
        }
        if (hit == null) {
            lastWebText = null
            return
        }
        lastWebText = text
        if (DEBUG) Log.d(TAG, "BLOCK: ${hit.title} / ${hit.message}")
        // Cover the offending page with the block screen. The overlay/Activity must be shown on
        // the main thread. (No GLOBAL_ACTION_BACK *here* — at block time it races with the
        // just-launched activity and dismisses it, same as the GLOBAL_ACTION_HOME race removed in
        // M2. That objection is about this moment and not about the exit: "Got it" on a page cover
        // does fire BACK, seconds later, on a deliberate tap with nothing launching — see
        // onCoverDismissed and CoverGate.exitFor. Keep the two apart.)
        withContext(Dispatchers.Main) {
            if (lastForegroundPkg == pkg && stillOnScreen(pkg)) {
                // A blocked WORD (or adult content) locks the whole app for a while — "Got it"
                // must not be a free pass back in. A blocked WEBSITE is gentler: cover the page
                // so the site stays blocked every visit, but don't lock the whole browser.
                if (!hit.site) addKeywordLockout(pkg, hit.word)
                if (hit.adult) {
                    recordDangerStrike(hit.word)
                    recordSiteEvidence(pkg, lastBrowserHost)
                }
                // Recorded under "web" (Insights shows one "Websites" row), but the OFFENCE is
                // this app: the lockout just added makes handleAppBlock raise a second,
                // package-keyed "Locked" cover moments from now, and one blocked word must not
                // count as two attempts. A site hit adds no lockout, so nothing follows it.
                showBlockScreen(
                    title = hit.title, message = hit.message, packageName = null,
                    counterKey = CoverGate.WEB_KEY, offenceKey = pkg, why = BlockWhy.ofWebHit(hit.site, hit.adult),
                    startedAt = startedAt,
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
        // A readable window that isn't ours settles it either way — unless it is a surface merely
        // drawn on top. The shade, the volume dialog, a heads-up notification and the keyboard all
        // genuinely become the active window while the user has not gone anywhere, and any of them
        // can land in the ~2.5s after "Got it" (a notification arriving is not a rare event). Read
        // as "positively somewhere else", that ends the exit watch and takes the cover down while
        // the blocked app is still right there behind the shade. "Can't tell" is the honest answer,
        // and ExitView.BLIND exists for it — the third invariant in docs/BLOCKING_INVARIANTS.md,
        // and this was the last place in the file still treating a transient surface as evidence.
        if (active != null && active != packageName && !isTransientSurface(active)) {
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
        // A Shorts exit is walking the player shut right now. Raising the cover again mid-walk
        // would put our own window in front of the thing it is reading, and readPlayerView answers
        // UNREADABLE to that — so every remaining step would go blind and the walk would stall
        // into give-up. The exit hands the player back here when it finishes, either way.
        if (shortsExitJob?.isActive == true) return
        if (!SettingsStore.blockYoutubeShorts(applicationContext) || !quickBlockActive() ||
            lastForegroundPkg != YOUTUBE_PKG
        ) {
            if (shortsCovering) withContext(Dispatchers.Main) { overlay.remove() }
            return
        }
        // shortsCovering is derived from the visible cover, so raising and removing it IS the
        // state change — there is no flag left to keep in step (and no window in which one says
        // "covered" while nothing is).
        // Three-way: null means the screen couldn't be read at all (see isShortsOnScreen), and
        // that must change nothing — taking the cover down on no evidence is what left Shorts
        // playing uncovered, with the next scan putting the cover straight back (a flicker, and
        // a watchable gap each time round). Leaving it up strands nothing: "Got it" removes it,
        // and scheduleShortsScan removes it as soon as YouTube stops being foreground.
        val onShorts = isShortsOnScreen()
        if (onShorts == true && !shortsCovering) {
            withContext(Dispatchers.Main) {
                // Re-confirm on the main thread before covering anything — the same guard
                // scanWebContent has always had, and this scan was missing.
                //
                // Everything above ran on a background thread, so the world can have moved on
                // while it did: the user can swipe Home (cover raised over the home screen, plus a
                // counted attempt) or lock the phone (onScreenOff does its whole cleanup, and THEN
                // this raises a cover nothing is left to tidy — a stranded cover on unlock, which
                // is the v1.98 bug arriving by a different route).
                //
                // Both halves are load-bearing: onScreenOff nulls lastForegroundPkg, which catches
                // the lock case, and stillOnScreen catches a real app switch — including one whose
                // window event never arrived, since it asks the window tree rather than the cache.
                if (lastForegroundPkg == YOUTUBE_PKG && stillOnScreen(YOUTUBE_PKG)) {
                    showBlockScreen(
                        title = words.get(R.string.block_shorts_title),
                        message = words.get(R.string.block_shorts_web_message),
                        packageName = null,
                        counterKey = CoverGate.SHORTS_KEY, why = BlockWhy.SHORTS,
                    )
                }
            }
        } else if (onShorts == false && shortsCovering) {
            withContext(Dispatchers.Main) { overlay.remove() }
        }
    }

    /** True while a just-dismissed cover's re-show should stay suppressed — see [CoverGate],
     *  which owns the rules (and is unit-tested, see CoverGateTest). */
    private fun dismissSuppressed(counterKey: String): Boolean {
        val since = stopwatchNow() - dismissedAt
        val suppressed = CoverGate.suppressed(
            counterKey, dismissedKey, dismissedPkg, lastForegroundPkg, since, dismissedViaBack,
        )
        if (suppressed) recordDecline(since)
        return suppressed
    }

    /**
     * A cover or a scan was just declined because of the dismiss grace.
     *
     * Nothing recorded this before, anywhere — see [SilenceLog]. The declines inside the short
     * grace are the mechanism working and are not counted; past it, the watcher is quiet while
     * the user sits in the app it has just covered, which is the shape of invariant 20's bug and
     * the number that would have shown it months earlier.
     */
    private fun recordDecline(sinceDismissMs: Long) {
        if (!SilenceLog.isLate(sinceDismissMs)) return
        SilenceLog.record(applicationContext, SilenceLog.LATE_DECLINES)
        if (!dismissalCountedDeaf) {
            dismissalCountedDeaf = true
            SilenceLog.record(applicationContext, SilenceLog.DEAF_DISMISSALS)
        }
    }

    /** Where the user is now, in the same unit the dismissed cover recorded — a host for a page
     *  cover, a window class for an app cover. Comparing the two kinds against each other would
     *  read as "moved" on the very first event and release the grace outright. */
    private fun currentDest(): String? =
        if (dismissedKey == CoverGate.WEB_KEY) lastBrowserHost else lastWindowClass

    /** Forget the dismissed cover, so the next block lands immediately. */
    private fun clearDismissState() {
        dismissalCountedDeaf = false
        dismissedViaBack = false
        dismissedKey = null
        dismissedPkg = null
        dismissedDest = null
        dismissedAt = 0L
    }

    /** The rule for [pkg], falling back to the pre-connect snapshot until Room has answered. */
    private fun ruleFor(pkg: String) =
        RuleSnapshot.ruleFor(pkg, rules, rulesLoaded, blockedSnapshot)

    /** The key-agnostic timing half, for the web scan. */
    private fun withinDismissWindow(): Boolean {
        val since = stopwatchNow() - dismissedAt
        val inGrace = CoverGate.inGrace(dismissedPkg, lastForegroundPkg, since, dismissedViaBack)
        if (inGrace) recordDecline(since)
        return inGrace
    }

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
        /** Which rule raised this, for the diagnostic log — see [BlockWhy]. The covers that are
         *  not a rule decision (the guard, a purchase sheet, a Shorts cover) pass their own
         *  fixed code rather than borrowing one that would misdescribe them in a report. */
        why: String = BlockWhy.UNKNOWN,
        /** When the event that led here arrived, monotonically — 0 when this cover has no
         *  meaningful start to measure from (the re-check tick, the guard, a purchase sheet).
         *  See [BlockLatency]: the interval is recorded only when there is a real one. */
        startedAt: Long = 0L,
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
        val now = stopwatchNow()
        val fresh = CoverGate.shouldCount(
            offenceKey, lastCountedOffence, now - lastCountedAt, resumingOffence,
        )
        if (offenceKey == resumingOffence) resumingOffence = null // one-shot: this was the redraw
        if (fresh) {
            AttemptCounter.record(applicationContext, counterKey)
            lastCountedOffence = offenceKey
            lastCountedAt = now
        }
        // What the breadcrumb will say, worked out here but *written* after the cover is up —
        // see the record below. Both of these have to be read now: `OwnUi.visible` and the window
        // tree describe the moment of the decision, and our own cover is about to become part of
        // both (the overlay is non-focusable and can itself report as the active window, which is
        // exactly the quirk ExitView exists for). Reading them afterwards would describe the
        // cover instead of what it covered.
        val logKind = when {
            counterKey == "strict_guard" -> "guard"
            // `why` as well as the key: the browser Shorts cover is *counted* as a web
            // block (it is a page block, and must not wear the player scan's key — see
            // scanWebContent), but in a report it is still a Shorts block and reading it
            // as "word" would send the next reader looking for a keyword that never
            // existed. Invariant 17: an instrument must mean one thing.
            counterKey == CoverGate.SHORTS_KEY || why == BlockWhy.SHORTS -> "shorts"
            packageName != null -> "app"
            else -> "word"
        }
        val logOwnUi = OwnUi.visible
        // Three-way, because the boolean this replaces meant two opposite things at once:
        // "the cover landed on the wrong app" (a bug) and "the tree was unreadable, so we
        // blocked anyway" (deliberate, invariant 1) both read as false. Report #5 turned
        // on exactly that distinction and the log could not answer it.
        //
        // Costs a look into the window tree, and only for a whole-app block: a page or word cover
        // passes no package, answers NA, and asks the system nothing at all.
        val logWindow = when {
            packageName == null -> BlockLog.Window.NA
            else -> when (rootInActiveWindow?.packageName?.toString()) {
                null -> BlockLog.Window.BLIND
                packageName -> BlockLog.Window.MATCH
                else -> BlockLog.Window.OTHER
            }
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
            // The app this cover is going up OVER, which for a page/word/purchase/guard cover is
            // the only thing that says whose it is — `packageName` above is set for whole-app
            // blocks alone. See BlockOverlay.ownerPkg and CoverGate.ownedByScan.
            ownerPkg = lastForegroundPkg,
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
        // **How long that took.** The first thing this app has ever measured about its own
        // speed: every other instrument here records whether something happened, never how long
        // it took to happen, so when the owner said the blocking was too slow there was no
        // number on the phone that could agree with him. Recorded after the cover for the same
        // reason as the breadcrumb below, and only when there is a real start to measure from.
        if (startedAt > 0L) BlockLatency.record(applicationContext, stopwatchNow() - startedAt)
        // Diagnostic breadcrumb, recorded at the one place every cover passes through. Shape
        // only — which path raised it, whether our own UI was in front, whether the window on
        // screen actually matched what we were blocking. Never the app, word or page: see
        // BlockLog. `ownUi=true` or a non-matching window here is what identifies a cover landing
        // somewhere it shouldn't, which is otherwise invisible after the milliseconds it lasts.
        //
        // **Written after the cover, not in front of it.** Recording an entry means reading the
        // stored log, splitting up to sixty entries, rebuilding the list and writing it back —
        // none of which changes a pixel of what is drawn. It used to sit between the decision and
        // the frame; it now goes on the next message, like the blocked app's icon.
        handler.post {
            runCatching {
                BlockLog.record(
                    context = applicationContext,
                    kind = logKind,
                    ownUi = logOwnUi,
                    window = logWindow,
                    why = why,
                    counted = fresh,
                )
            }
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
        dismissedKey = overlay.counterKey
        dismissedPkg = lastForegroundPkg
        // Read AFTER dismissedKey, which is what decides which unit this cover thinks in.
        dismissedDest = currentDest()
        dismissalCountedDeaf = false
        if (DEBUG) Log.d(TAG, "dismissed: key=$dismissedKey pkg=$dismissedPkg dest=$dismissedDest")
        dismissedAt = stopwatchNow()
        dismissedViaBack = false
        lastBlockedPkg = null
        // Re-arm word detection: coming back to the page that was just blocked must
        // block again, not read as "already handled" via the text dedup.
        lastWebText = null
        lastCheckedUrl = null
        // A `when` over the **enum**, deliberately, and not a chain of ifs: a non-exhaustive
        // `when` on an enum subject is a compile error, so a fourth kind of cover cannot be added
        // without something here deciding how it leaves. The old shape — a boolean for Shorts,
        // then an `if` for BACK, then a catch-all `else` — meant every new cover silently
        // inherited HOME, which is exactly how the Shorts cover came to fire the one action that
        // makes a playing video float away (see CoverGate.exitFor).
        when (CoverGate.exitFor(dismissedKey)) {
            CoverGate.Exit.BACK_THEN_HOME -> startShortsExit()

            CoverGate.Exit.BACK -> if (
                !CoverGate.backExitFailed(
                    dismissedPkg, lastBackExitPkg, stopwatchNow() - lastBackExitAt,
                )
            ) {
                // **A page cover covers a page, so its exit is off the page — not out of the app.**
                // The app underneath is not blocked (a site hit adds no lockout), and firing HOME
                // left him in a loop he reported: the blocked site is still the open tab, so coming
                // back to the browser lands on it and covers again, with no way from the cover to a
                // different page. See CoverGate.exitFor.
                //
                // The old objection to BACK is at the raise site — "it races with the just-launched
                // activity and dismisses it" — and it is about firing BACK *automatically at block
                // time*, against an activity that is still opening. This is a deliberate tap seconds
                // later with nothing launching. Different moment, different race.
                //
                // Not a bypass: the scan re-runs on whatever page BACK lands on, and another blocked
                // page covers again. The dismiss grace set above absorbs only the page being left,
                // exactly as it already does for the trip Home.
                lastBackExitPkg = dismissedPkg
                lastBackExitAt = stopwatchNow()
                dismissedViaBack = true
                overlay.remove()
                performGlobalAction(GLOBAL_ACTION_BACK)
                // **A blocked word locks the whole app, and nothing was raising that lock's cover.**
                // The lockout is recorded the instant the word is caught, but the "Locked" screen it
                // calls for waits for [handleAppBlock] to run again — and the only things that run
                // it are a foreground change and the thirty-second re-check tick. BACK keeps him in
                // the same app, and the page it lands on is static and emits nothing, so an app he
                // had just been locked out of sat there usable for up to half a minute.
                //
                // Re-decide the moment the grace ends instead. No scan and no reading of the screen:
                // the lockout is already recorded, so this is a decision whose inputs are all in
                // hand.
                val lockedPkg = lastForegroundPkg
                if (lockedPkg != null && keywordLockoutRemaining(lockedPkg) > 0L) {
                    handler.removeCallbacks(recheckRunnable)
                    handler.postDelayed(recheckRunnable, CoverGate.DISMISS_GRACE_MS + 150L)
                }
            } else {
                // BACK has already been tried in this app and left him somewhere still worth
                // covering, so this tap leaves instead. See CoverGate.backExitFailed.
                startExit(dismissedPkg)
            }

            // Everything else — a whole app, the purchase sheet, a Settings page we bounced out
            // of — is held until the user is really off the app it covered, so a swallowed HOME
            // can't leave the thing we were covering exposed.
            CoverGate.Exit.HOME -> startExit(dismissedPkg)
        }
    }

    /**
     * "Got it" on a **Shorts** cover: close the reel, confirm it closed, and only then leave
     * YouTube. See [ShortsExit] for the rules and [CoverGate.Exit.BACK_THEN_HOME] for why this is
     * a third exit rather than a variation on the other two.
     *
     * ## Why the cover comes down first, against this file's usual instinct
     *
     * Every other exit **holds** the cover until the user is verifiably off the app. Here that
     * would break the mechanism: [readPlayerView] answers `UNREADABLE` whenever our own package is
     * the active window, and our cover is exactly that. Holding it up would blind every reading
     * and reduce this to firing blind actions — which is the bug being fixed, not a safer version
     * of it.
     *
     * Taking it down is safe because [CoverGate.suppressed] exempts Shorts covers: if the close
     * fails, [scheduleShortsScan] re-covers the player within a tick. And it makes the failure
     * mode of this whole walk *"nothing happens"* rather than *"a cover is stuck"* — invariant 7
     * (never trap the user) satisfied by construction rather than by a timeout.
     *
     * The first BACK is unconditional and fires before any reading: at the instant "Got it" is
     * tapped, "a Short is on screen" is the best-evidenced fact in the system — the cover is up,
     * and its own scan takes it down as soon as that stops being true.
     */
    private fun startShortsExit() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        // Recorded because a BACK really was fired *inside* the app: the dismiss grace should be
        // the short one, not the eight seconds priced for a swallowed HOME. CoverGate.inGrace asks
        // for the move that was actually made, not the key it was made for.
        dismissedViaBack = true
        overlay.remove()
        val startedAt = stopwatchNow()
        shortsExitJob?.cancel()
        shortsExitJob = scope.launch {
            var backsSent = 1
            var closedReads = 0
            while (true) {
                delay(ShortsExit.CLOSE_POLL_MS)
                val view = readPlayerView()
                closedReads = if (view == PlayerView.CLOSED) closedReads + 1 else 0
                val step = ShortsExit.next(
                    view, backsSent, closedReads, stopwatchNow() - startedAt,
                )
                if (DEBUG) Log.d(TAG, "shortsExit: view=$view backs=$backsSent step=$step")
                when (step) {
                    ShortsExit.Step.BACK -> {
                        backsSent++
                        withContext(Dispatchers.Main) {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                        }
                    }
                    ShortsExit.Step.HOME -> {
                        SilenceLog.record(applicationContext, SilenceLog.SHORTS_EXIT_CLOSED)
                        withContext(Dispatchers.Main) {
                            // Hand over to the ordinary exit watcher, which owns leaving an app
                            // and re-tries a swallowed HOME. It restamps exitStartedAt, so the
                            // trip Home gets its own full budget rather than what this walk left.
                            //
                            // dismissedViaBack goes back to false: the move that now decides the
                            // grace is a trip Home, and that one needs the long window.
                            dismissedViaBack = false
                            startExit(YOUTUBE_PKG)
                        }
                        return@launch
                    }
                    // He is already out of YouTube — the BACK that closed the reel also left the
                    // app, or he walked out himself. Nothing to press.
                    ShortsExit.Step.DONE -> return@launch
                    ShortsExit.Step.GIVE_UP -> {
                        // ⚠️ Presses NOTHING. A close that could not be confirmed must never be
                        // followed by HOME: HOME on a playing Short is what hands it to a floating
                        // window, which is the entire reason this walk exists. Leaving it alone
                        // costs nothing — the cover is already down and the scan owns the player
                        // again, so a Short still playing is re-covered on the next tick.
                        SilenceLog.record(applicationContext, SilenceLog.SHORTS_EXIT_BLIND)
                        withContext(Dispatchers.Main) { scheduleShortsScan() }
                        return@launch
                    }
                    ShortsExit.Step.WAIT -> Unit
                }
            }
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
        exitStartedAt = stopwatchNow()
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
            // Spend the dismiss grace here too, for the exits that never pass through a
            // foreground change at all — screen-off calls this directly. NOT the main release:
            // this branch is unreachable whenever the watcher cannot read the window tree, which
            // is most of the time on gesture nav, and relying on it left the grace running in
            // exactly the case that mattered. onForegroundChanged owns the release; see
            // CoverGate.graceSpentBy.
            clearDismissState()
        } else if (confirmed) {
            // HOME never landed and the user IS still in the blocked app. The cover has to come
            // down (never trap anyone), so schedule the re-check for just after the dismiss
            // grace lapses — otherwise the app would stay uncovered until the ordinary 30s
            // tick. Mark that redraw as a resume so it lands as the SAME block: no new attempt,
            // no new quote, without the plain cooldown having to be long enough to reach it.
            resumingOffence = lastCountedOffence
            val untilGraceEnds = CoverGate.DISMISS_GRACE_STUCK_MS -
                (stopwatchNow() - dismissedAt)
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

    /**
     * Android telling us to stop what we are doing. There is nothing to stop — this service holds
     * no speech, no long-running feedback — so the body stays empty of *behaviour*.
     *
     * It is no longer empty of *evidence*. This was the one framework callback that says something
     * is happening to this service, and it recorded nothing, so an outage's first moment left no
     * trace anywhere. It is not a failure on its own; it fires for ordinary reasons too. But a
     * count that moves either side of an outage is the first thing anyone has had that describes
     * what precedes one. See [ServiceHealth.recordInterrupt].
     */
    override fun onInterrupt() {
        runCatching { ServiceHealth.recordInterrupt(applicationContext) }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cleared FIRST: everything below can throw, and a watcher that has begun tearing itself
        // down is not running whether or not the rest of this method completes.
        connected = false
        // The binding is being taken down in an orderly way, and *that* is the finding — an OEM
        // force-stop never gets here, and neither does a killed process. See recordUnbind: a
        // stamp at the start of an outage means something ended the binding, and no stamp means
        // the process vanished without being told anything.
        runCatching { ServiceHealth.recordUnbind(applicationContext) }
        handler.removeCallbacks(heartbeatRunnable)
        handler.removeCallbacks(webScanRunnable)
        handler.removeCallbacks(shortsScanRunnable)
        handler.removeCallbacks(recheckRunnable)
        handler.removeCallbacks(focusClearRunnable)
        handler.removeCallbacks(exitRunnable)
        handler.removeCallbacks(confirmForegroundRunnable)
        exitTarget = null
        // Stop location updates so they don't leak past the service (battery + privacy).
        stopLocationUpdates()
        networkCallback?.let { cb ->
            runCatching {
                (getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager)
                    ?.unregisterNetworkCallback(cb)
            }
        }
        networkCallback = null
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
        // Cancels every scan/exit job in one go — they are all launched on this scope, so there is
        // nothing to enumerate here and nothing that can be forgotten when a new one is added.
        // Screen-off is the case that has to name them individually, and CodeShapeTest checks it.
        scope.cancel()
    }

    companion object {
        private const val TAG = "AppBlocker"
        private const val DEBUG = false // flip to true to log scans/blocks for debugging

        /**
         * Whether the watcher is bound and running **right now**.
         *
         * Android's accessibility setting records the user's *choice*, not the service's state,
         * so [AccessibilityUtil.isEnabled] keeps saying "on" after the phone has killed us — the
         * failure the owner hits by switching to Xiaomi's Second Space and back. Nothing outside
         * this service could tell the difference, so the app agreed with the lie for two hours at
         * a time (see [protectionState]).
         *
         * It is a plain static because there is **no `android:process` in the manifest**: the
         * watcher, the activity, the tile and the WorkManager worker are all one process, so this
         * flag is a conclusive answer wherever it is read — no usage-stats permission, no waiting,
         * no guessing whether an idle phone means a dead watcher. Its one blind spot is the moment
         * after the process starts and before Android has bound us, which the caller covers with a
         * grace window rather than a longer timeout here (see `SERVICE_BIND_GRACE_MS`).
         */
        @Volatile private var connected = false

        fun isConnected(): Boolean = connected

        /** How often the watcher stamps "still alive" while nothing is happening on screen. */
        private const val HEARTBEAT_MS = 60_000L

        /** Silence this long means the service may be bound but deaf, and is worth a nudge.
         *  Generous: a phone genuinely left alone produces no events either, and the nudge is
         *  only ever a re-post of state we already hold. */
        private const val REVIVE_AFTER_SILENCE_MS = 3 * 60_000L

        // How often to re-check the app the user is currently inside (mid-use enforcement).
        private const val RECHECK_MS = 30_000L

        /** How stale a filter reading may get on the foreground path. Well inside
         *  [NetworkFilter.SETTLE_MS], so the backstop can never delay a decision the settle
         *  window has already allowed. */
        private const val NET_FILTER_POLL_MS = 20_000L
        // Throttle for refreshCachedSettingsIfStale. A minute: the listener is the fast route,
        // and this only has to bound how long a MISSED delivery can last.
        private const val CACHED_SETTINGS_POLL_MS = 60_000L
        // Web scan pacing: run once events pause for the debounce, but a churning page that
        // never pauses is still scanned at least every max-wait.
        private const val WEB_SCAN_DEBOUNCE_MS = 250L
        private const val WEB_SCAN_MAX_WAIT_MS = 700L
        // How long an app stays fully locked after a blocked word was caught in it.
        private const val KEYWORD_LOCKOUT_MS = 30 * 60_000L

        // Backoff for re-collecting the rule flow after it throws — grows per attempt to a cap
        // so a persistently failing database can't spin.
        private const val RULE_FLOW_RETRY_MS = 2_000L
        private const val RULE_FLOW_RETRY_MAX_MS = 60_000L

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

        // How long a read address stays believed once the toolbar has slid away (see
        // rememberedUrl). Long enough to cover reading a page, and bounded only as a backstop:
        // the foreground-change clear and the replace-on-next-read are what normally end it.
        private const val URL_MEMORY_MS = 10 * 60_000L

        // Throttle for isLauncherPkg's on-miss launcher re-detection.
        private const val LAUNCHER_REFRESH_MS = 30_000L
        // Throttle for isRealBrowserPkg re-reading an EMPTY browser set. Longer than the launcher
        // one: an empty answer here is usually the truth (a phone with no browser installed at
        // all), so this is a rare-but-cheap correction, not a poll.
        private const val REAL_BROWSER_REFRESH_MS = 5 * 60_000L
        // Throttle for the cached keyboard package (see imePackage). Short, so a keyboard swap
        // is noticed quickly, but enough to keep Settings.Secure off the per-event path.
        private const val IME_REFRESH_MS = 10_000L

        // The seed for "we know how to read this one" lives in PackageSets.kt beside the
        // browser detection it belongs with; see KNOWN_READABLE_BROWSERS.

        // Never keyword-scanned even with "every app" on: System UI (notification shade,
        // recents — shows app labels) and Settings (Settings→Apps lists every app's name; a
        // keyword that's also an app name would lock the user out of managing the phone).
        private val KEYWORD_SCAN_EXCLUDED = setOf("com.android.systemui", "com.android.settings")

        // Packages whose windows sit on top of whatever app is open instead of replacing it —
        // System UI (shade, volume dialog, heads-up notifications, recents) and the system
        // dialog host. The current keyboard joins them at runtime; see isTransientSurface.
        // Settings and the dialer are deliberately absent: they are real destinations.
        //
        // The Samsung three are the same thing under OEM names: panels drawn over whatever is
        // open, never somewhere you go. Without them, swiping out the Edge panel over a blocked
        // app read as leaving it — a phantom open counted against that app's daily limit and the
        // mid-use re-check cancelled, exactly what v1.98 fixed for the shade and the keyboard.
        // If one of these ever turns out to be a real destination the cost is a cover that
        // lingers when you open it: visible and reportable, not a silent hole.
        private val TRANSIENT_SURFACES = setOf(
            "com.android.systemui", "android",
            "com.samsung.android.app.cocktailbarservice", // Edge panel
            "com.samsung.android.game.gametools",         // Game Booster's in-game overlay
            "com.samsung.android.app.smartcapture",       // screenshot / capture toolbar
        )

        // Activity-name fragments that identify the Google Play purchase/billing sheet.
        private val PURCHASE_HINTS = listOf("acquire", "purchase", "billing")

        // Packages that host the Strict-Mode escape hatches (system Settings, MIUI's security
        // center, and the package uninstaller flows). Both lists live in GuardPackages so they
        // can be tested — they are vendor names inside a file that has no test coverage, and
        // between them they gated the uninstall guard down to three OEMs' installers.
        private val INSTALLER_PACKAGES = GuardPackages.INSTALLERS
        private val GUARD_PACKAGES = GuardPackages.GUARD

        // Activity-name fragments for the dangerous Settings pages. The accessibility and
        // device-admin ones are AOSP aliased activities (verified on the emulator). The app-info
        // fragments are best-effort for the force-stop/uninstall page — specific enough not to
        // over-match generic containers (AOSP's SPA app-info uses a shared SpaActivity we can't
        // safely match, so on that build the text fallback / device-admin block cover it instead).
        /** A fragment from the start of R.string.accessibility_description — the text Android
         *  shows on our service's own settings page, and on no other page. Lowercased to match
         *  guardScreenText(). If that string is ever reworded, reword this with it. */
        private const val OUR_SERVICE_DESCRIPTION_FRAGMENT = "appblocker uses this to detect"

        // STRICT_GUARD_HINTS and GUARD_TEXT_MARKERS used to live here, and Strict Mode bounced any
        // page they matched. Between them that was the entire Accessibility section and every
        // app's App-info page, which is why the owner reported Strict "blocking the whole
        // Settings". Both are deleted rather than left beside the narrow rule — the v1.106 lesson:
        // a superseded matcher sitting next to its replacement is how the same over-block gets
        // rebuilt later. The one case they genuinely covered on MIUI (a device-admin screen with a
        // generic class name) is now AdminScreens.MARKERS, which is testable and much narrower.

        private const val YOUTUBE_PKG = "com.google.android.youtube"

        // What WifiManager reports instead of the network name when we lack location access.
        private const val UNKNOWN_SSID = "<unknown ssid>"

    }


}
