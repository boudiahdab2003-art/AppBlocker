package com.appblocker.data

import android.os.Build

/**
 * What this particular phone's manufacturer does to background apps, and how to stop it.
 *
 * **Why this exists.** Every keep-alive instruction in the app used to be addressed to one phone:
 * "On Xiaomi/MIUI, allow auto-start…", and an Auto-start button that deep-linked into MIUI's
 * security centre and nowhere else. That is the owner's phone. On a Samsung — where **"Put unused
 * apps to sleep" is on by default** and is the single most likely thing to kill blocking — the app
 * said nothing useful and the button opened a generic page.
 *
 * The advice is per-brand because the *destination* is per-brand; nothing about blocking itself
 * changes. Getting the brand wrong therefore costs a paragraph of irrelevant instructions, never a
 * blocking decision — which is why matching on [Build.MANUFACTURER] is acceptable here and is not
 * acceptable in the watcher (docs/BLOCKING_INVARIANTS.md, invariant 12).
 *
 * **The written steps are the deliverable; [VendorAdvice.deepLinks] is a bonus.** OEMs rename these
 * activities between versions, and Android 11+ package visibility can make an explicit component
 * unreachable — so every card shows the steps in words whether or not the button lands anywhere.
 * An unrecognised brand gets [GENERIC], which names the common brands and deep-links nowhere:
 * saying "look for something like this" beats sending someone to a page that does not exist.
 *
 * Pure data with one Android call (the default argument), so the mapping is unit-testable.
 */
data class VendorAdvice(
    /** Display name of the brand, or "" for the generic entry. */
    val brand: String,
    /** Title for the keep-alive setup card — the OEM's own name for the thing being changed. */
    val keepAliveLabel: String,
    /** The steps, in the user's words. Shown on the card whether or not a deep link exists. */
    val keepAliveDesc: String,
    /** Extra housekeeping that isn't one settings page (locking in Recents, battery profiles). */
    val extraTips: String,
    /**
     * `package` to `class` candidates for the keep-alive page, best first. Tried in order, each
     * one falling through to the next and finally to the app's own details page.
     */
    val deepLinks: List<Pair<String, String>> = emptyList(),
    /**
     * This brand's app-cloning feature, named, or null if it has none worth warning about.
     *
     * A cloned app runs as a **different Android user**, and an accessibility service receives no
     * events from another user — so a blocked app cloned into Secure Folder, Second Space or App
     * Clone is genuinely invisible to the blocker. It cannot be fixed from inside the app, so the
     * app says so instead: under-blocking the user doesn't know about is the failure mode this
     * project keeps paying for.
     */
    val clonedAppsFeature: String? = null,
    /**
     * What to do about this brand's *space switching* killing the blocker — or null if the brand
     * has no such feature worth warning about.
     *
     * **Not the same thing as [clonedAppsFeature], though both name Second Space.** That one is
     * about an app *inside* the other space being invisible to us. This one is about the ordinary
     * space switch stopping every app in *this* space, ours included, and HyperOS not always
     * rebinding the accessibility service on the way back — which leaves Android's toggle reading
     * "on" over a watcher that is gone. Two different failures of one feature; merging them would
     * produce advice that is half wrong for whichever one the reader has.
     *
     * The text ends by admitting the steps only make it rarer. They cannot prevent it: a space
     * switch stops the whole space, and no in-app setting outranks that. Promising otherwise
     * would be the kind of claim this project has learnt costs a release to walk back.
     */
    val spacesWarning: String? = null,
)

object DeviceVendor {

    private val XIAOMI = VendorAdvice(
        brand = "Xiaomi",
        keepAliveLabel = "Auto-start",
        keepAliveDesc = "Xiaomi phones stop background apps after a while. Allow auto-start for " +
            "AppBlocker so blocking survives a reboot or a memory cleanup.",
        extraTips = "On Xiaomi/MIUI: also lock AppBlocker in Recents (swipe down on the card) and " +
            "set Battery saver to “No restrictions” so it isn't killed. And switch on " +
            "“Floating notifications” for AppBlocker — MIUI keeps that as its own permission, " +
            "off by default, and while it's off the “blocking has stopped” alert can't pop up " +
            "no matter what the app does.",
        deepLinks = listOf(
            "com.miui.securitycenter" to
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
        ),
        clonedAppsFeature = "Second Space or Dual Apps",
        spacesWarning = "Switching to Second Space and back shuts AppBlocker down in this " +
            "space, and Android's switch still says it's on. Lock AppBlocker in Recents (swipe " +
            "down on its card), allow Auto-start, and set Battery saver to “No restrictions”. " +
            "That makes it happen less often — it can't stop it completely, because switching " +
            "space stops every app in this space. That's what the alert is for.",
    )

    private val SAMSUNG = VendorAdvice(
        brand = "Samsung",
        keepAliveLabel = "Stop Samsung sleeping the app",
        keepAliveDesc = "Samsung puts unused apps to sleep, which switches blocking off without " +
            "telling you. Open Battery ▸ Background usage limits, turn off “Put unused apps to " +
            "sleep”, and add AppBlocker to “Never sleeping apps”.",
        extraTips = "On Samsung: also check AppBlocker isn't listed under Sleeping apps or Deep " +
            "sleeping apps — an app in either list is not running, so nothing gets blocked.",
        deepLinks = listOf(
            // Device Care ▸ Battery. One UI has moved this class at least once, hence two.
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
            "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
        ),
        clonedAppsFeature = "Secure Folder or Dual Messenger",
        spacesWarning = "Coming back from Secure Folder, or switching between users, can stop " +
            "AppBlocker while Android's switch still says it's on. Add AppBlocker to “Never " +
            "sleeping apps” and lock it in Recents to make that rarer.",
    )

    private val HUAWEI = VendorAdvice(
        brand = "Huawei",
        keepAliveLabel = "Startup manager",
        keepAliveDesc = "Huawei phones close background apps aggressively. In Phone Manager ▸ " +
            "Startup, switch AppBlocker to Manage manually and turn all three switches on.",
        extraTips = "On Huawei/Honor: also set Battery ▸ App launch for AppBlocker to manual, or " +
            "the phone will close it again after the next restart.",
        deepLinks = listOf(
            "com.huawei.systemmanager" to
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to
                "com.huawei.systemmanager.optimize.process.ProtectActivity",
        ),
        clonedAppsFeature = "App Twin or PrivateSpace",
    )

    private val OPPO = VendorAdvice(
        brand = "Oppo",
        keepAliveLabel = "Allow background running",
        keepAliveDesc = "ColorOS phones freeze background apps. In Battery ▸ App battery " +
            "management, allow AppBlocker to run in the background and disable any sleep or " +
            "optimisation for it.",
        extraTips = "On Oppo/Realme/OnePlus: also lock AppBlocker in Recents so a swipe-away " +
            "cleanup doesn't take blocking with it.",
        deepLinks = listOf(
            "com.coloros.safecenter" to
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oplus.battery" to "com.oplus.powermanager.fuelgaue.PowerConsumptionActivity",
        ),
        clonedAppsFeature = "App Clone",
    )

    private val VIVO = VendorAdvice(
        brand = "Vivo",
        keepAliveLabel = "Background app manager",
        keepAliveDesc = "Vivo phones close background apps to save power. In Battery ▸ " +
            "Background power consumption management, allow AppBlocker to run in the background.",
        extraTips = "On Vivo: also allow AppBlocker under Autostart, or blocking won't come back " +
            "after a restart.",
        deepLinks = listOf(
            "com.vivo.permissionmanager" to
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        ),
        clonedAppsFeature = "App Clone",
    )

    /**
     * The fallback, and it deliberately promises nothing. No deep link (there is no page to guess
     * at) and no cloned-apps warning (naming a feature the phone may not have reads as a bug).
     * Stock Android does not kill accessibility services, so on a Pixel this card is genuinely
     * just belt-and-braces — which is what the wording says.
     */
    private val GENERIC = VendorAdvice(
        brand = "",
        keepAliveLabel = "Keep AppBlocker running",
        keepAliveDesc = "Some phones close background apps to save battery, which switches " +
            "blocking off. In your phone's battery settings, allow AppBlocker to run in the " +
            "background and exclude it from any power saving or app sleeping.",
        extraTips = "Brands like Xiaomi, Samsung, Huawei, Oppo and Vivo each hide this in a " +
            "different place — look for auto-start, sleeping apps, or background usage limits.",
    )

    /** Every entry, so a caller can ask about the whole table rather than one phone's row. */
    private val ALL = listOf(XIAOMI, SAMSUNG, HUAWEI, OPPO, VIVO, GENERIC)

    /**
     * Every package any [VendorAdvice.deepLinks] points at.
     *
     * **These must also be declared in `<queries>` in AndroidManifest.xml**, or the deep link is
     * filtered on Android 11+ and the button silently opens the app-details page instead of the
     * page its own label names. That was true of the MIUI link for this app's entire life.
     *
     * `DeviceVendorTest` pins that every deep link appears here. It cannot pin the manifest — a
     * JVM test cannot read it — so this is a checklist with a test attached, not a proof. **Adding
     * a brand means editing two files**, and the manifest is the one that is easy to forget.
     */
    val DECLARED_KEEP_ALIVE_PACKAGES: Set<String> =
        ALL.flatMap { advice -> advice.deepLinks.map { it.first } }.toSet()

    /**
     * The advice for [manufacturer], defaulting to this phone's.
     *
     * Sub-brands are folded into their parent (Redmi/Poco → Xiaomi, Honor → Huawei,
     * Realme/OnePlus → Oppo) because they ship the same battery manager under the same package
     * name. Null or unrecognised gives [GENERIC] — the app never guesses a brand.
     */
    fun advice(manufacturer: String? = Build.MANUFACTURER): VendorAdvice {
        val m = manufacturer?.lowercase().orEmpty()
        return when {
            m.isBlank() -> GENERIC
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> XIAOMI
            m.contains("samsung") -> SAMSUNG
            m.contains("huawei") || m.contains("honor") -> HUAWEI
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") ||
                m.contains("oplus") -> OPPO
            m.contains("vivo") || m.contains("iqoo") -> VIVO
            else -> GENERIC
        }
    }
}
