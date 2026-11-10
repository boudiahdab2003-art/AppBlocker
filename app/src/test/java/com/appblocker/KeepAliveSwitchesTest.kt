package com.appblocker

import com.appblocker.service.KeepAliveSwitches
import com.appblocker.service.KeepAliveSwitches.State
import com.appblocker.service.ProtectionNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Invariant 109: the warning that Notification access or Autostart is off — the two switches that
 * bring a force-stopped AppBlocker back (proven on his phone, 8 Nov 2026). His picks on 10 Nov 2026:
 * a warning that stays and floats again every hour; warn only.
 */
class KeepAliveSwitchesTest {

    // ---- the read -------------------------------------------------------------------------------

    @Test
    fun `app-op modes map to on, off, and unknown`() {
        assertEquals(State.ON, KeepAliveSwitches.fromMode(0)) // MODE_ALLOWED
        assertEquals(State.OFF, KeepAliveSwitches.fromMode(1)) // MODE_IGNORED: his phone, switched off
        assertEquals(State.OFF, KeepAliveSwitches.fromMode(2)) // MODE_ERRORED
        assertEquals(State.OFF, KeepAliveSwitches.fromMode(3)) // MODE_DEFAULT: never switched on
        assertEquals(State.UNKNOWN, KeepAliveSwitches.fromMode(null)) // the read refused / threw
        assertEquals(State.UNKNOWN, KeepAliveSwitches.fromMode(4))
    }

    @Test
    fun `autostart is only read on Xiaomi phones`() {
        assertTrue(KeepAliveSwitches.isXiaomi("Xiaomi"))
        assertTrue(KeepAliveSwitches.isXiaomi("Redmi"))
        assertTrue(KeepAliveSwitches.isXiaomi("POCO"))
        assertFalse(KeepAliveSwitches.isXiaomi("samsung"))
        assertFalse(KeepAliveSwitches.isXiaomi("Google"))
        assertFalse(KeepAliveSwitches.isXiaomi(null))
    }

    @Test
    fun `unknown never counts as off, so it never warns`() {
        val unknown = KeepAliveSwitches.Reading(State.UNKNOWN, State.UNKNOWN)
        assertFalse(unknown.anyOff)
        assertFalse(KeepAliveSwitches.Reading(State.ON, State.UNKNOWN).anyOff)
        assertTrue(KeepAliveSwitches.Reading(State.OFF, State.UNKNOWN).anyOff)
        assertTrue(KeepAliveSwitches.Reading(State.ON, State.OFF).anyOff)
        assertEquals("unknown", KeepAliveSwitches.reportValue(State.UNKNOWN))
        assertEquals("off", KeepAliveSwitches.reportValue(State.OFF))
    }

    // ---- how often ------------------------------------------------------------------------------

    @Test
    fun `the warning floats again after an hour, not before`() {
        val hour = ProtectionNotifier.KEEPALIVE_REFLOAT_MS
        assertEquals(60 * 60_000L, hour)
        val t0 = 10_000_000L
        assertTrue(ProtectionNotifier.shouldRefloat(0L, t0, force = false, everyMs = hour))
        assertFalse(ProtectionNotifier.shouldRefloat(t0, t0 + hour - 1, force = false, everyMs = hour))
        assertTrue(ProtectionNotifier.shouldRefloat(t0, t0 + hour, force = false, everyMs = hour))
        // The stalled alert keeps its five minutes.
        assertTrue(ProtectionNotifier.shouldRefloat(t0, t0 + ProtectionNotifier.REFLOAT_MS, force = false))
    }

    @Test
    fun `a different set of switches is a different warning`() {
        val keys = setOf(
            ProtectionNotifier.keepAliveKey(notifOff = true, autostartOff = false),
            ProtectionNotifier.keepAliveKey(notifOff = false, autostartOff = true),
            ProtectionNotifier.keepAliveKey(notifOff = true, autostartOff = true),
        )
        assertEquals(3, keys.size)
    }

    @Test
    fun `the heartbeat reads the switches every two minutes`() {
        val t0 = 5_000_000L
        assertTrue(KeepAliveSwitches.heartbeatCheckDue(0L, t0))
        assertFalse(KeepAliveSwitches.heartbeatCheckDue(t0, t0 + KeepAliveSwitches.HEARTBEAT_CHECK_MS - 1))
        assertTrue(KeepAliveSwitches.heartbeatCheckDue(t0, t0 + KeepAliveSwitches.HEARTBEAT_CHECK_MS))
        assertTrue(KeepAliveSwitches.heartbeatCheckDue(t0, t0 - 1)) // clock went backwards: check
        assertNotEquals(0L, KeepAliveSwitches.HEARTBEAT_CHECK_MS)
    }

    // ---- reports --------------------------------------------------------------------------------

    // The HealthFacts side ("Autostart is off" is a fault) lives in HealthFactsTest beside its twin.

    @Test
    fun `the report carries the autostart key`() {
        val sender = source("service/BugReportSender.kt").readText()
        assertTrue(sender.contains("field(\"autostart\")"))
    }

    // ---- shape: every path that must check, checks ----------------------------------------------

    @Test
    fun `the watchdog checks the switches when blocking is running`() {
        val text = source("service/ProtectionWatchdog.kt").readText()
        val start = text.indexOf("SettingsStore.clearProtectionOffSince(context)")
        val end = text.indexOf("ProtectionState.OFF ->", start)
        assertTrue("OK branch not found", start in 0 until end)
        assertTrue(text.substring(start, end).contains("KeepAliveSwitches.check(context)"))
    }

    @Test
    fun `the watcher heartbeat checks the switches`() {
        val text = source("service/BlockerAccessibilityService.kt").readText()
        val start = text.indexOf("private val heartbeatRunnable")
        val end = text.indexOf("handler.postDelayed(this, HEARTBEAT_MS)", start)
        assertTrue("heartbeat not found", start in 0 until end)
        assertTrue(text.substring(start, end).contains("KeepAliveSwitches.checkFromHeartbeat("))
    }

    @Test
    fun `notification access turned off is noticed by the listener itself`() {
        val text = source("service/NotificationCountListener.kt").readText()
        for (hook in listOf("override fun onListenerDisconnected()", "override fun onListenerConnected()")) {
            val start = text.indexOf(hook)
            val end = text.indexOf("\n    }", start)
            assertTrue("$hook not found", start in 0 until end)
            assertTrue(hook, text.substring(start, end).contains("KeepAliveSwitches.checkSoon("))
        }
    }

    @Test
    fun `the warning is loud, standing, and floats every time`() {
        val text = source("service/ProtectionNotifier.kt").readText()
        val chan = text.indexOf("CHANNEL_ID_KEEPALIVE,\n").let { if (it < 0) text.indexOf("CHANNEL_ID_KEEPALIVE,\r\n") else it }
        assertTrue("keep-alive channel not created", chan >= 0)
        assertTrue(text.substring(chan, chan + 200).contains("IMPORTANCE_HIGH"))
        val start = text.indexOf("fun notifyKeepAliveOff(")
        val end = text.indexOf("internal fun keepAliveKey(", start)
        assertTrue(start in 0 until end)
        val body = text.substring(start, end)
        assertTrue(body.contains("ongoing = true"))
        assertTrue(body.contains("alertEveryTime = true"))
        assertTrue(body.contains("everyMs = KEEPALIVE_REFLOAT_MS"))
        assertTrue(body.contains("channelId = CHANNEL_ID_KEEPALIVE"))
        assertTrue("must make its channel before posting", body.contains("createChannel(context)"))
    }

    private fun source(path: String): File =
        listOf(File("src/main/java/com/appblocker/$path"), File("app/src/main/java/com/appblocker/$path"))
            .firstOrNull { it.isFile }
            ?: error("cannot find $path from ${File(".").absolutePath}")
}
