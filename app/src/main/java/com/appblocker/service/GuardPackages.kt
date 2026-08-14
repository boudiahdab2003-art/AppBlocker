package com.appblocker.service

/**
 * The packages the off-switch guard is willing to *read*, and the subset of them that hosts an
 * uninstall confirmation.
 *
 * **These lists decide whether a screen is looked at, not whether it is blocked.** Everything
 * dangerous is still decided by the four checks in `handleSettingsGuard` — our own accessibility
 * page, an uninstall confirmation naming us, a device-admin removal naming us, and (during Strict
 * only) our own App-info page. Adding a package here widens what gets read; it does not widen what
 * counts as dangerous. That distinction is the difference between this change and the broad rule
 * that made Strict Mode "block the whole Settings" across v1.104→v1.110.
 *
 * **Why they moved out of the watcher.** They were two vendor lists inside a 2000-line service
 * with no test coverage and no way to acquire any, and one of them silently gated the other (see
 * the subset rule below). Out here they can be asserted, in the same way `AdminScreens` and
 * `AppInfoScreen` were extracted for exactly this reason.
 *
 * **Additive insurance, like `KNOWN_BROWSERS`.** A package name that no phone uses never matches
 * and costs nothing; a real one that is missing is a whole guard that silently never fires. So the
 * list is generous with OEM spellings and the code that reads it stays vendor-agnostic.
 */
internal object GuardPackages {

    /**
     * The uninstall confirmation lives in one of these and nowhere else.
     *
     * **Every entry must also be in [GUARD], or it is dead code** — `handleSettingsGuard` returns
     * early for any package outside [GUARD], so an installer listed only here can never be
     * reached. `GuardPackagesTest` pins that.
     *
     * The AOSP/Google/MIUI three were the whole list, which is how the guard came to be weaker on
     * every phone that isn't the owner's: a Samsung routes its uninstall through Samsung's own
     * installer, `uninstallConfirmation` sees a package it doesn't recognise and answers "not a
     * threat", and the uninstall goes through in the middle of a Strict session. Nothing on screen
     * says so — the invisible under-block from docs/BLOCKING_INVARIANTS.md.
     *
     * Matching here is by package plus "does this screen name us", never by wording, so a new
     * entry cannot over-block some unrelated page: an installer package exists to install and
     * uninstall, and the screen still has to be about AppBlocker.
     */
    val INSTALLERS = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.packageinstaller",              // Xiaomi
        "com.samsung.android.packageinstaller",   // Samsung
        "com.oppo.packageinstaller",              // Oppo / Realme (older ColorOS)
        "com.oplus.packageinstaller",             // Oppo / Realme / OnePlus (newer ColorOS)
        "com.vivo.packageinstaller",              // Vivo
        "com.huawei.packageinstaller",            // Huawei / Honor
    )

    /**
     * Packages that host the escape hatches, so their screens are read at all.
     *
     * System Settings covers almost everything on almost every phone — Samsung, Oppo, Vivo and
     * Huawei all keep Accessibility, device admin and App info inside `com.android.settings`, so
     * the guard already works there. MIUI's security centre is here because Xiaomi genuinely
     * routes app management through it.
     *
     * **The other OEM battery/security centres are deliberately NOT here.** Samsung's Device Care,
     * ColorOS's safecenter and Huawei's systemmanager host sleeping-app lists, not our off-switch:
     * none of them shows our accessibility page, our App-info page (that check needs our version
     * string or an app-info class name) or a device-admin screen. Adding them would buy nothing
     * and would expose `deviceAdminRemoval`'s text fallback — which fires on "deactivate" next to
     * our name — to a page full of app names and toggles. That is a wrong bounce for no gain, and
     * wrong bounces are this guard's entire history. Add one only with a screenshot showing a real
     * off-switch on it.
     */
    val GUARD = setOf(
        "com.android.settings",
        "com.miui.securitycenter", "com.miui.securitycore",
    ) + INSTALLERS
}
