package com.appblocker

import com.appblocker.data.OffNotice
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariant 103 (8 Nov 2026): a long gap in blocking is told to him on the phone the moment it ends,
 * and a long switch-off files its own report then. His 7 Nov battery-empty force stop cost 27 h and
 * nothing said so.
 */
class OffNoticeTest {

    private fun source(path: String): String =
        listOf(File("src/main/java/com/appblocker/$path"), File("app/src/main/java/com/appblocker/$path"))
            .firstOrNull { it.isFile }?.readText()
            ?: error("cannot find $path from ${File(".").absolutePath}")

    private fun body(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("$signature not found", start >= 0)
        val next = src.indexOf("\n    private fun ", start + signature.length)
            .let { if (it < 0) src.indexOf("\n    fun ", start + signature.length) else it }
        return if (next < 0) src.substring(start) else src.substring(start, next)
    }

    @Test fun repairedKillsStaySilent() {
        assertFalse(OffNotice.worthTelling(3 * 60_000L, 3 * 60_000L))
        assertFalse(OffNotice.worthTelling(29 * 60_000L, -1L))
    }

    @Test fun theBatteryEmptyGapIsTold() {
        // 7 Nov 16:53 → 8 Nov 20:11, with a restart in it: the monotonic length is only a floor
        // from the boot, the span is the real time he went unprotected.
        val span = (27 * 60 + 18) * 60_000L
        assertTrue(OffNotice.worthTelling(5 * 60_000L, span))
        assertEquals(span, OffNotice.shownMs(5 * 60_000L, span))
        assertEquals("27 h 18 min", OffNotice.length(span))
    }

    @Test fun lengthsReadPlainly() {
        assertEquals("45 min", OffNotice.length(45 * 60_000L))
        assertEquals("3 h", OffNotice.length(180 * 60_000L))
        assertEquals("0 min", OffNotice.length(-5L))
    }

    @Test fun aLongSwitchOffIsToldAndReportedWhenItEnds() {
        val end = body(source("service/ProtectionWatchdog.kt"), "private fun endSwitchOff(")
        assertTrue("endSwitchOff must decide with OffNotice", "OffNotice.worthTelling(" in end)
        assertTrue("endSwitchOff must post the notice", "ProtectionNotifier.notifyWasOff(" in end)
        assertTrue("endSwitchOff must file its report now", "BugReportSender.reportSwitchOff(" in end)
    }

    @Test fun aLongStoppageIsToldWhenBlockingIsBack() {
        val end = body(source("service/ProtectionWatchdog.kt"), "private fun endOpenOutage(")
        assertTrue("endOpenOutage must post the notice", "ProtectionNotifier.notifyWasOff(" in end)
        assertTrue("only once blocking is back", "state == ProtectionState.OK && OffNotice.worthTelling(" in end)
    }

    @Test fun theSwitchOffReportIsSentNow() {
        val r = body(source("service/BugReportSender.kt"), "fun reportSwitchOff(")
        assertTrue("its key must not collide with an outage's", "\"outageAt\" to \"off" in r)
        assertTrue("sent now, not with the weekly summary", "flush(context)" in r)
    }
}
