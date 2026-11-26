package com.appblocker.service

/**
 * **The home screen's own "Uninstall AppBlocker?" popup** — the screen he left through on
 * 26 Nov 2026 (invariant 110).
 *
 * The guard only ever read Settings and Android's package installers ([GuardPackages]). On a Pixel
 * that is enough: Launcher3 hands an uninstall to the package installer, whose confirmation
 * `uninstallConfirmation` already bounces. HyperOS's launcher (`com.miui.home`) shows its own
 * popup instead, and a package the guard never read cannot be guarded. With "Prevent uninstall"
 * off, long-press ▸ Uninstall ▸ confirm took the app and every block with it.
 *
 * Pure, so it can be tested; the watcher hands it what it read. Three conditions, all needed:
 *  - **a popup**, not the home grid. The grid names AppBlocker (its icon label) and, while any
 *    icon is being dragged, shows an "Uninstall" drop target — so the grid alone must never match.
 *    A popup is a window whose event class says dialog/popup, or one clearly shorter than the
 *    screen (see [isPopup]).
 *  - **naming AppBlocker** — another app's uninstall is none of our business.
 *  - **an uninstall word**, in the languages his phones run.
 *
 * Blank text answers null ("can't tell") and must not bounce, exactly as `aboutUs` does: the
 * popup has only just opened, nothing can have been tapped, and its content events follow.
 */
internal object LauncherUninstall {

    /** Words for removing an app. Lowercase, and run through the same Arabic fold as the screen
     *  text (`WebContentFilter.normalizeArabic`), so the alef-less spelling is the one stored. */
    val UNINSTALL_WORDS = listOf(
        "uninstall", "deinstall", "delete", "remove", "löschen", "entfernen",
        "الغاء التثبيت", "حذف",
    )

    /** A window shorter than this share of the screen is a popup rather than the home screen. */
    const val POPUP_MAX_HEIGHT_FRACTION = 0.85f

    /** How long a dialog/popup class name seen on a launcher event keeps vouching for the window
     *  in front — its own content events carry view classes, which say nothing. */
    const val POPUP_HINT_MS = 5_000L

    /** Whether an event class name says "this window is a dialog or popup". */
    fun classSaysPopup(className: String?): Boolean {
        val c = className?.lowercase() ?: return false
        return "dialog" in c || "popup" in c
    }

    /**
     * @param popupHinted a dialog/popup class name was seen on a launcher event moments ago.
     * @param heightFraction the active window's height over the screen's, or null if unreadable.
     */
    fun isPopup(popupHinted: Boolean, heightFraction: Float?): Boolean =
        popupHinted || (heightFraction != null && heightFraction > 0f &&
            heightFraction < POPUP_MAX_HEIGHT_FRACTION)

    /** @param text the active window's text, lowercased and Arabic-folded. */
    fun matches(text: String, popup: Boolean): Boolean? {
        if (text.isBlank()) return null
        if (!popup) return false
        if ("appblocker" !in text) return false
        return UNINSTALL_WORDS.any { it in text }
    }
}
