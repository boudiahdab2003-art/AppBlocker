package com.appblocker

import com.appblocker.data.BootAudit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **Did our own start-up run after the phone restarted?**
 *
 * The question nothing in the app could answer, written after the longest stoppage ever recorded
 * on the owner's phone: 351 minutes, `after=boot`, and 350 of those minutes before anything
 * noticed. The one number that looked like evidence — `boots` — is Android's own
 * `Settings.Global.BOOT_COUNT`, read on demand, and says nothing about whether our receiver ran.
 *
 * The rule is pure so it can be stated here rather than trusted: unit tests run with
 * `isReturnDefaultValues = true`, so an un-mocked `SystemClock.elapsedRealtime()` returns 0 in
 * silence and a rule only expressed against the device is a rule nobody checks.
 */
class BootAuditTest {

    @Test
    fun `a boot our receiver heard reports how long after it ran`() {
        assertEquals(14_000L, BootAudit.lagFor(storedBoot = 61, storedRt = 14_000L, nowBoot = 61))
    }

    @Test
    fun `a boot the receiver never heard reports missed`() {
        assertEquals(
            BootAudit.MISSED,
            BootAudit.lagFor(storedBoot = 60, storedRt = 9_000L, nowBoot = 61),
        )
    }

    @Test
    fun `no stamp at all is never, which is not the same as missed`() {
        assertEquals(BootAudit.NEVER, BootAudit.lagFor(storedBoot = -2, storedRt = 0L, nowBoot = 61))
        // A fresh install that has not seen a restart must not accuse the phone of anything.
        assertEquals(BootAudit.NEVER, BootAudit.lagFor(storedBoot = 61, storedRt = 0L, nowBoot = 61))
    }

    // --- judging a boot missed ----------------------------------------------------------------

    /**
     * ⚠️ **The first thing that runs after a boot must not be the thing that judges it.**
     *
     * The first version did, and that is a race it loses more often than it wins: Android binds an
     * enabled accessibility service early, so `onServiceConnected` reaches `noteRun` before
     * `BOOT_COMPLETED` is delivered — and on a file-based-encryption phone that broadcast waits
     * for the first unlock, hours later. `bootsMissed` would have climbed on every restart and
     * reported the exact opposite of the truth, about the one question the instrument exists for.
     */
    @Test
    fun `first sight of a boot judges nothing`() {
        assertEquals(
            BootAudit.Judgement.WAIT,
            BootAudit.judge(heard = false, firstSeenRt = 0L, nowRt = 4_000L),
        )
    }

    @Test
    fun `a boot is only missed once the receiver has had its chance`() {
        // Seen at 4s, asked again at 30s — still inside the grace, still not evidence.
        assertEquals(
            BootAudit.Judgement.WAIT,
            BootAudit.judge(heard = false, firstSeenRt = 4_000L, nowRt = 30_000L),
        )
        // Seen at 4s, asked again three minutes later: the receiver was never coming.
        assertEquals(
            BootAudit.Judgement.MISSED,
            BootAudit.judge(heard = false, firstSeenRt = 4_000L, nowRt = 184_000L),
        )
    }

    @Test
    fun `a heard boot is settled immediately, whenever it was heard`() {
        assertEquals(
            BootAudit.Judgement.HEARD,
            BootAudit.judge(heard = true, firstSeenRt = 0L, nowRt = 1_000L),
        )
        // The FBE case: heard hours later, at the first unlock. Still heard, never missed.
        assertEquals(
            BootAudit.Judgement.HEARD,
            BootAudit.judge(heard = true, firstSeenRt = 4_000L, nowRt = 6 * 3_600_000L),
        )
    }

    /**
     * `DeviceBoot.count` returns -1 when the counter cannot be read, on both sides of the
     * comparison. Equal is the right answer: "can't tell" must not invent a missed boot and put a
     * red line on a healthy phone — invariant 11, and the exact failure the 5 Sep report pass was
     * about.
     */
    @Test
    fun `an unreadable boot counter does not invent a missed boot`() {
        assertEquals(5_000L, BootAudit.lagFor(storedBoot = -1, storedRt = 5_000L, nowBoot = -1))
    }

    // --- the first stamp of a boot stands (invariant 77, 15 Sep 2026) ---------------------------

    /**
     * A comeback after a force stop inside the ten minutes `isBoot` still calls the boot delivers a
     * second `BOOT_COMPLETED` in the same boot. Stamping it would replace the boot's real lag with a
     * later one: 40 seconds becoming five minutes.
     */
    @Test
    fun `a stamp already written in this boot stands`() {
        assertEquals(
            true,
            BootAudit.keepsEarlierStamp(storedBoot = 61, storedRt = 40_000L, nowBoot = 61, nowRt = 300_000L),
        )
    }

    /**
     * The last boot's stamp at 40 s, and this boot five minutes up. ⚠️ Now has to be LATER than the
     * stored time, or "a stored time no later than now" rejects the stamp on its own and the boot
     * comparison this test is named for decides nothing — which is how its first version stayed green
     * with that comparison broken (15 Sep 2026).
     */
    @Test
    fun `a stamp from an earlier boot is replaced`() {
        assertEquals(
            false,
            BootAudit.keepsEarlierStamp(storedBoot = 60, storedRt = 40_000L, nowBoot = 61, nowRt = 300_000L),
        )
    }

    @Test
    fun `no stamp yet is always written`() {
        assertEquals(
            false,
            BootAudit.keepsEarlierStamp(storedBoot = -2, storedRt = 0L, nowBoot = 61, nowRt = 35_000L),
        )
    }

    /** -1 on both sides compares equal for ever: keeping would freeze one stamp over every boot after. */
    @Test
    fun `an unreadable boot counter never keeps a stamp`() {
        assertEquals(
            false,
            BootAudit.keepsEarlierStamp(storedBoot = -1, storedRt = 40_000L, nowBoot = -1, nowRt = 300_000L),
        )
    }

    /** A stored time later than now is a restart the counter did not see. */
    @Test
    fun `a stamp from later than now is a restart the counter missed`() {
        assertEquals(
            false,
            BootAudit.keepsEarlierStamp(storedBoot = 61, storedRt = 900_000L, nowBoot = 61, nowRt = 35_000L),
        )
    }

    // --- a BOOT_COMPLETED that is not a boot (invariant 77) -----------------------------------

    /**
     * ⚠️ **Since Android 15 a force-stopped app is sent `BOOT_COMPLETED` when it next starts, with no
     * restart at all.** On the Android 16 emulator on 15 Sep 2026 it arrived 0.2 s after a force
     * stop, at exactly the cold starts `dumpsys activity start-info` marked `wasForceStopped=true`.
     * The figures below are that run's, in milliseconds of elapsed realtime.
     */
    @Test
    fun `a start Android marked force-stopped says so`() {
        val starts = listOf(BootAudit.ColdStart(launchRtMs = 957_138L, forceStopped = true))
        assertEquals(true, BootAudit.startedAfterForceStop(starts, processStartRt = 957_190L))
    }

    @Test
    fun `an ordinary cold start says it was not force-stopped`() {
        val starts = listOf(BootAudit.ColdStart(launchRtMs = 583_706L, forceStopped = false))
        assertEquals(false, BootAudit.startedAfterForceStop(starts, processStartRt = 583_760L))
    }

    /** The records outlive a reboot, so the newest can be the process that died before it. */
    @Test
    fun `a record from before the restart does not speak for this process`() {
        val starts = listOf(BootAudit.ColdStart(launchRtMs = 957_138L, forceStopped = true))
        assertEquals(null, BootAudit.startedAfterForceStop(starts, processStartRt = 31_000L))
    }

    @Test
    fun `the record nearest this process start decides`() {
        val starts = listOf(
            BootAudit.ColdStart(launchRtMs = 410_501L, forceStopped = true),
            BootAudit.ColdStart(launchRtMs = 413_900L, forceStopped = false),
        )
        assertEquals(false, BootAudit.startedAfterForceStop(starts, processStartRt = 413_950L))
    }

    @Test
    fun `nothing to read is cannot tell, never a guess`() {
        assertEquals(null, BootAudit.startedAfterForceStop(null, processStartRt = 957_190L))
        assertEquals(null, BootAudit.startedAfterForceStop(emptyList(), processStartRt = 957_190L))
        assertEquals(
            null,
            BootAudit.startedAfterForceStop(
                listOf(BootAudit.ColdStart(launchRtMs = 1_000L, forceStopped = true)),
                processStartRt = 0L,
            ),
        )
    }

    @Test
    fun `a start after a force stop long into a boot is not the boot`() {
        // The emulator's: sixteen minutes up. And the owner's report: 58531 s.
        assertEquals(false, BootAudit.isBoot(forceStopped = true, uptimeMs = 957_190L))
        assertEquals(false, BootAudit.isBoot(forceStopped = true, uptimeMs = 58_531_000L))
    }

    @Test
    fun `a start Android did not mark is the boot however late, and so is cannot tell`() {
        // The file-based-encryption phone: heard at the first unlock, hours in. Still the boot.
        assertEquals(true, BootAudit.isBoot(forceStopped = false, uptimeMs = 6 * 3_600_000L))
        assertEquals(true, BootAudit.isBoot(forceStopped = null, uptimeMs = 6 * 3_600_000L))
    }

    /**
     * If Android binds the listener as the phone comes up, that bind is what takes a force-stopped
     * app out of the stopped state — the boot heard through another door. Calling it missed would
     * put a red line on a phone that did start the blocker.
     */
    @Test
    fun `a start after a force stop as the phone comes up is the boot`() {
        assertEquals(true, BootAudit.isBoot(forceStopped = true, uptimeMs = 40_000L))
    }
}
