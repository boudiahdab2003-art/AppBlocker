package com.appblocker

import com.appblocker.service.ProtectionState
import com.appblocker.service.protectionState
import org.junit.Assert.assertEquals
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
}
