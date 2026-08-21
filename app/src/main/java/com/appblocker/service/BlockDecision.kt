package com.appblocker.service

import com.appblocker.data.AppRule
import com.appblocker.data.BlockMode
import com.appblocker.data.Schedule
import com.appblocker.data.ScheduleType

/**
 * Why a cover was raised, from a fixed vocabulary, so a bug report can say **which rule fired**
 * without saying what it fired on.
 *
 * Report #5 is what this is for: *"the app blocked claude idk why"*. The log recorded that an app
 * rule fired and nothing more, so the one question that mattered — was this your block list, a
 * schedule, a daily limit, or the unsupported-browser switch? — could not be answered from here,
 * and answering it wrong means shipping a fix for the wrong layer.
 *
 * These are codes, not sentences: [BlockReason.title] and `message` are shown to the user and get
 * reworded, while these are matched by a maintainer reading a report and must stay stable. Same
 * contract as `BlockLog.KINDS`, which is where they end up.
 */
internal object BlockWhy {
    const val LOCKOUT = "lockout"          // a blocked word locked the whole app
    const val ALLOWLIST = "allowlist"      // allowlist mode: not on the allowed list
    const val STRICT = "strict"            // Strict session blocks every chosen app
    const val QUICK = "quick"              // the app's own Quick Block rule
    const val LIMIT = "limit"              // per-app daily minute limit
    const val SCHED_TIME = "sched-time"
    const val SCHED_USAGE = "sched-usage"
    const val SCHED_LAUNCH = "sched-launch"
    const val SCHED_WIFI = "sched-wifi"
    const val SCHED_LOCATION = "sched-location"
    const val BROWSER = "browser"          // "block unsupported browsers"
    const val UNKNOWN = "?"                // decoded from a log entry written before this existed

    val ALL = setOf(
        LOCKOUT, ALLOWLIST, STRICT, QUICK, LIMIT, SCHED_TIME, SCHED_USAGE, SCHED_LAUNCH,
        SCHED_WIFI, SCHED_LOCATION, BROWSER, UNKNOWN,
    )

    // The layers that raise a cover without going through decideBlock — a page scan, the Shorts
    // scan, the settings guard, the purchase watcher. They used to be loose strings at the raise
    // sites, which is how the first two came to share one.
    const val WORD = "word"                // a blocked word was on screen
    const val SITE = "site"                // the address bar was on a blocked site
    const val ADULT = "adult"              // one of the built-in adult layers, not the user's word
    const val SHORTS = "shorts"            // Shorts, in the app or on the web
    /** The off-switch guard, split by **which** off-switch — four screens, four failure modes.
     *
     *  One `guard` code could not answer report #7 (*"i dont know why it blocked"*): the
     *  accessibility page relapsing is v1.127's shape, an uninstall confirmation is report #6's,
     *  a device-admin screen is v1.107's, and App-info-during-Strict is force-stop protection.
     *  They get opposite fixes, so a report that cannot tell them apart sends the next one to the
     *  wrong screen — the same argument [ofWebHit] makes, and invariant 17.
     *
     *  [GUARD] itself stays, because the log survives an update and older entries carry it. */
    const val GUARD = "guard"              // pre-v1.134 entries: one of the four, unknown which
    const val GUARD_SERVICE = "guard-service"      // our own accessibility page
    const val GUARD_UNINSTALL = "guard-uninstall"  // the "uninstall this app?" dialog
    const val GUARD_ADMIN = "guard-admin"          // deactivating device admin
    const val GUARD_APPINFO = "guard-appinfo"      // our App-info page during Strict (Force stop)
    const val PURCHASE = "purchase"        // the Play billing sheet

    /**
     * A page scan raises one cover for three quite different findings, and they were logged as
     * one code and then as two: a blocked WORD the user typed in themselves, a blocked WEBSITE
     * found in the address bar, and one of the three built-in ADULT layers. They fail differently
     * and get fixed differently — over-blocking is nearly always a word or the adult lists, a
     * site opening freely is nearly always the address-bar layer — so a report that cannot tell
     * them apart sends the next fix to the wrong one. Invariant 17.
     *
     * The adult split is what the 20 Aug start-page report needed and could not say: the cover
     * that came up for opening Chrome logged `why=word`, which reads as "he blocked that word
     * himself" and points the investigation at a list he had never touched.
     */
    fun ofWebHit(site: Boolean, adult: Boolean = false): String = when {
        site -> SITE
        adult -> ADULT
        else -> WORD
    }
}

/** Why an app is blocked right now — the block screen's title kicker + short human message,
 *  plus the stable [BlockWhy] code that goes in the diagnostic log. */
internal data class BlockReason(val title: String, val message: String, val why: String)

/**
 * Everything the decision needs, gathered by the service and handed over as plain values.
 *
 * The decision itself is the single most important thing this app does, and it used to be
 * unreachable by tests: a hundred lines reading a dozen service fields, preferences, the database
 * and usage stats in place. Splitting the *gathering* (the service's job — it owns the live state)
 * from the *deciding* (this file) makes the rules testable without an Android device, in the same
 * way SessionClock and TimeWindow already are.
 *
 * Only genuinely device-dependent lookups stay as lambdas, and they are called lazily so a phone
 * with no schedules never pays for them.
 */
internal data class BlockInputs(
    val pkg: String,
    /** After an app update, nothing blocks until the user reactivates. */
    val updatePaused: Boolean,
    /** Millis left on a keyword lockout for this app (0 = none), and the word that caused it. */
    val lockoutRemainingMs: Long,
    val lockoutWord: String?,
    /** A Strict session is running: it overrides pausing and every per-app mode. */
    val strict: Boolean,
    /** Quick Block is enforcing right now (Strict, or a session saying "block now", or not paused). */
    val quickEnforcing: Boolean,
    val rule: AppRule?,
    val allowlistMode: Boolean,
    /** Launcher/dialer/keyboard/system — never blocked in Allowlist mode, or the phone bricks.
     *  A lambda because answering it reads the current keyboard from Settings. */
    val isEssential: () -> Boolean,
    val schedules: List<Schedule>,
    /** This package is a browser, and one we cannot read the contents of. */
    val isUnsupportedBrowser: Boolean,
    /** "Block unsupported browsers" is on AND there is something to filter for. */
    val unsupportedBrowserBlockingActive: () -> Boolean,
    val usedMinutesToday: (String) -> Int,
    val opensToday: (String) -> Int,
    /** Wi-Fi/location conditions — device state the service reads. */
    val scheduleConditionMet: (Schedule) -> Boolean,
    val scheduleLabel: (Schedule) -> String,
    val hourMinuteLabel: (Int) -> String,
)

/**
 * The reason [BlockInputs.pkg] is blocked right now, or null when it's allowed. Checked in
 * order — keyword lockout, then Strict/Quick Block, then schedules (first match wins), then
 * unsupported browsers.
 */
internal fun decideBlock(i: BlockInputs): BlockReason? {
    // After an update: nothing blocks until the user reactivates (the update also ends
    // any Strict session — see UpdatePause).
    if (i.updatePaused) return null

    // The phone has to stay usable, so the essentials — ourselves, the launcher, the current
    // keyboard, the dialer, System UI, Settings — are never blocked by ANY layer. This has to
    // come before the lockout branch below: lockouts are keyed by the last foreground package,
    // which can be stale, and one landing on the launcher would otherwise cover the home
    // screen. (Only consulted in Allowlist mode, where the lambda is already being used and
    // everything is blocked by default; Blocklist mode blocks only what the user picked, so
    // asking would cost a Settings read on every decision for no benefit.)
    if (i.allowlistMode && i.isEssential()) return null

    // Keyword lockout: a blocked word was caught in this app recently, so the whole app
    // stays locked — no page inside it is reachable until the lockout runs out.
    if (i.lockoutRemainingMs > 0L) {
        val mins = (i.lockoutRemainingMs + 59_999L) / 60_000L
        val w = i.lockoutWord
        return BlockReason(
            "Locked",
            if (w != null) "“$w” was found here. Locked for $mins more min."
            else "A blocked word was found here. Locked for $mins more min.",
            why = BlockWhy.LOCKOUT,
        )
    }

    if (i.allowlistMode) {
        // Allowlist: block anything that isn't explicitly allowed. Essentials already returned
        // above, so they need no second check here — and isEssential() reads the current
        // keyboard from Settings, so asking twice per decision is worth avoiding.
        if (i.quickEnforcing && i.rule?.isAllowed != true) {
            return BlockReason(
                if (i.strict) "Strict Mode" else "Blocked",
                "Only your allowed apps work right now.",
                why = BlockWhy.ALLOWLIST,
            )
        }
        // Per-app HARD/SCHEDULE/LIMIT modes don't apply in Allowlist mode.
    } else if (i.rule != null && i.rule.isBlocked) {
        if (i.strict) { // Strict Mode blocks every chosen app outright.
            return BlockReason(
                "Strict Mode", "Blocked until your Strict session ends.",
                why = BlockWhy.STRICT,
            )
        }
        if (i.quickEnforcing) when (i.rule.mode) {
            BlockMode.HARD, BlockMode.SCHEDULE ->
                return BlockReason(
                    "Blocked", "Quick Block is on for this app.", why = BlockWhy.QUICK,
                )
            BlockMode.LIMIT ->
                if (i.rule.dailyLimitMinutes >= 0 &&
                    i.usedMinutesToday(i.pkg) >= i.rule.dailyLimitMinutes
                ) return BlockReason(
                    "Daily limit reached",
                    if (i.rule.dailyLimitMinutes > 0)
                        "You've used your ${i.rule.dailyLimitMinutes} min for today."
                    else "This app is blocked for today.",
                    why = BlockWhy.LIMIT,
                )
        }
    }

    // Schedules — block when the schedule's condition is currently met. First match wins.
    for (s in i.schedules) {
        if (!s.enabled || i.pkg !in s.packages) continue
        val reason = when (s.type) {
            ScheduleType.TIME -> if (i.scheduleConditionMet(s)) BlockReason(
                "Blocked by schedule",
                "${i.scheduleLabel(s)} is on until ${i.hourMinuteLabel(s.endMinutes)}.",
                why = BlockWhy.SCHED_TIME,
            ) else null
            ScheduleType.USAGE_LIMIT -> if (
                i.usedMinutesToday(i.pkg) >= s.limitMinutes
            ) BlockReason(
                "Daily limit reached",
                "${s.limitMinutes} min used today — the limit set by ${i.scheduleLabel(s)}.",
                why = BlockWhy.SCHED_USAGE,
            ) else null
            ScheduleType.LAUNCH_COUNT -> if (
                i.opensToday(i.pkg) >= s.limitCount
            ) BlockReason(
                "Open limit reached",
                "Opened ${s.limitCount} times today — the limit set by ${i.scheduleLabel(s)}.",
                why = BlockWhy.SCHED_LAUNCH,
            ) else null
            ScheduleType.WIFI -> if (i.scheduleConditionMet(s)) BlockReason(
                "Blocked on this Wi-Fi",
                if (s.wifiSsid.isBlank()) "This app is blocked while you're on Wi-Fi."
                else "This app is blocked on “${s.wifiSsid}”.",
                why = BlockWhy.SCHED_WIFI,
            ) else null
            ScheduleType.LOCATION -> if (i.scheduleConditionMet(s)) BlockReason(
                "Blocked at this location",
                "This app is blocked here by ${i.scheduleLabel(s)}.",
                why = BlockWhy.SCHED_LOCATION,
            ) else null
        }
        if (reason != null) return reason
    }

    // Unsupported browsers — if web filtering is on, block browsers we can't read so they
    // can't be used to bypass website/keyword filtering (e.g. Brave). Chrome is filterable.
    if (i.isUnsupportedBrowser && i.unsupportedBrowserBlockingActive()) {
        return BlockReason(
            "Browser blocked",
            "This browser can't be filtered, so it's blocked while word blocking is on.",
            why = BlockWhy.BROWSER,
        )
    }

    return null
}

/** How old a location fix may be and still be treated as "where the phone is now".
 *
 *  Chosen against how fixes actually arrive, not as a round number: while a Location schedule is
 *  enabled the service pulls a single fresh fix at most once a minute (on API 30+) and subscribes
 *  for updates every 30s, so in normal operation the fix is seconds old. Fifteen minutes therefore
 *  never trips by accident — it trips only when location has genuinely stopped being delivered
 *  (services off, permission downgraded to foreground-only, provider dead). */
internal const val LOCATION_MAX_AGE_MS = 15 * 60_000L

/**
 * Whether a location fix is recent enough to decide blocking with.
 *
 * There used to be no such check at all: `lastLocation` was set once and trusted forever, in both
 * directions. A fix taken at the blocked place kept blocking everywhere the user went afterwards
 * (visible, and the reason "it blocks me at work" would be reported), and a fix taken away from the
 * place meant returning to it never started blocking (invisible, so never reported). The existing
 * guard only stopped an *older* fix from overwriting a newer one — it did nothing when no new fix
 * arrived at all, which is the normal state of a stationary phone.
 *
 * Both clocks are `elapsedRealtime` nanos, which is monotonic (invariant 9): a location's age must
 * not be measurable with a clock the user can move. A fix that claims to be from the future is
 * treated as unusable rather than as infinitely fresh.
 */
internal fun locationFixUsable(
    fixElapsedNanos: Long,
    nowElapsedNanos: Long,
    maxAgeMs: Long = LOCATION_MAX_AGE_MS,
): Boolean {
    val ageMs = (nowElapsedNanos - fixElapsedNanos) / 1_000_000L
    return ageMs in 0..maxAgeMs
}
