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
}
