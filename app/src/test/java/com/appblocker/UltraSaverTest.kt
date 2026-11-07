package com.appblocker

import com.appblocker.service.UltraSaver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Xiaomi's Ultra battery saver, its ways in closed always — invariant 102, his report of 7 Nov 2026
 * (he relapsed through it). Texts and ids were read off his phone that day over USB.
 */
class UltraSaverTest {

    @Test fun theUltraRowIsRecognisedAndTheOtherModesAreNot() {
        assertTrue(UltraSaver.isUltraRow("Ultra battery saver", "android:id/title"))
        for (other in listOf("Performance mode", "Balanced", "Battery saver", "13h 25m remaining")) {
            assertFalse(other, UltraSaver.isUltraRow(other, "android:id/title"))
        }
        // The same words elsewhere (a summary, a settings search hint) are not the row.
        assertFalse(UltraSaver.isUltraRow("Ultra battery saver", "android:id/summary"))
        assertFalse(UltraSaver.isUltraRow("Ultra battery saver", null))
        assertFalse(UltraSaver.isUltraRow(null, "android:id/title"))
    }

    /** The Edit screen's marks, read on his phone; the normal panel's nodes must never match. */
    @Test fun theEditScreenIsRecognisedAndTheNormalPanelIsNot() {
        assertTrue(UltraSaver.isTileEditMark("miui.systemui.plugin:id/mark", "Add"))
        assertTrue(UltraSaver.isTileEditMark("miui.systemui.plugin:id/mark", "Remove"))
        for ((id, desc) in listOf(
            "miui.systemui.plugin:id/tile_label" to "Add",
            "miui.systemui.plugin:id/qs_tile" to null,
            null to "Remove",
            "miui.systemui.plugin:id/mark" to null,
            "miui.systemui.plugin:id/mark" to "Storage",
            "miui.systemui.plugin:id/main_panel" to "Control center",
        )) assertFalse("$id / $desc read as the Edit screen", UltraSaver.isTileEditMark(id, desc))
    }

    @Test fun oneOpeningIsOneClose() {
        assertTrue(UltraSaver.shouldClose(rowVisible = true, sinceLastCloseMs = UltraSaver.CLOSE_GAP_MS))
        assertFalse(UltraSaver.shouldClose(rowVisible = true, sinceLastCloseMs = 200))
        assertFalse(UltraSaver.shouldClose(rowVisible = false, sinceLastCloseMs = 60_000))
    }

    @Test fun itWatchesTheBatteryAppAndTheControlPanel() {
        assertEquals("com.miui.securitycenter", UltraSaver.PACKAGE)
        assertEquals("com.android.systemui", UltraSaver.SYSTEMUI)
    }

    /**
     * ⚠️ **His choices, 7 Nov 2026: "Block it always" and "Lock the Edit screen".** No setting, no
     * Quick Block gate; both closes only on a VISIBLE node. ⚠️ And never the 1.186 tile-list
     * rewrite again: apps cannot read `sysui_qs_tiles` (measured — the tile put back stayed).
     */
    @Test fun itIsBlockedAlwaysAndOnlyWhenSeen() {
        val dir = listOf(File("src/main/java/com/appblocker/service"), File("app/src/main/java/com/appblocker/service"))
            .first { it.exists() }
        val text = File(dir, "BlockerAccessibilityService.kt").readText()
        fun body(start: String): String {
            val from = text.indexOf(start)
            assertTrue("cannot find $start", from >= 0)
            return text.substring(from, text.indexOf("\n    private ", from + start.length))
        }
        val schedule = body("private fun scheduleUltraScan(")
        val scan = body("private suspend fun scanUltraRow()")
        for ((name, code) in mapOf("scheduleUltraScan" to schedule, "scanUltraRow" to scan)) {
            for (gate in listOf("quickBlockActive()", "SettingsStore.")) {
                assertFalse("$name asks $gate — Ultra battery saver is blocked always", gate in code)
            }
        }
        assertTrue(schedule.contains("UltraSaver.SYSTEMUI"))
        assertTrue(scan.contains("ultraRowVisible()"))
        assertTrue(scan.contains("tileEditorVisible()"))
        assertTrue(scan.contains("performGlobalAction(GLOBAL_ACTION_HOME)"))
        assertTrue("the cover needs its safety net", scan.contains("overlay.remove()"))
        assertTrue(Regex("scheduleUltraScan\\(").findAll(text).count() >= 4)
        assertFalse("apps cannot read sysui_qs_tiles", "sysui_qs_tiles" in text)

        val reader = File(dir, "ScreenText.kt").readText()
        for (fn in listOf("ultraRowVisible()", "tileEditorVisible()")) {
            val code = reader.substringAfter("fun AccessibilityService.$fn").substringBefore("\n}")
            assertTrue("$fn: a hidden node must not count", code.contains("isVisibleToUser"))
        }
    }
}
