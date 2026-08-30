package com.appblocker

import com.appblocker.data.OutageLog
import com.appblocker.service.PROBE_FAIL_LIMIT
import com.appblocker.service.ProtectionState
import com.appblocker.service.SERVICE_BIND_GRACE_MS
import com.appblocker.service.STALE_MIN_USED_MINUTES
import com.appblocker.service.protectionVerdict
import com.appblocker.service.bindPending
import com.appblocker.service.protectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watchdog used to check only whether the accessibility toggle was on, so a service the phone
 * had killed looked perfectly healthy. These pin down the stall rule — and, just as importantly,
 * the cases where it must stay quiet: a false "blocking stopped" alert teaches the owner to ignore
 * the real one.
 */
class ProtectionStateTest {

    private val hour = 60 * 60_000L
    private val now = 1_700_000_000_000L

    @Test fun disabledIsOff() =
        assertEquals(
            ProtectionState.OFF,
            protectionState(enabled = false, lastEventAt = now, now = now, usedMinutesSinceLastEvent = 0),
        )

    @Test fun recentEventIsHealthy() =
        assertEquals(
            ProtectionState.OK,
            protectionState(true, lastEventAt = now - 5 * 60_000L, now = now, usedMinutesSinceLastEvent = 5),
        )

    /** Freshly enabled / freshly installed: nothing has happened yet, which isn't a fault. */
    @Test fun neverRanIsHealthy() =
        assertEquals(ProtectionState.OK, protectionState(true, lastEventAt = 0L, now = now, usedMinutesSinceLastEvent = 600))

    @Test fun hoursSilentWhileThePhoneWasUsedIsStalled() =
        assertEquals(
            ProtectionState.STALLED,
            protectionState(true, lastEventAt = now - 3 * hour, now = now, usedMinutesSinceLastEvent = 40),
        )

    /** Overnight: the phone was untouched, so silence proves nothing. */
    @Test fun hoursSilentWithAnIdlePhoneIsHealthy() =
        assertEquals(
            ProtectionState.OK,
            protectionState(true, lastEventAt = now - 8 * hour, now = now, usedMinutesSinceLastEvent = 0),
        )

    /** A couple of minutes of use isn't enough to conclude anything either. */
    @Test fun briefUseIsNotEnoughToCallItStalled() =
        assertEquals(
            ProtectionState.OK,
            protectionState(true, lastEventAt = now - 3 * hour, now = now, usedMinutesSinceLastEvent = 3),
        )

    /** No usage access (it's an optional permission) = no evidence = never alarm. */
    @Test fun withoutUsageAccessItNeverAlarms() =
        assertEquals(
            ProtectionState.OK,
            protectionState(true, lastEventAt = now - 24 * hour, now = now, usedMinutesSinceLastEvent = null),
        )

    /** Just under the window still counts as healthy — the boundary is deliberate, not incidental. */
    @Test fun justUnderTheStaleWindowIsHealthy() =
        assertEquals(
            ProtectionState.OK,
            protectionState(true, lastEventAt = now - (2 * hour - 1), now = now, usedMinutesSinceLastEvent = 120),
        )

    // ---- paused after an update ----------------------------------------------------------
    // Every update switches blocking off until the user reactivates it. This used to report OK,
    // so the app blocked nothing while insisting it was fine — after every single release.

    @Test fun pausedAfterAnUpdateIsNotHealthy() =
        assertEquals(
            ProtectionState.PAUSED,
            protectionState(
                true, lastEventAt = now - 60_000L, now = now,
                usedMinutesSinceLastEvent = 1, updatePaused = true,
            ),
        )

    /** A disabled service outranks it: reactivating achieves nothing while the toggle is off. */
    @Test fun disabledOutranksPaused() =
        assertEquals(
            ProtectionState.OFF,
            protectionState(
                enabled = false, lastEventAt = now, now = now,
                usedMinutesSinceLastEvent = 0, updatePaused = true,
            ),
        )

    /** Paused outranks stalled: it's the specific, self-inflicted cause with an obvious fix. */
    @Test fun pausedOutranksStalled() =
        assertEquals(
            ProtectionState.PAUSED,
            protectionState(
                true, lastEventAt = now - 3 * hour, now = now,
                usedMinutesSinceLastEvent = 40, updatePaused = true,
            ),
        )

    /** And it must not fire when nothing is paused — the default keeps every case above honest. */
    @Test fun notPausedIsUnaffected() =
        assertEquals(
            ProtectionState.OK,
            protectionState(
                true, lastEventAt = now - 60_000L, now = now,
                usedMinutesSinceLastEvent = 1, updatePaused = false,
            ),
        )

    // ---- switched on but not running -----------------------------------------------------
    // The Second Space failure: switching space stops every app in this space, HyperOS doesn't
    // always rebind the watcher, and ENABLED_ACCESSIBILITY_SERVICES still lists us because it
    // records a CHOICE. Every case above needed two hours plus fifteen measured minutes of use
    // plus usage-stats permission to notice; this needs none of them.

    private val upFor = SERVICE_BIND_GRACE_MS + 1

    @Test fun switchedOnButNotRunningIsStalledAtOnce() =
        assertEquals(
            ProtectionState.STALLED,
            protectionState(
                true, lastEventAt = now - 60_000L, now = now, usedMinutesSinceLastEvent = 1,
                serviceConnected = false, msSinceProcessStart = upFor,
            ),
        )

    /** …and with no usage access either, which the old path required before it would speak. */
    @Test fun notRunningIsStalledWithoutUsageAccess() =
        assertEquals(
            ProtectionState.STALLED,
            protectionState(
                true, lastEventAt = now - 60_000L, now = now, usedMinutesSinceLastEvent = null,
                serviceConnected = false, msSinceProcessStart = upFor,
            ),
        )

    /**
     * The one false alarm this rule could produce: our process has just started and Android has
     * not bound the service yet. Every cold start passes through that moment, so getting this
     * wrong would mean an alert on every launch.
     */
    @Test fun notRunningInsideTheBindGraceIsHealthy() =
        assertEquals(
            ProtectionState.OK,
            protectionState(
                true, lastEventAt = now - 60_000L, now = now, usedMinutesSinceLastEvent = 1,
                serviceConnected = false, msSinceProcessStart = SERVICE_BIND_GRACE_MS - 1,
            ),
        )

    /** The boundary is deliberate: exactly at the grace, the answer is already death. */
    @Test fun theBindGraceBoundaryCounts() =
        assertEquals(
            ProtectionState.STALLED,
            protectionState(
                true, lastEventAt = now - 60_000L, now = now, usedMinutesSinceLastEvent = 1,
                serviceConnected = false, msSinceProcessStart = SERVICE_BIND_GRACE_MS,
            ),
        )

    /** Running: the new rule must be silent and leave the old path to answer. */
    @Test fun runningIsHealthy() =
        assertEquals(
            ProtectionState.OK,
            protectionState(
                true, lastEventAt = now - 60_000L, now = now, usedMinutesSinceLastEvent = 1,
                serviceConnected = true, msSinceProcessStart = upFor,
            ),
        )

    /**
     * A running watcher that has still seen nothing for hours of use is stalled for the *other*
     * reason — bound but deaf. The new rule must not shadow the old one.
     */
    @Test fun runningButSilentForHoursIsStillStalled() =
        assertEquals(
            ProtectionState.STALLED,
            protectionState(
                true, lastEventAt = now - 3 * hour, now = now, usedMinutesSinceLastEvent = 40,
                serviceConnected = true, msSinceProcessStart = upFor,
            ),
        )

    /**
     * "Couldn't tell" must behave exactly as the function did before the parameter existed —
     * the regression guard for every case above, which all omit it.
     */
    @Test fun unknownLivenessChangesNothing() =
        assertEquals(
            ProtectionState.OK,
            protectionState(
                true, lastEventAt = now - 8 * hour, now = now, usedMinutesSinceLastEvent = 0,
                serviceConnected = null, msSinceProcessStart = upFor,
            ),
        )

    /** Switched off outranks it: telling someone to revive what they switched off is nonsense. */
    @Test fun disabledOutranksNotRunning() =
        assertEquals(
            ProtectionState.OFF,
            protectionState(
                enabled = false, lastEventAt = now, now = now, usedMinutesSinceLastEvent = 0,
                serviceConnected = false, msSinceProcessStart = upFor,
            ),
        )

    /**
     * ⚠️ **Not running outranks paused — the reverse of what this file used to assert**, and the
     * reversal is the point rather than a side effect.
     *
     * The old test said "after an update the watcher genuinely is unbound for a moment while it
     * rebinds, so say Reactivate, not 'your phone killed the blocker'". The moment is real; ranking
     * PAUSED first was the wrong way to cover it. An update *is* Android killing our process
     * (invariant 21), so the pause covers exactly the window in which the watcher is most likely
     * never to come back — and with PAUSED winning, the watchdog could not reach STALLED there,
     * `recordFoundDead` never fired and no `OutageLog` episode ever opened. The leading hypothesis
     * for his outages was the one case the instrument was blind to.
     *
     * The moment is covered by [SERVICE_BIND_GRACE_MS] and by `bindPending`'s deferrals instead —
     * by waiting for a real answer rather than by preferring a comfortable one.
     */
    @Test fun notRunningOutranksPaused() =
        assertEquals(
            ProtectionState.STALLED,
            protectionState(
                true, lastEventAt = now - 60_000L, now = now, usedMinutesSinceLastEvent = 1,
                updatePaused = true, serviceConnected = false, msSinceProcessStart = upFor,
            ),
        )

    /** Inside the grace it is still PAUSED: nothing is known yet, and the pause is what he can act on. */
    @Test fun insideTheGraceAPausedWatcherIsStillPaused() =
        assertEquals(
            ProtectionState.PAUSED,
            protectionState(
                true, lastEventAt = now - 60_000L, now = now, usedMinutesSinceLastEvent = 1,
                updatePaused = true, serviceConnected = false, msSinceProcessStart = 2_000L,
            ),
        )

    // ---- "OK, or just too early to tell?" — see bindPending --------------------------------

    /**
     * **The hole this was written for.** A check WorkManager cold-started runs in a process a
     * couple of seconds old, so the bind grace forgives an unbound watcher and [protectionState]
     * answers OK — the one answer that makes the watchdog clear the alert and close the open
     * outage episode. The state is still OK; what is new is that the watchdog can now tell this
     * OK apart from a real one instead of acting on it.
     */
    @Test fun coldStartedCheckLooksHealthyButIsNotAnAnswer() {
        val justStarted = 2_000L
        assertEquals(
            ProtectionState.OK,
            protectionState(
                true, lastEventAt = now - 60_000L, now = now, usedMinutesSinceLastEvent = 1,
                serviceConnected = false, msSinceProcessStart = justStarted,
            ),
        )
        assertTrue(
            bindPending(
                enabled = true,
                serviceConnected = false, msSinceProcessStart = justStarted,
            ),
        )
    }

    /** Past the grace it is a real answer — STALLED — with nothing left to wait for. */
    @Test fun pastTheGraceIsAnAnswerNotAPendingBind() =
        assertFalse(
            bindPending(
                enabled = true,
                serviceConnected = false, msSinceProcessStart = SERVICE_BIND_GRACE_MS,
            ),
        )

    /** A bound watcher is never pending, however young the process is. */
    @Test fun connectedIsNeverPending() =
        assertFalse(
            bindPending(
                enabled = true,
                serviceConnected = true, msSinceProcessStart = 0L,
            ),
        )

    /**
     * "Couldn't tell" is not "not bound" (invariant 11). A null reading must not arm a re-check,
     * because nothing it came back to would be any more conclusive.
     */
    @Test fun cannotTellIsNotPending() =
        assertFalse(
            bindPending(
                enabled = true,
                serviceConnected = null, msSinceProcessStart = 0L,
            ),
        )

    /**
     * Switched off outranks a pending bind exactly as it outranks STALLED: he has his own answer
     * on screen, and nothing is waiting for a bind.
     */
    @Test fun disabledIsNotPending() =
        assertFalse(
            bindPending(
                enabled = false,
                serviceConnected = false, msSinceProcessStart = 2_000L,
            ),
        )

    /**
     * **An update pause IS a pending bind now** — the deliberate reversal that makes ranking
     * STALLED above PAUSED safe.
     *
     * The pause is armed by an install, and an install is Android killing our process, so this is
     * precisely the moment a rebind is legitimately in flight. Excluding it used to be harmless
     * because a paused watcher could never be called STALLED anyway; now that it can be, the
     * deferral is the only thing standing between "the update just landed" and "your phone killed
     * the blocker". Without this the reorder would cry wolf after every release he installs.
     */
    @Test fun pausedIsPendingWhileTheBindIsInFlight() =
        assertTrue(
            bindPending(
                enabled = true,
                serviceConnected = false, msSinceProcessStart = 2_000L,
            ),
        )

    // --- The probe arm: a bound watcher that cannot read the screen ---------------------------

    /**
     * **The hole this closes.** Until now the only detector for "bound but deaf" was the staleness
     * check above, which needs two hours *plus* fifteen measured minutes *plus* usage-stats
     * permission. Everything here is inside that window — five minutes of silence, no usage access
     * at all — and it is still STALLED, because a watcher answering "I cannot read a lit, unlocked
     * screen" five times running is not an absence of evidence.
     */
    @Test fun aWatcherThatCannotReadALitScreenIsStalled() =
        assertEquals(
            ProtectionState.STALLED,
            protectionState(
                true, lastEventAt = now - 5 * 60_000L, now = now,
                usedMinutesSinceLastEvent = null,
                serviceConnected = true, probeFailStreak = PROBE_FAIL_LIMIT,
            ),
        )

    /** One bad reading is a bad reading. The limit is what makes it a verdict. */
    @Test fun oneFailedProbeIsNotEnough() =
        assertEquals(
            ProtectionState.OK,
            protectionState(
                true, lastEventAt = now - 5 * 60_000L, now = now,
                usedMinutesSinceLastEvent = null,
                serviceConnected = true, probeFailStreak = PROBE_FAIL_LIMIT - 1,
            ),
        )

    /**
     * ⚠️ A streak only means something about a watcher we know is bound.
     *
     * `false` is already answered by the arm above it, and `null` is "we could not tell" — pairing
     * a persisted count with an unknown liveness is how a streak left behind by a process that
     * died deaf gets read as a verdict about the one running now.
     */
    @Test fun theProbeStreakIsIgnoredWhenLivenessIsUnknown() =
        assertEquals(
            ProtectionState.OK,
            protectionState(
                true, lastEventAt = now - 5 * 60_000L, now = now,
                usedMinutesSinceLastEvent = null,
                serviceConnected = null, probeFailStreak = PROBE_FAIL_LIMIT * 10,
            ),
        )

    /**
     * Failing probes outrank an update pause, for the same reason the unbound arm does: the pause
     * covers exactly the window after an install, which is when Android has just killed the
     * process — so ranking the pause first is what hid the leading outage hypothesis from its own
     * instrument until v1.144.
     */
    @Test fun probeFailuresOutrankAnUpdatePause() =
        assertEquals(
            ProtectionState.STALLED,
            protectionState(
                true, lastEventAt = now - 5 * 60_000L, now = now,
                usedMinutesSinceLastEvent = null, updatePaused = true,
                serviceConnected = true, probeFailStreak = PROBE_FAIL_LIMIT,
            ),
        )

    /** Telling someone to revive a service they switched off themselves would be wrong. */
    @Test fun switchedOffOutranksAFailingProbe() =
        assertEquals(
            ProtectionState.OFF,
            protectionState(
                enabled = false, lastEventAt = now, now = now,
                usedMinutesSinceLastEvent = null,
                serviceConnected = true, probeFailStreak = PROBE_FAIL_LIMIT,
            ),
        )

    /** Nothing measured is not a complaint: the arm can only ever add a verdict, never soften one. */
    @Test fun noProbesYetChangesNothing() =
        assertEquals(
            ProtectionState.OK,
            protectionState(
                true, lastEventAt = now - 5 * 60_000L, now = now,
                usedMinutesSinceLastEvent = 4,
                serviceConnected = true, probeFailStreak = 0,
            ),
        )

    // --- The state and the arm are one answer -------------------------------------------------

    /**
     * ⚠️ **`OutageLog` records which detector fired, and this is what stops that drifting from the
     * detector that actually fired.**
     *
     * Three routes now reach STALLED at wildly different speeds, and a report that says detection
     * took twenty seconds is worthless unless the arm named is the arm that did it. The obvious
     * implementation — a second function re-deriving the arm from the same inputs — is the
     * two-sources-of-truth shape this codebase has paid for repeatedly, so `protectionVerdict`
     * produces both in one pass and this sweeps the whole input space to prove the pairing holds:
     * every STALLED names a real arm, and nothing else ever names one.
     */
    @Test fun everyStalledNamesTheArmThatFoundItAndNothingElseDoes() {
        val flags = listOf(true, false)
        val liveness = listOf(true, false, null)
        val used = listOf(null, 0, STALE_MIN_USED_MINUTES, 120)
        val ages = listOf(0L, now - 60_000L, now - 3 * hour)
        val streaks = listOf(0, PROBE_FAIL_LIMIT - 1, PROBE_FAIL_LIMIT)
        val ups = listOf(0L, SERVICE_BIND_GRACE_MS - 1, SERVICE_BIND_GRACE_MS, Long.MAX_VALUE)
        var stalledSeen = 0
        for (enabled in flags) for (paused in flags) for (conn in liveness) {
            for (u in used) for (last in ages) for (streak in streaks) for (up in ups) {
                val v = protectionVerdict(
                    enabled, lastEventAt = last, now = now, usedMinutesSinceLastEvent = u,
                    updatePaused = paused, serviceConnected = conn,
                    msSinceProcessStart = up, probeFailStreak = streak,
                )
                if (v.state == ProtectionState.STALLED) {
                    stalledSeen++
                    assertTrue(
                        "STALLED with arm=${v.arm}, which is not one this log knows",
                        v.arm in OutageLog.DetectedBy.ALL && v.arm != OutageLog.DetectedBy.UNKNOWN,
                    )
                } else {
                    assertEquals("only STALLED may name an arm", null, v.arm)
                }
                assertEquals(
                    "protectionState must agree with the verdict it delegates to",
                    v.state,
                    protectionState(
                        enabled, lastEventAt = last, now = now, usedMinutesSinceLastEvent = u,
                        updatePaused = paused, serviceConnected = conn,
                        msSinceProcessStart = up, probeFailStreak = streak,
                    ),
                )
            }
        }
        // A sweep that never reached the state it is about would pass while proving nothing.
        assertTrue("the sweep never produced a STALLED", stalledSeen > 0)
    }
}
