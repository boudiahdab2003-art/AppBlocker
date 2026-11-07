package com.appblocker

import com.appblocker.service.UltraSaver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Xiaomi's Ultra battery saver, its ways in closed always — invariant 102, his report of 7 Nov 2026
 * (he relapsed through it). Texts, ids and the tile list were read off his phone that day over USB.
 */
class UltraSaverTest {

    /** His real `sysui_qs_tiles`, 7 Nov 2026, main space. */
    private val hisTiles = "wifi,cell,bt,airplane,mute,flashlight,screenshot,batterysaver,rotation,settings," +
        "custom(com.google.android.gms/.nearby.sharing.SharingTileService),scanner,wallet," +
        "custom(com.miui.securitycenter/com.miui.powercenter.powersaver.PerformanceModeTileService)," +
        "custom(com.miui.screenrecorder/.service.QuickService),nfc," +
        "custom(com.miui.securitycenter/com.miui.superpower.notification.SuperPowerTileService),gps,edit"

    @Test fun theUltraTileIsTakenOutAndNothingElse() {
        val after = UltraSaver.withoutUltraTile(hisTiles)!!
        assertFalse(after.contains("SuperPowerTileService"))
        // Every other tile stays, in order — Battery saver and the performance-mode tile included.
        assertEquals(hisTiles.split(',').filter { "SuperPowerTileService" !in it }, after.split(','))
        assertTrue(after.contains("batterysaver"))
        assertTrue(after.contains("PerformanceModeTileService"))
    }

    /** No tile to remove = no write, so the setting observer cannot loop on our own write. */
    @Test fun nothingToDoMeansNoWrite() {
        assertNull(UltraSaver.withoutUltraTile(UltraSaver.withoutUltraTile(hisTiles)))
        assertNull(UltraSaver.withoutUltraTile(null))
        assertNull(UltraSaver.withoutUltraTile(""))
        assertEquals("wifi", UltraSaver.withoutUltraTile("${UltraSaver.TILE},wifi"))
    }

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

    @Test fun oneOpeningIsOneClose() {
        assertTrue(UltraSaver.shouldClose(rowVisible = true, sinceLastCloseMs = UltraSaver.CLOSE_GAP_MS))
        assertFalse(UltraSaver.shouldClose(rowVisible = true, sinceLastCloseMs = 200))
        assertFalse(UltraSaver.shouldClose(rowVisible = false, sinceLastCloseMs = 60_000))
    }

    @Test fun itWatchesTheBatteryApp() {
        assertEquals("com.miui.securitycenter", UltraSaver.PACKAGE)
        assertEquals("sysui_qs_tiles", UltraSaver.TILES_SETTING)
    }

    /**
     * ⚠️ **His choice, 7 Nov 2026: "Block it always".** No setting, no Quick Block gate; the list
     * closes only on a VISIBLE row; the tile is removed at bind AND on every change of the list.
     */
    @Test fun itIsBlockedAlwaysAndTheTileStaysOut() {
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
        assertTrue(scan.contains("ultraRowVisible()"))
        assertTrue(scan.contains("performGlobalAction(GLOBAL_ACTION_HOME)"))
        assertTrue("the cover needs its safety net", scan.contains("overlay.remove()"))
        assertTrue(Regex("scheduleUltraScan\\(").findAll(text).count() >= 4)
        // The tile: removed at bind, and the observer re-removes it on every change.
        assertTrue(text.contains("registerContentObserver("))
        assertTrue(text.contains("UltraSaver.TILES_SETTING"))
        assertTrue(Regex("UltraSaver\\.removeTile\\(").findAll(text).count() >= 2)
        assertTrue(text.contains("unregisterContentObserver(ultraTileObserver)"))

        val reader = File(dir, "ScreenText.kt").readText()
            .substringAfter("fun AccessibilityService.ultraRowVisible()").substringBefore("\n}")
        assertTrue("a hidden row must not count", reader.contains("isVisibleToUser"))
    }
}
