package com.appblocker

import com.appblocker.data.GuardedDeadline
import com.appblocker.data.OffSwitchGuard
import com.appblocker.data.UninstallUnlock
import com.appblocker.service.KeepAliveSwitches
import com.appblocker.service.KeepAliveSwitches.State
import com.appblocker.service.LauncherUninstall
import com.appblocker.service.ProtectionNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **Invariant 110** — 26 Nov 2026, he relapsed by uninstalling AppBlocker: Profile ▸ Prevent
 * uninstall → typed paragraph → device admin off on the spot → long-press the home-screen icon ▸
 * Uninstall. His picks: the wait to switch it off is longer than a day (48 h); the launcher's own
 * uninstall popup is covered always.
 */
class UninstallGuardTest {

    // ---- the wait --------------------------------------------------------------------------------

    @Test
    fun `switching prevent uninstall off waits longer than a day, then opens briefly`() {
        assertTrue(UninstallUnlock.UNLOCK_DELAY_MS > 24 * 60 * 60_000L)
        assertEquals(48 * 60 * 60_000L, UninstallUnlock.UNLOCK_DELAY_MS)
        assertEquals(30 * 60_000L, UninstallUnlock.UNLOCK_WINDOW_MS)
        // Longer than the off-switch guard's, or the guard would be the cheaper way to the same place.
        assertTrue(UninstallUnlock.UNLOCK_DELAY_MS > OffSwitchGuard.UNLOCK_DELAY_MS)
        assertEquals("48 hours", UninstallUnlock.DELAY_LABEL)
    }

    @Test
    fun `only the served window stands the uninstall arms down`() {
        assertTrue(UninstallUnlock.armed(OffSwitchGuard.Phase.GUARDED))
        assertTrue(UninstallUnlock.armed(OffSwitchGuard.Phase.WAITING))
        assertFalse(UninstallUnlock.armed(OffSwitchGuard.Phase.OPEN))
    }

    @Test
    fun `winding the clock forward does not serve the two days`() {
        val d = UninstallUnlock.UNLOCK_DELAY_MS
        val w = UninstallUnlock.UNLOCK_WINDOW_MS
        val wall0 = 1_800_000_000_000L
        val req = GuardedDeadline(
            realtimeStart = 1_000L, realtimeEnd = 1_000L + d,
            wallStart = wall0, wallEnd = wall0 + d, bootCount = 7,
        )
        // One hour in by the monotonic clock, the wall clock pushed four days ahead.
        val nowRt = 1_000L + 60 * 60_000L
        val nowWall = wall0 + 4 * d
        val untilUnlock = req.remainingAt(7, nowRt, nowWall)
        val untilExpiry = req.remainingAt(7, nowRt, nowWall, extraMs = w)
        assertEquals(d - 60 * 60_000L, untilUnlock)
        assertEquals(
            OffSwitchGuard.Phase.WAITING,
            OffSwitchGuard.phase(hasRequest = true, untilUnlock = untilUnlock, untilExpiry = untilExpiry),
        )
    }

    @Test
    fun `strict refuses starting the wait and lowering, but never switching it on`() {
        assertEquals(OffSwitchGuard.Tap.TURN_ON, OffSwitchGuard.tap(false, OffSwitchGuard.Phase.GUARDED, true))
        assertEquals(OffSwitchGuard.Tap.REFUSED_STRICT, OffSwitchGuard.tap(true, OffSwitchGuard.Phase.GUARDED, true))
        assertEquals(OffSwitchGuard.Tap.REFUSED_STRICT, OffSwitchGuard.tap(true, OffSwitchGuard.Phase.OPEN, true))
        assertEquals(OffSwitchGuard.Tap.START_WAIT, OffSwitchGuard.tap(true, OffSwitchGuard.Phase.GUARDED, false))
        assertEquals(OffSwitchGuard.Tap.NOTHING, OffSwitchGuard.tap(true, OffSwitchGuard.Phase.WAITING, false))
        assertEquals(OffSwitchGuard.Tap.LOWER, OffSwitchGuard.tap(true, OffSwitchGuard.Phase.OPEN, false))
    }

    // ---- the launcher popup ----------------------------------------------------------------------

    @Test
    fun `the launcher's uninstall popup about us is covered`() {
        assertEquals(true, LauncherUninstall.matches("uninstall \"appblocker\"? cancel uninstall", popup = true))
        assertEquals(true, LauncherUninstall.matches("appblocker deinstallieren? abbrechen", popup = true))
        assertEquals(true, LauncherUninstall.matches("delete appblocker? cancel delete", popup = true))
    }

    @Test
    fun `the home grid never matches, though it names us and can show an uninstall target`() {
        val grid = "phone messages chrome appblocker youtube settings uninstall"
        assertEquals(false, LauncherUninstall.matches(grid, popup = false))
        assertFalse(LauncherUninstall.isPopup(popupHinted = false, heightFraction = 1.0f))
        assertFalse(LauncherUninstall.isPopup(popupHinted = false, heightFraction = 0.95f))
        assertFalse(LauncherUninstall.isPopup(popupHinted = false, heightFraction = null))
    }

    @Test
    fun `another app's popup and an unreadable one are left alone`() {
        assertEquals(false, LauncherUninstall.matches("uninstall \"instagram\"? cancel uninstall", popup = true))
        assertEquals(false, LauncherUninstall.matches("appblocker app info widgets", popup = true))
        assertNull(LauncherUninstall.matches("   ", popup = true))
    }

    @Test
    fun `a popup is a dialog class or a short window`() {
        assertTrue(LauncherUninstall.classSaysPopup("miuix.appcompat.app.AlertDialog"))
        assertTrue(LauncherUninstall.classSaysPopup("android.widget.PopupWindow\$PopupDecorView"))
        assertFalse(LauncherUninstall.classSaysPopup("com.miui.home.launcher.Launcher"))
        assertFalse(LauncherUninstall.classSaysPopup(null))
        assertTrue(LauncherUninstall.isPopup(popupHinted = true, heightFraction = 1.0f))
        assertTrue(LauncherUninstall.isPopup(popupHinted = false, heightFraction = 0.4f))
    }

    // ---- the warning and the report ------------------------------------------------------------

    @Test
    fun `prevent uninstall off raises the standing warning, unknown never does`() {
        assertTrue(KeepAliveSwitches.Reading(State.ON, State.ON, State.OFF).anyOff)
        assertFalse(KeepAliveSwitches.Reading(State.ON, State.ON, State.ON).anyOff)
        assertFalse(KeepAliveSwitches.Reading(State.ON, State.ON, State.UNKNOWN).anyOff)
        assertTrue(
            ProtectionNotifier.keepAliveKey(false, false, adminOff = true) !=
                ProtectionNotifier.keepAliveKey(false, false, adminOff = false),
        )
        val text = code(source("service/KeepAliveSwitches.kt").readText())
        assertTrue("check() must hand the admin state on", "r.adminOff" in text)
        assertTrue("read() must read device admin", "admin(context)" in text)
    }

    @Test
    fun `reports carry prevent uninstall and its wait`() {
        val sender = source("service/BugReportSender.kt").readText()
        assertTrue(sender.contains("field(\"admin\")"))
        assertTrue(sender.contains("field(\"uninstallUnlock\")"))
        assertTrue(code(source("service/HealthReader.kt").readText()).contains("admin = when"))
    }

    // ---- shape -----------------------------------------------------------------------------------

    /** The 26 Nov hole was a straight call from the gate's confirm. One door, behind the wait. */
    @Test
    fun `disableDeviceAdmin has one caller, and it is the served window`() {
        val callers = sourceTree()
            .flatMap { f -> code(f.readText()).lines().map { f.name to it } }
            .filter { (_, line) -> "disableDeviceAdmin(" in line && "fun disableDeviceAdmin(" !in line }
        assertEquals("disableDeviceAdmin must have exactly one caller: $callers", 1, callers.size)
        assertEquals("ProfileScreen.kt", callers.single().first)

        val profile = code(source("ui/ProfileScreen.kt").readText())
        val whenAt = profile.indexOf("when (uninstallTap) {")
        assertTrue("the Prevent uninstall row no longer switches on its tap state", whenAt >= 0)
        val body = braceBody(profile, profile.indexOf('{', whenAt))
        val lower = body.substringAfter("OffSwitchGuard.Tap.LOWER ->", "")
            .substringBefore("OffSwitchGuard.Tap.START_WAIT ->")
        assertTrue("disableDeviceAdmin must sit in the LOWER branch", "disableDeviceAdmin(context)" in lower)
        val start = body.substringAfter("OffSwitchGuard.Tap.START_WAIT ->", "")
        assertTrue("the gate must start the two-day wait", "setUninstallUnlockRequest(" in start)
        assertFalse("the gate must not switch protection off", "disableDeviceAdmin" in start)
    }

    /** Two clocks: the uninstall arms never come down with the two-hour guard. */
    @Test
    fun `the uninstall arms answer to the two-day wait only`() {
        val text = code(source("service/BlockerAccessibilityService.kt").readText())
        val guard = text.substringAfter("private fun handleSettingsGuard(", "")
            .substringBefore("private fun ")
        assertTrue("handleSettingsGuard is gone; this check is reading nothing", guard.isNotEmpty())
        val decl = guard.lines().first { "val uninstallArmed" in it }
        assertTrue(decl, "UninstallUnlock.armed(" in decl)
        assertFalse("the uninstall arms must not use the 2-hour guard: $decl", "OffSwitchGuard" in decl)
        for (why in listOf("BlockWhy.GUARD_UNINSTALL", "BlockWhy.GUARD_ADMIN")) {
            val arms = guard.lines().filter { why in it && "->" in it }
            assertTrue("no $why arm", arms.isNotEmpty())
            for (arm in arms) {
                assertTrue("$why must be gated by uninstallArmed: $arm", arm.trim().startsWith("uninstallArmed &&"))
            }
        }
        assertTrue(
            "a launcher must reach its own check before the package return",
            "handleLauncherUninstall(" in guard.substringBefore("val offSwitchArmed"),
        )
        val launcher = text.substringAfter("private fun handleLauncherUninstall(", "")
            .substringBefore("private fun ")
        assertTrue("UninstallUnlock.armed(" in launcher)
        assertFalse("the launcher popup is guarded always, not only in Strict", "OffSwitchGuard" in launcher)
        assertTrue("BlockWhy.GUARD_LAUNCHER" in launcher)
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun code(text: String): String = text.lines()
        .filterNot { line ->
            val t = line.trim()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/**")
        }
        .joinToString("\n")

    private fun braceBody(text: String, open: Int): String {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open + 1, i)
            }
        }
        return text.substring(open + 1)
    }

    private fun source(path: String): File =
        listOf(File("src/main/java/com/appblocker/$path"), File("app/src/main/java/com/appblocker/$path"))
            .firstOrNull { it.isFile }
            ?: error("cannot find $path from ${File(".").absolutePath}")

    private fun sourceTree(): List<File> =
        listOf(File("src/main/java/com/appblocker"), File("app/src/main/java/com/appblocker"))
            .firstOrNull { it.isDirectory }
            ?.walkTopDown()?.filter { it.isFile && it.extension == "kt" }?.toList()
            ?: error("cannot find the source tree from ${File(".").absolutePath}")
}
