package com.appblocker

import com.appblocker.service.LowBatteryLock
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariant 104 (8 Nov 2026): at 5 % or less, unplugged, only calls and messages open, so the phone
 * is never used down to Xiaomi's 1 % hibernation, which force-stops AppBlocker.
 */
class LowBatteryLockTest {

    private fun src(path: String): String =
        listOf(File("src/main/java/com/appblocker/$path"), File("app/src/main/java/com/appblocker/$path"))
            .firstOrNull { it.isFile }?.readText()
            ?: error("cannot find $path from ${File(".").absolutePath}")

    private fun r(p: Int, c: Boolean) = LowBatteryLock.Reading(p, c)

    @Test fun locksAtFiveAndBelowWhenUnplugged() {
        assertTrue(LowBatteryLock.isLocked(r(5, false)))
        assertTrue(LowBatteryLock.isLocked(r(1, false)))
        assertFalse(LowBatteryLock.isLocked(r(6, false)))
    }

    @Test fun theChargerAlwaysUnlocks() {
        assertFalse(LowBatteryLock.isLocked(r(1, true)))
        assertFalse(LowBatteryLock.isLocked(r(5, true)))
    }

    @Test fun anUnknownReadingNeverCoversThePhone() {
        assertFalse(LowBatteryLock.isLocked(null))
        assertFalse(LowBatteryLock.isLocked(r(-1, false)))
    }

    @Test fun callsAndMessagesStayOpen() {
        assertTrue(LowBatteryLock.allowed("com.google.android.dialer", essential = false, smsPkg = null))
        assertTrue(LowBatteryLock.allowed("com.android.incallui", essential = false, smsPkg = null))
        assertTrue(LowBatteryLock.allowed("com.google.android.apps.messaging", false, "com.google.android.apps.messaging"))
        assertTrue(LowBatteryLock.allowed("com.miui.home", essential = true, smsPkg = null))
        assertFalse(LowBatteryLock.allowed("com.google.android.youtube", false, "com.google.android.apps.messaging"))
        assertFalse(LowBatteryLock.allowed("com.android.chrome", false, null))
    }

    @Test fun theLockIsAskedBeforeAnyRule() {
        val s = src("service/BlockerAccessibilityService.kt")
        val i = s.indexOf("private fun handleAppBlock(")
        val body = s.substring(i, s.indexOf("\n    private fun ", i + 10))
        assertTrue("handleAppBlock must ask the low-battery lock first",
            "val reason = lowBatteryReason(pkg) ?: blockReason(pkg)" in body)
    }

    @Test fun theServiceFollowsTheBattery() {
        val s = src("service/BlockerAccessibilityService.kt")
        assertTrue("registered when the watcher binds", "registerBatteryReceiver()" in s)
        assertTrue("follows ACTION_BATTERY_CHANGED", "IntentFilter(Intent.ACTION_BATTERY_CHANGED)" in s)
        assertTrue("let go on destroy", "batteryReceiver?.let { runCatching { unregisterReceiver(it) } }" in s)
        assertTrue("a locked phone keeps the re-check alive", "LowBatteryLock.isLocked(batteryReading) ||" in s)
    }
}
