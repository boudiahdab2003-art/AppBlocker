package com.appblocker.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * **Xiaomi's Ultra battery saver** — its ways in are closed, always. His report, 7 Nov 2026:
 * *"when i turn the phone to ultra battery saving or the phone enter hibernation mode … the
 * blocking turn off so i abused it and relapsed"*; his choice "Block it always". Invariant 102.
 *
 * ## Why the way IN has to be closed (measured on his phone over USB, 7 Nov 2026, HyperOS 3)
 *
 * - Switching Ultra on runs `SuperPowerClean.cleanAllMemory`, which **force-stops AppBlocker**
 *   (`Force stopping com.appblocker … LockScreenClean`) about 1.5 s after the tap — even with
 *   AppBlocker on Ultra's own app list. A force stop also drops the Accessibility switch, and
 *   nothing can start a force-stopped app again until he opens it (5 Nov: off for two days).
 *   So nothing AppBlocker does *inside* Ultra can work; the tap itself must not happen.
 * - **Way in 1, the control-panel tile** — one tap and Ultra is on, too fast for a cover. The tile
 *   is `custom(com.miui.securitycenter/com.miui.superpower.notification.SuperPowerTileService)` in
 *   the secure setting `sysui_qs_tiles`; removing it there removed it from his control panel at
 *   once (measured). `power_supersave_tile_enabled=0` did NOT hide it. AppBlocker takes it out with
 *   the computer-granted `WRITE_SECURE_SETTINGS` ([Grayscale], [SelfToggle]) and again whenever
 *   the setting changes, so adding it back with Edit undoes itself.
 * - **Way in 2, Battery ▸ Current mode** — a popup list in `com.miui.securitycenter`
 *   (`PowerMainActivity`) whose rows are `android:id/title` texts: Performance mode, Balanced,
 *   Battery saver, **Ultra battery saver**. Tapping the Ultra row switches it on with NO confirm.
 *   The popup is the focused window, so it is `rootInActiveWindow`. The list is closed (BACK) and
 *   covered the moment it shows the Ultra row; Battery saver stays on its own control-panel tile.
 *
 * ⚠️ The automatic low-battery **hibernation** cannot be stopped or survived by any app; what
 * still filters then is the Private DNS family filter, which is a phone setting, not an app.
 */
internal object UltraSaver {

    /** The counter key and the log's why. */
    const val KEY = "ultra-saver"

    /** Where the Current-mode list lives. */
    const val PACKAGE = "com.miui.securitycenter"

    /** The Ultra row's title, as read on his phone (English UI). */
    private val ROW_TITLES = setOf("ultra battery saver")

    /** Gap kept between two closes, so one list opening is one BACK however many events follow. */
    const val CLOSE_GAP_MS = 1_500L

    /** The control-panel list of tiles — a secure setting, per Android user. */
    const val TILES_SETTING = "sysui_qs_tiles"

    /** The Ultra tile's entry in [TILES_SETTING]. */
    const val TILE = "custom(com.miui.securitycenter/com.miui.superpower.notification.SuperPowerTileService)"

    /** Whether a node's [text] in a node with [viewId] is the Ultra row of the mode list. */
    fun isUltraRow(text: CharSequence?, viewId: String?): Boolean =
        viewId == "android:id/title" &&
            text != null && text.toString().trim().lowercase() in ROW_TITLES

    /** Whether to close: the row is on screen, and no close went out in the last [CLOSE_GAP_MS]. */
    fun shouldClose(rowVisible: Boolean, sinceLastCloseMs: Long): Boolean =
        rowVisible && sinceLastCloseMs >= CLOSE_GAP_MS

    /**
     * [tiles] without the Ultra tile, or null when there is nothing to change (no setting, or the
     * tile is not in it). Only the exact entry goes; every other tile and its order stay.
     */
    fun withoutUltraTile(tiles: String?): String? {
        if (tiles.isNullOrBlank()) return null
        val parts = tiles.split(',')
        if (parts.none { it.trim() == TILE }) return null
        return parts.filter { it.trim() != TILE }.joinToString(",")
    }

    /**
     * Takes the Ultra tile out of the control panel. True when it wrote, false when there was
     * nothing to do or it may not (no `WRITE_SECURE_SETTINGS`: the Play build, or a reinstall
     * that lost the grant). Never throws.
     */
    fun removeTile(context: Context): Boolean = runCatching {
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        val cr = context.contentResolver
        val next = withoutUltraTile(Settings.Secure.getString(cr, TILES_SETTING)) ?: return false
        Settings.Secure.putString(cr, TILES_SETTING, next)
    }.getOrDefault(false)
}
