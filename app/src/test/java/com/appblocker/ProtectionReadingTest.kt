package com.appblocker

import com.appblocker.service.AccessibilityUtil
import com.appblocker.service.ProtectionNotifier
import com.appblocker.service.UsageTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two readings the app treats as facts about itself: "is the watcher on" and "how long has this
 * app been used today". Neither changes what gets blocked directly — the first is status, the
 * second is what a daily limit is compared against — and both fail *quietly* when they are wrong,
 * which is the only kind of failure this app cannot notice.
 */
class ProtectionReadingTest {

    private val us =
        "com.appblocker/com.appblocker.service.BlockerAccessibilityService"

    // --- is the watcher on ---

    @Test
    fun `the long form is recognised`() {
        assertTrue(AccessibilityUtil.isListed("$us:com.other/com.other.Svc", us))
    }

    @Test
    fun `the short form is recognised too`() {
        // The bug this test exists for: which spelling lands in the setting depends on what wrote
        // it, and comparing against flattenToString() alone reads a running service as OFF.
        assertTrue(
            AccessibilityUtil.isListed(
                "com.appblocker/.service.BlockerAccessibilityService", us,
            ),
        )
    }

    @Test
    fun `it is found wherever it sits in the list, and around whitespace`() {
        assertTrue(AccessibilityUtil.isListed("com.a/com.a.S: $us :com.b/.S", us))
    }

    @Test
    fun `another app's service is never mistaken for ours`() {
        // The permissive direction must not become "anything accessibility-ish counts".
        assertFalse(AccessibilityUtil.isListed("com.other/.service.BlockerAccessibilityService", us))
        assertFalse(AccessibilityUtil.isListed("com.appblocker/.service.SomethingElse", us))
    }

    @Test
    fun `nothing enabled reads as off`() {
        assertFalse(AccessibilityUtil.isListed(null, us))
        assertFalse(AccessibilityUtil.isListed("", us))
    }

    // --- how long has this app been used today ---

    @Test
    fun `a lower reading is a failed read, not a reset`() {
        // queryUsageStats comes back empty for reasons unrelated to the truth (access revoked,
        // the stats DB rotating, an OEM throttling). Taking that as "0 minutes used today" makes
        // the daily limit silently stop existing.
        assertEquals(42, UsageTracker.mergeUsedToday(previous = 42, fresh = 0))
        assertEquals(42, UsageTracker.mergeUsedToday(previous = 42, fresh = 41))
    }

    @Test
    fun `a higher reading is adopted, because that is the whole point`() {
        assertEquals(43, UsageTracker.mergeUsedToday(previous = 42, fresh = 43))
        assertEquals(7, UsageTracker.mergeUsedToday(previous = null, fresh = 7))
    }

    @Test
    fun `with nothing known yet, zero is still zero`() {
        // A phone that has never had usage access must not start inventing minutes.
        assertEquals(0, UsageTracker.mergeUsedToday(previous = null, fresh = 0))
    }

    // ---- how often the "blocking has stopped" alert floats again (v1.135) ---------------

    /**
     * **Asked for as "make the notifications much more persisting and floating so i see them".**
     *
     * He wasn't seeing them because the alert could float exactly once, ever: the notification
     * carried `setOnlyAlertOnce(true)`, and an in-memory "already posted" flag stopped it being
     * re-posted at all. The flag asked *have I posted this*; the question that matters is *when
     * did he last see it*.
     */
    @Test fun theFirstSightingAlwaysFloats() {
        assertTrue(ProtectionNotifier.shouldRefloat(lastAtRt = 0L, nowRt = 90_000L, force = false))
    }

    @Test fun itStaysQuietInsideTheGap() {
        assertFalse(
            ProtectionNotifier.shouldRefloat(
                lastAtRt = 10_000L,
                nowRt = 10_000L + ProtectionNotifier.REFLOAT_MS - 1,
                force = false,
            ),
        )
    }

    @Test fun itFloatsAgainOnceTheGapHasPassed() {
        assertTrue(
            ProtectionNotifier.shouldRefloat(
                lastAtRt = 10_000L,
                nowRt = 10_000L + ProtectionNotifier.REFLOAT_MS,
                force = false,
            ),
        )
    }

    /** Opening the app is the best moment there is to say blocking is dead — he is holding the
     *  phone. `AppRoot` has always passed this; the notifier was ignoring it. */
    @Test fun openingTheAppAlwaysFloatsItAgain() {
        assertTrue(ProtectionNotifier.shouldRefloat(lastAtRt = 10_000L, nowRt = 10_001L, force = true))
    }

    /** Every uncertainty resolves towards him seeing it — a clock that has gone backwards must not
     *  be able to silence the one alert that means nothing is being blocked (invariant 9). */
    @Test fun aBackwardsClockFloatsRatherThanSilences() {
        assertTrue(ProtectionNotifier.shouldRefloat(lastAtRt = 90_000L, nowRt = 1_000L, force = false))
    }
}
