package com.appblocker.service

import com.appblocker.data.AppRule
import com.appblocker.data.BlockMode
import com.appblocker.data.Schedule
import com.appblocker.data.ScheduleType

/** Why an app is blocked right now — the block screen's title kicker + short human message. */
internal data class BlockReason(val title: String, val message: String)

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
            )
        }
        // Per-app HARD/SCHEDULE/LIMIT modes don't apply in Allowlist mode.
    } else if (i.rule != null && i.rule.isBlocked) {
        if (i.strict) { // Strict Mode blocks every chosen app outright.
            return BlockReason("Strict Mode", "Blocked until your Strict session ends.")
        }
        if (i.quickEnforcing) when (i.rule.mode) {
            BlockMode.HARD, BlockMode.SCHEDULE ->
                return BlockReason("Blocked", "Quick Block is on for this app.")
            BlockMode.LIMIT ->
                if (i.rule.dailyLimitMinutes >= 0 &&
                    i.usedMinutesToday(i.pkg) >= i.rule.dailyLimitMinutes
                ) return BlockReason(
                    "Daily limit reached",
                    if (i.rule.dailyLimitMinutes > 0)
                        "You've used your ${i.rule.dailyLimitMinutes} min for today."
                    else "This app is blocked for today.",
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
            ) else null
            ScheduleType.USAGE_LIMIT -> if (
                i.usedMinutesToday(i.pkg) >= s.limitMinutes
            ) BlockReason(
                "Daily limit reached",
                "${s.limitMinutes} min used today — the limit set by ${i.scheduleLabel(s)}.",
            ) else null
            ScheduleType.LAUNCH_COUNT -> if (
                i.opensToday(i.pkg) >= s.limitCount
            ) BlockReason(
                "Open limit reached",
                "Opened ${s.limitCount} times today — the limit set by ${i.scheduleLabel(s)}.",
            ) else null
            ScheduleType.WIFI -> if (i.scheduleConditionMet(s)) BlockReason(
                "Blocked on this Wi-Fi",
                if (s.wifiSsid.isBlank()) "This app is blocked while you're on Wi-Fi."
                else "This app is blocked on “${s.wifiSsid}”.",
            ) else null
            ScheduleType.LOCATION -> if (i.scheduleConditionMet(s)) BlockReason(
                "Blocked at this location",
                "This app is blocked here by ${i.scheduleLabel(s)}.",
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
        )
    }

    return null
}
