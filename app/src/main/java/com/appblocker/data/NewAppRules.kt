package com.appblocker.data

/**
 * Whether a newly installed app should be blocked automatically.
 *
 * **The rule exists because the owner's plan is to run several blockers at once** (26 Aug 2026):
 * *"i was thinking about installing many porn blockers so if ours failed the others would
 * compensate"*. With "Add newly installed apps" on, AppBlocker blocked every new launchable app
 * — so each backup layer would have been covered by a block screen the moment it was installed,
 * and the defence he was building would have been shot down by the app he was building it around.
 *
 * The signal is structural rather than a list of names: a protection app declares an
 * accessibility service, a device-admin receiver or a VPN service, because those are the only
 * three ways an Android app can enforce anything about other apps. That catches blockers nobody
 * here has heard of, which a name list never would — see [com.appblocker.service.isProtectiveApp].
 *
 * **Only the automatic path changes.** Blocking such an app by hand still works: this decides
 * what happens without being asked, and being asked is different.
 *
 * A new *browser* is the opposite case and stays auto-blocked. That one really is the escape
 * hatch — installing a fresh browser is the cheapest way around a block list — so where the two
 * signals disagree, browser wins.
 */
internal object NewAppRules {

    fun shouldAutoBlock(
        addNewApps: Boolean,
        allowlistMode: Boolean,
        launchable: Boolean,
        isProtector: Boolean,
        isBrowser: Boolean,
    ): Boolean {
        // Allowlist mode has nothing to add to: a new app is already blocked by not being on the
        // list. Non-launchable packages are background components, never something the user opens.
        if (!addNewApps || allowlistMode || !launchable) return false
        if (isBrowser) return true
        return !isProtector
    }
}
