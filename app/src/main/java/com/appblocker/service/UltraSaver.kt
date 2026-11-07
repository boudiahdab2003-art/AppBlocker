package com.appblocker.service

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
 * - **Way in 1, Battery ▸ Current mode** — a popup list in `com.miui.securitycenter`
 *   (`PowerMainActivity`) whose rows are `android:id/title` texts: Performance mode, Balanced,
 *   Battery saver, **Ultra battery saver**. Tapping the Ultra row switches it on with NO confirm.
 *   The popup is the focused window, so it is `rootInActiveWindow`. The list is closed (HOME) and
 *   covered the moment it shows the Ultra row. Verified on his phone, 1.186: "closed".
 * - **Way in 2, the control-panel tile** — one tap and Ultra is on, too fast for any cover. The tile
 *   was taken out of his control panel by hand over USB (`secure sysui_qs_tiles`). ⚠️ AppBlocker
 *   CANNOT keep it out by itself: 1.186 tried (read the list, write it back without the tile, on
 *   every change) and on his phone the tile put back stayed — from Android 12, apps may not read
 *   hidden settings like `sysui_qs_tiles`, whatever permission they hold. So 1.187 locks the
 *   **control panel's Edit screen** instead (his choice, "Lock the Edit screen"): in edit mode
 *   every tile carries a `miui.systemui.plugin:id/mark` whose description is "Add" or "Remove",
 *   which the normal panel never shows. Shade dismissed + HOME + cover.
 *
 * ⚠️ The automatic low-battery **hibernation** cannot be stopped or survived by any app; what
 * still filters then is the Private DNS family filter, which is a phone setting, not an app.
 */
internal object UltraSaver {

    /** The counter key and the log's why. */
    const val KEY = "ultra-saver"

    /** Where the Current-mode list lives. */
    const val PACKAGE = "com.miui.securitycenter"

    /** Where the control panel and its Edit screen live. */
    const val SYSTEMUI = "com.android.systemui"

    /** The Ultra row's title, as read on his phone (English UI). */
    private val ROW_TITLES = setOf("ultra battery saver")

    /** The descriptions of a tile's mark in edit mode, as read on his phone (English UI). */
    private val EDIT_MARKS = setOf("add", "remove")

    /** Gap kept between two closes, so one opening is one HOME however many events follow. */
    const val CLOSE_GAP_MS = 1_500L

    /** Whether a node's [text] in a node with [viewId] is the Ultra row of the mode list. */
    fun isUltraRow(text: CharSequence?, viewId: String?): Boolean =
        viewId == "android:id/title" &&
            text != null && text.toString().trim().lowercase() in ROW_TITLES

    /** Whether a node is a tile's Add/Remove mark — present only on the control panel's Edit screen. */
    fun isTileEditMark(viewId: String?, description: CharSequence?): Boolean =
        viewId != null && viewId.endsWith(":id/mark") &&
            description != null && description.toString().trim().lowercase() in EDIT_MARKS

    /** Whether to close: the row is on screen, and no close went out in the last [CLOSE_GAP_MS]. */
    fun shouldClose(rowVisible: Boolean, sinceLastCloseMs: Long): Boolean =
        rowVisible && sinceLastCloseMs >= CLOSE_GAP_MS
}
