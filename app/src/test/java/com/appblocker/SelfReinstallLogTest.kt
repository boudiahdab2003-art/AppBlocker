package com.appblocker

import com.appblocker.data.SelfReinstallLog
import com.appblocker.data.SelfReinstallLog.Skip
import com.appblocker.data.SelfReinstallLog.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Invariant 81: when AppBlocker reinstalls itself to revive a killed watcher, and what each attempt
 * came to. Every refusal and every verdict is a pure rule, so each is pinned here.
 */
class SelfReinstallLogTest {

    private val minute = 60_000L

    private fun decide(
        possible: Boolean = true,
        canInstall: Boolean = true,
        watcherUnbound: Boolean = true,
        appOpen: Boolean = false,
        updatePaused: Boolean = false,
        attemptPending: Boolean = false,
        sinceLastAttemptMs: Long = -1L,
        futileStreak: Int = 0,
    ) = SelfReinstallLog.decide(
        possible, canInstall, watcherUnbound, appOpen, updatePaused, attemptPending,
        sinceLastAttemptMs, futileStreak,
    )

    @Test
    fun `a killed watcher with nothing in the way is repaired`() {
        assertNull(decide())
    }

    @Test
    fun `each reason to hold back is its own answer`() {
        assertEquals("the Play build, or below Android 12", Skip.NOT_POSSIBLE, decide(possible = false))
        assertEquals(Skip.NO_INSTALL_PERMISSION, decide(canInstall = false))
        assertEquals("a bound watcher is not a killed one", Skip.NOT_UNBOUND, decide(watcherUnbound = false))
        assertEquals("the install would close the app under his fingers", Skip.APP_OPEN, decide(appOpen = true))
        assertEquals("the update pause is waiting for him", Skip.UPDATE_PAUSED, decide(updatePaused = true))
        assertEquals(Skip.ATTEMPT_PENDING, decide(attemptPending = true))
    }

    @Test
    fun `it waits a quarter of an hour between tries`() {
        assertEquals(Skip.COOLDOWN, decide(sinceLastAttemptMs = 14 * minute))
        assertNull(decide(sinceLastAttemptMs = 15 * minute))
        assertNull("-1 is no attempt since this boot", decide(sinceLastAttemptMs = -1L))
    }

    @Test
    fun `three futile tries stand it down for a day`() {
        val streak = SelfReinstallLog.MAX_FUTILE_STREAK
        assertEquals(Skip.GAVE_UP, decide(futileStreak = streak, sinceLastAttemptMs = 20 * minute))
        assertEquals(Skip.GAVE_UP, decide(futileStreak = streak, sinceLastAttemptMs = 23 * 60 * minute))
        assertNull(decide(futileStreak = streak, sinceLastAttemptMs = 24 * 60 * minute))
        assertNull("a new boot starts over", decide(futileStreak = streak, sinceLastAttemptMs = -1L))
        assertNull(decide(futileStreak = streak - 1, sinceLastAttemptMs = 20 * minute))
    }

    private fun judge(
        since: Long,
        rebound: Boolean,
        outcome: String? = null,
        sameBoot: Boolean = true,
    ): Verdict? {
        val attempt = 1_000_000L
        return SelfReinstallLog.judge(
            pendingRt = attempt,
            pendingBoot = 7,
            outcome = outcome,
            nowRt = attempt + since,
            boot = if (sameBoot) 7 else 8,
            rebound = rebound,
        )
    }

    @Test
    fun `a rebind within minutes is the repair working`() {
        assertEquals(Verdict.HELPED, judge(since = 5_000L, rebound = true))
        assertEquals(Verdict.HELPED, judge(since = SelfReinstallLog.JUDGE_WINDOW_MS, rebound = true))
        assertEquals("too late to credit", Verdict.NO_REBIND, judge(since = SelfReinstallLog.JUDGE_WINDOW_MS + 1, rebound = true))
    }

    @Test
    fun `a phone that asked for a tap gets longer to be tapped`() {
        val tap = SelfReinstallLog.OUTCOME_TAP
        assertEquals(Verdict.HELPED, judge(since = 8 * minute, rebound = true, outcome = tap))
        assertNull("still waiting for his tap", judge(since = 8 * minute, rebound = false, outcome = tap))
        assertEquals(Verdict.NEEDS_TAP, judge(since = SelfReinstallLog.STALE_PENDING_MS, rebound = false, outcome = tap))
    }

    @Test
    fun `a refused install is a failure at once`() {
        assertEquals(Verdict.FAILED, judge(since = 5_000L, rebound = false, outcome = SelfReinstallLog.OUTCOME_FAILED))
    }

    @Test
    fun `nothing is judged too early, and silence is judged as no rebind`() {
        assertNull(judge(since = 2 * minute, rebound = false))
        assertEquals(Verdict.NO_REBIND, judge(since = SelfReinstallLog.STALE_PENDING_MS, rebound = false))
    }

    @Test
    fun `a restart in between is evidence neither way`() {
        assertEquals(Verdict.DROPPED, judge(since = 5_000L, rebound = true, sameBoot = false))
    }

    @Test
    fun `no attempt means nothing to judge`() {
        assertNull(SelfReinstallLog.judge(0L, 7, null, 5_000L, 7, rebound = true))
    }
}
