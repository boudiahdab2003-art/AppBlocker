package com.appblocker

import com.appblocker.data.ProtectionPulse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pulse the periodic worker leaves behind, so a second wake source can notice that
 * **WorkManager itself has stopped running**.
 *
 * Every out-of-process check this app has is a WorkManager job, and `ProtectionScheduler` says in
 * its own KDoc that the conditions which kill the watcher are the conditions that throttle
 * WorkManager. So "is the scheduler still alive?" is a real question with no answer anywhere on
 * the phone — and the alarm that asks it must never fire the expensive check on a bad reading.
 */
class ProtectionPulseTest {

    private val minute = 60_000L

    /** The ordinary case: same boot, monotonic difference. */
    @Test fun theGapIsMeasuredMonotonically() =
        assertEquals(
            20 * minute,
            ProtectionPulse.sinceStamp(
                stampedRt = 5 * minute, stampedBoot = 7, nowRt = 25 * minute, nowBoot = 7,
            ),
        )

    /**
     * ⚠️ A reboot resets `elapsedRealtime`, so the difference across one is not an interval — and
     * it is not evidence either: everything is freshly scheduled after a boot. Inventing a number
     * here would make the alarm fire the full check on every single restart.
     */
    @Test fun aRebootMakesTheGapUnknownRatherThanWrong() =
        assertEquals(
            ProtectionPulse.UNKNOWN,
            ProtectionPulse.sinceStamp(
                stampedRt = 40 * minute, stampedBoot = 7, nowRt = 2 * minute, nowBoot = 8,
            ),
        )

    /** Nothing stamped yet is not silence. `ensureBaseline` is what stops that being forever. */
    @Test fun noStampIsUnknown() =
        assertEquals(
            ProtectionPulse.UNKNOWN,
            ProtectionPulse.sinceStamp(
                stampedRt = 0L, stampedBoot = -2, nowRt = 90 * minute, nowBoot = 7,
            ),
        )

    /**
     * An unreadable boot counter is -1 on both sides and compares equal, which is right:
     * "can't tell" must not invent a reboot (invariant 11).
     */
    @Test fun anUnreadableBootCounterIsNotAReboot() =
        assertEquals(
            10 * minute,
            ProtectionPulse.sinceStamp(
                stampedRt = 5 * minute, stampedBoot = -1, nowRt = 15 * minute, nowBoot = -1,
            ),
        )

    /** A backwards monotonic reading clamps to zero rather than going negative. */
    @Test fun timeGoingBackwardsIsClampedNotNegative() =
        assertTrue(
            ProtectionPulse.sinceStamp(
                stampedRt = 30 * minute, stampedBoot = 7, nowRt = 10 * minute, nowBoot = 7,
            ) >= 0L,
        )

    /**
     * The threshold has to sit clear of the worker's own fifteen-minute cycle plus the lateness
     * WorkManager is allowed, or the alarm becomes a second copy of a check that is merely running
     * a few minutes behind — which is the cost this design exists to avoid.
     */
    @Test fun theSilenceThresholdIsLaterThanLate() =
        assertTrue(
            "worker cycle is 15 min; the threshold must be comfortably past it",
            com.appblocker.service.WORKER_SILENT_MS >= 20 * minute,
        )
}
