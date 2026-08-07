package com.appblocker.service

/**
 * Recognises the system **App info** page — the Settings screen that carries Force stop.
 *
 * **Why this exists.** Force-stopping AppBlocker kills the accessibility service, and the owner
 * reached that button during an active Strict session: "no protection anymore". The page was left
 * unguarded on purpose (see handleSettingsGuard) because App info is a *hub* — battery,
 * permissions, storage and uninstall all live on it — and guarding it for everyone cost him the
 * rest. The trade assumed force-stop was survivable because the service comes back by itself.
 *
 * So this is narrow by design and used in exactly one place: with `aboutUs(text) == true`, during
 * a Strict session only. Every other app's App info page, and every page outside a session, is
 * untouched. That narrowness is the point — the broad version of this guard is what made Strict
 * "block the whole Settings" across v1.104→v1.110.
 *
 * **It fails open.** An OEM that hides the version and uses an unrecognised class name gets
 * "no", and the page behaves exactly as it does today. That direction is deliberate: this guard's
 * entire history is over-blocking, and a missed bounce costs one hole while a wrong bounce costs
 * the owner his phone's Settings in the middle of a session he cannot end.
 */
object AppInfoScreen {

    /**
     * Activity-class fragments for the App-info screen. Class names are never translated, so they
     * mean the same thing in every language — but each OEM picks its own, and some builds route
     * the page through a shared container (AOSP's `SpaActivity`) that cannot be matched without
     * catching half of Settings with it. Those are deliberately absent, which is why the version
     * string below exists as an independent second signal.
     */
    val CLASS_HINTS = listOf("installedappdetails", "appinfodashboard")

    /*
     * Measured on both builds this app actually meets, because they differ and only one of them
     * can be tested here:
     *
     *  - HyperOS (the owner's phone, from his screenshot): the page prints the app name AND
     *    "Version: 1.119". The version signal fires.
     *  - AOSP 14 (the test emulator): the page is com.android.settings.spa.SpaActivity — a
     *    container shared with much of Settings — and prints the app name but NO version. Neither
     *    signal fires, so the guard stays open there, which is the fail-open direction on purpose.
     *
     * That asymmetry is why the emulator can prove this never over-blocks but cannot prove it
     * bounces: the positive case only exists on the phone. Do not "fix" the AOSP case by matching
     * SpaActivity or the words "force stop" / "uninstall" — the first catches half of Settings,
     * and the second is a translated string, which is the fragility v1.104-v1.110 spent seven
     * releases removing.
     */

    /**
     * Whether [className] + [text] read like an App-info page showing [versionName].
     *
     * Two independent signals, so one OEM's layout cannot defeat both: the activity class, or the
     * app's own version string printed on the page ("Version: 1.119"). The version is ours and
     * comes from BuildConfig, so it stays correct across releases with nothing to remember.
     *
     * Says nothing about *whose* page it is — the caller pairs this with the guard's existing
     * `aboutUs`, which owns the rule that an unreadable screen answers "can't tell" and must not
     * bounce. Keeping that rule in one place is why it isn't repeated here.
     *
     * [text] is expected lowercased, as `guardScreenText()` returns it.
     */
    fun looksLikeAppInfo(className: String, text: String, versionName: String): Boolean {
        if (text.isBlank()) return false
        val cn = className.lowercase()
        if (CLASS_HINTS.any { cn.contains(it) }) return true
        return versionName.isNotBlank() && text.contains(versionName)
    }
}
