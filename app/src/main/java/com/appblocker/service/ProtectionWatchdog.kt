package com.appblocker.service

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.appblocker.data.OutageLog
import com.appblocker.data.ServiceHealth
import com.appblocker.data.SettingsStore
import com.appblocker.ui.hasUsageAccess

/**
 * Single source of truth for "is the blocking service actually on right now" — called from the
 * periodic worker, the boot receiver, and the app's own resume fast-path, so all three paths
 * stay in sync on notifying/cancelling.
 *
 * "On" means two things: switched on in Settings, and *still alive*. See [protectionState].
 */
object ProtectionWatchdog {

    /**
     * How many "too early to tell" answers in a row before the watchdog stops waiting and calls
     * it. Three, at 45 seconds apart, is a little over two minutes of a phone that kills our
     * process faster than we can finish looking at it — which is not a phone to keep quiet for.
     */
    private const val MAX_BIND_DEFERRALS = 3

    /**
     * A single look at blocking's health: the state every screen reads, plus whether that state
     * was answered by the bind grace rather than by evidence — see [bindPending].
     *
     * Both come out of **one** set of readings on purpose. Asking the same questions twice, once
     * for the state and once for the grace, is the shape that has cost this codebase more bugs
     * than any other: two sources of truth that drift.
     */
    internal data class Reading(
        val state: ProtectionState,
        val bindPending: Boolean,
        /** Which detector produced a STALLED, for `OutageLog` — null for every other state. */
        val arm: String? = null,
        /**
         * Foreground minutes since the last event the watcher saw, or null when it cannot be told
         * (no usage access, nothing seen yet, or the system service failed).
         *
         * **The number the verdict actually turns on.** A [ProtectionState.STALLED] needs
         * `STALE_MIN_USED_MINUTES` of *measured use* before quiet counts against the watcher, so
         * this is what separates four unprotected hours from a phone left on a table. It was
         * computed on every read and thrown away, which left a report carrying `lastEventMin 240`
         * and no way to tell those apart. Carried here rather than recomputed elsewhere, because
         * the point of [Reading] is that the state and the numbers behind it come from one look.
         */
        val usedMinutes: Int? = null,
        /** How long this process has been alive — separates a cold start from a real death. */
        val sinceProcessStartMs: Long = 0L,
    )

    /** The current health of blocking, for the watchdog and for the app's own status row. */
    internal fun state(context: Context, now: Long = System.currentTimeMillis()): ProtectionState =
        read(context, now).state

    internal fun read(context: Context, now: Long = System.currentTimeMillis()): Reading {
        val enabled = AccessibilityUtil.isEnabled(context)
        val lastEventAt = ServiceHealth.lastEventAt(context)
        // Usage access is optional, so this can be null — protectionState then never says STALLED.
        // The usage-stats read is also the one part of this that talks to a system service that
        // can fail (revoked access mid-read, an OEM throwing from queryEvents), and state() is
        // called from composables — ProfileScreen's status row and AppRoot's resume effect — so
        // letting it throw would crash the app on open. A failure means "can't tell", which is
        // already the null case: never a false STALLED.
        val usedMinutes = if (enabled && lastEventAt > 0L && hasUsageAccess(context)) {
            runCatching { UsageTracker.totalMinutesInRange(context, lastEventAt, now) }.getOrNull()
        } else {
            null
        }
        val updatePaused = SettingsStore.updatePaused(context)
        // The conclusive answer, available because the watcher shares this process — see
        // BlockerAccessibilityService.isConnected.
        val connected = BlockerAccessibilityService.isConnected()
        // Monotonic, from the OS rather than a field of our own (invariant 9).
        val sinceStart = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        // The watcher's own answer to "can I still read the screen?", counted by the heartbeat.
        // Read here rather than inside protectionState so the whole verdict still comes out of one
        // set of readings — see the Reading KDoc.
        val probeFails = ServiceHealth.probeFailStreak(context)
        val verdict = protectionVerdict(
            enabled, lastEventAt, now, usedMinutes,
            updatePaused = updatePaused,
            serviceConnected = connected,
            msSinceProcessStart = sinceStart,
            probeFailStreak = probeFails,
        )
        return Reading(
            state = verdict.state,
            bindPending = bindPending(enabled, connected, sinceStart),
            arm = verdict.arm,
            usedMinutes = usedMinutes,
            sinceProcessStartMs = sinceStart,
        )
    }

    /**
     * @param force pass true from the app-open/resume path so the alert always reflects the true
     *   current state (bypasses the 4-hour throttle); the background worker uses the default false.
     */
    fun checkAndNotify(
        context: Context,
        force: Boolean = false,
        // Who is asking. Carried only so that, when this check is the one that finds blocking
        // back, the episode can record what brought it back rather than leaving "recovered" to
        // mean seven different things. See OutageLog.EndedBy — the whole recovery question is
        // whether these come back "background" or "app-opened".
        calledBy: String = OutageLog.EndedBy.UNKNOWN,
    ) = guarded(context, "watchdog") {
        // Guarded for the same reason the watcher's callbacks are: this runs from the app's own
        // resume effect (AppRoot), the boot receiver and the periodic worker. An exception from
        // posting a notification — OEM notification managers do throw — would crash the app on
        // open from the resume path. The thing that tells the user blocking has stopped must not
        // itself be able to take the app down. Failures land in ServiceHealth, which the Profile
        // screen now shows.
        val reading = read(context)
        // Too early to tell: our process is seconds old and Android has not bound the watcher
        // yet — the normal shape of a check that WorkManager cold-started in order to run. There
        // is nothing here to report and, more importantly, nothing to CLEAR: falling into the OK
        // branch would cancel a live alert and close an open outage episode while blocking was
        // still down. Come back once the grace has run out. See bindPending.
        //
        // Capped, because deferring is a silence. The re-check normally lands in this same
        // process 45s later and answers properly; the count only climbs if every check gets a
        // freshly killed process, and that phone is the one that most needs telling. Past the cap
        // we stop being polite and let the verdict through.
        val deferrals = SettingsStore.bindDeferrals(context)
        if (reading.bindPending && deferrals < MAX_BIND_DEFERRALS) {
            SettingsStore.setBindDeferrals(context, deferrals + 1)
            ProtectionScheduler.scheduleRecheckSoon(context)
            return@guarded
        }
        // A real verdict follows, so the run of deferrals is over.
        if (deferrals != 0) SettingsStore.setBindDeferrals(context, 0)
        // ⚠️ Past the cap, a pending bind must become the verdict it was standing in for —
        // switched on and not running — NOT the OK that `protectionState` returns while the grace
        // holds. Falling through to that OK would cancel the alert and close the open episode,
        // which is the whole failure this deferral exists to avoid.
        val state = if (reading.bindPending) ProtectionState.STALLED else reading.state
        // ⚠️ **Leaving STALLED closes the episode, whichever way we left it.** This used to live in
        // the OK branch alone, so STALLED -> OFF (he switches accessibility off — which is the
        // repair the alert tells him to do) and STALLED -> PAUSED (an update lands mid-outage,
        // weekly for him) both left the episode open and `foundDeadPending` stuck true. The next
        // outage was then never counted, and the one eventually closed carried a duration spanning
        // hours that were not an outage at all. An instrument that keeps measuring after the thing
        // it measures has stopped is worse than one that stops.
        if (state != ProtectionState.STALLED) endOpenOutage(context, state, calledBy)
        when (state) {
            ProtectionState.OK -> {
                SettingsStore.clearProtectionOffSince(context)
                ProtectionNotifier.cancel(context)
            }
            ProtectionState.OFF -> ProtectionNotifier.notifyDisabled(context, force)
            // Switched on, but nothing has reached the watcher for hours of active use — the
            // signature of an OEM battery manager killing it. Toggling accessibility off/on
            // revives it, which is what the alert sends the user to do.
            ProtectionState.STALLED -> {
                // Counted once per occasion, not once per check: this runs from a 15-minute
                // worker, every app resume and every pull of the notification shade, so counting
                // unconditionally would turn one death into dozens and make the number useless
                // for the thing it exists for — telling whether a fix actually reduced them.
                if (!SettingsStore.foundDeadPending(context)) {
                    SettingsStore.setFoundDeadPending(context, true)
                    ServiceHealth.recordFoundDead(context)
                    // The same transition, so the count and the log can never disagree about how
                    // many outages there have been. The count says how often; this says how long,
                    // how late we noticed, and what had just happened — see OutageLog.
                    // Which arm found it travels with the episode. A deferred bind that ran out
                    // of deferrals above has no arm of its own — it IS the unbound case, standing
                    // in for the verdict the grace was holding back.
                    OutageLog.begin(
                        context,
                        ServiceHealth.lastEventAt(context),
                        detectedBy = reading.arm ?: OutageLog.DetectedBy.UNBOUND,
                    )
                }
                ProtectionNotifier.notifyStalled(context, force)
                // Come back in five minutes and float it again. The 15-minute periodic check is
                // still the backstop; this is what makes the alert insistent rather than a thing
                // he has to happen to look at. Re-armed on every check that still sees STALLED,
                // and cancelled by endOpenOutage on any exit from it.
                ProtectionScheduler.scheduleStalledRepeat(context)
            }
            // Off after an update, pending reactivation. Worth an alert precisely because it is
            // self-inflicted and easy to forget: the app was doing nothing at all, and saying it
            // was fine, until the user happened to open the Blocking tab.
            ProtectionState.PAUSED -> ProtectionNotifier.notifyPaused(context, force)
        }
    }

    /**
     * Closes an open outage episode and takes the stalled alert down with it. Called on **every**
     * exit from STALLED, not only the one that means blocking is back.
     *
     * How it ended is recorded rather than assumed, because the three endings mean different
     * things and the report is read as evidence:
     *  - `recovered` — blocking is working again. The ordinary ending.
     *  - `switched-off` — he switched accessibility off, which is the repair the alert asks for;
     *    the outage really did stop here, and the next OK is a fresh start, not this episode.
     *  - `paused` — an update landed mid-outage. Everything after this point is the pause, not
     *    the failure, and counting it would inflate the very number the log exists to establish.
     *
     * Also cancels the stalled notification specifically: it is `ongoing`, so leaving it up while
     * posting the "switched off" or "paused" one left him with a permanent alert about a state he
     * was no longer in.
     */
    private fun endOpenOutage(context: Context, state: ProtectionState, calledBy: String) {
        SettingsStore.setFoundDeadPending(context, false)
        ProtectionScheduler.cancelStalledRepeat(context)
        ProtectionNotifier.cancelStalled(context)
        // Returns null in the ordinary case, where nothing was down.
        OutageLog.end(context, endedBy = calledBy)?.let {
            BugReportSender.reportOutage(context, it, outageEndedBy(state))
        }
    }
}
