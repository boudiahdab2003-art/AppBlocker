package com.appblocker.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import com.appblocker.service.GuardPackages
import com.appblocker.service.KNOWN_BROWSERS
import com.appblocker.service.KNOWN_READABLE_BROWSERS
import com.appblocker.service.findRealBrowserPackages

/**
 * **What this phone answers when you ask it the questions we had to guess at.**
 *
 * `PhoneReport.kt` states the problem: everything the app believes about a non-Xiaomi phone was
 * reasoned rather than measured, and each guess **fails silently**. Profile ▸ *What the blocker
 * sees* turns those questions into sentences a human reads off a screenshot, which is the right
 * tool when the owner is holding the phone and the wrong one for every phone he isn't.
 *
 * This is the same three lookups with the screen taken off, so that three callers can share one
 * answer: the diagnostics screen (for a human), `DeviceProbeTest` (for a device farm), and
 * [BugReportSender.reportDeviceProfile] — which sends them home **on a phone where nothing is
 * wrong**, because that is the only case the app could never previously report.
 *
 * ## Why a working phone has to report too
 *
 * A Samsung whose uninstall screen we mis-guessed does not crash. Strict Mode simply stops
 * protecting, and nothing anywhere says so. The existing reporter only fires on a `Throwable`, so
 * the entire class of "quietly wrong on hardware nobody owns" was unreportable by construction.
 * Sending on success is also what makes silence meaningful: without it, no news means either *all
 * good* or *the reporting is broken*, and those need different reactions.
 *
 * ## What may leave the device, and why these three are allowed
 *
 * [BugReport]'s contract is an allow-list and it forbids package names, for the good reason that
 * what someone blocks is the most sensitive thing this app holds. These fields are named
 * exceptions, each argued rather than assumed:
 *
 *  - **the uninstall handler** — the OS component that would show *our own* uninstall dialog,
 *    resolved from our own package name. It says something about the phone's ROM and nothing
 *    about its owner: every phone of a brand gives the same answer.
 *  - **the keep-alive component** — one of the seven constants this app already ships in
 *    `DeviceVendor`. Reporting which of our own guesses resolved cannot describe the reader.
 *  - **browsers, filtered through [KNOWN_BROWSERS]** — the only list that matters is whether a
 *    browser we *claim* to be able to read is present. Filtering against our own constant is what
 *    keeps this a check against our list rather than an inventory of someone's phone: a browser
 *    we never shipped a name for is invisible here, and so is every non-browser app.
 *
 * Nothing here can carry a keyword, a URL, on-screen text, or a blocked app.
 */
object DeviceProfile {

    /**
     * "there are none", written once.
     *
     * It was four literals here and a fifth in [BugReport], where the report decides whether a row
     * is worth acting on — so a change to the wording would silently stop that check matching and
     * the report would start naming a fault the phone does not have. Same reason the "row is bad"
     * rule is one function.
     */
    const val NONE = "(none)"

    /**
     * A lookup that resolved nowhere — the value, written once.
     *
     * Produced here for two rows and matched in [BugReport], which decides from `keepAlive` whether
     * the whole profile counts as healthy and titles the issue accordingly. Two producers and a
     * consumer, all spelling the same string by hand: reword one and the report goes on saying
     * "profile OK" for a phone whose keep-alive button goes nowhere.
     */
    const val UNRESOLVED = "NONE RESOLVED"

    /** The `where` tag that marks a report as a profile rather than a fault. */
    const val WHERE = BugReport.PROFILE_WHERE

    /** Which package would show "uninstall AppBlocker?" here, or null if nothing resolved. */
    fun uninstallHandler(context: Context): String? = runCatching {
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${context.packageName}"))
        context.packageManager
            .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }.getOrNull()

    /**
     * Which Settings screen the **Grant** button actually lands on here, as `package/ShortClass`.
     *
     * **This is the measurement that replaces a guess.** The setup guide tells people where their
     * brand of phone keeps the accessibility list, and every one of those sentences was written
     * from knowledge rather than from looking at a phone — precisely the kind of claim this
     * project has already had to walk back once (`com.samsung.android.packageinstaller`, believed
     * for a year, measured wrong in twenty minutes).
     *
     * No API exposes a menu's *label*, so this cannot read "Installed apps" off the screen. What it
     * can read is the activity that handles the intent, and that is enough to tell the phones
     * apart: an OEM that has rebuilt its accessibility screen answers with its own class name.
     * Reported from every phone the app runs on, so the brands nobody here will ever own fill in
     * the table themselves.
     *
     * Null means the intent resolves nowhere — Grant would go nowhere — which is worth hearing
     * loudly rather than discovering from a confused user.
     */
    fun accessibilityScreen(context: Context): String? = runCatching {
        context.packageManager
            .resolveActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), 0)
            ?.activityInfo
            ?.let { "${it.packageName}/${it.name.substringAfterLast('.')}" }
    }.getOrNull()

    /**
     * The first keep-alive deep link that exists on this phone, or null if none did.
     *
     * Null means two different things and the caller must keep them apart: [VendorAdvice.deepLinks]
     * being empty is the generic entry, which points nowhere **by design**, while a non-empty list
     * that resolves nothing is the button silently opening the wrong page.
     */
    fun keepAliveTarget(context: Context, advice: VendorAdvice): Pair<String, String>? =
        advice.deepLinks.firstOrNull { (pkg, cls) ->
            runCatching {
                context.packageManager
                    .resolveActivity(Intent().setComponent(ComponentName(pkg, cls)), 0) != null
            }.getOrDefault(false)
        }

    /** [PhoneFacts] for this phone — the same values the diagnostics screen renders. */
    fun facts(context: Context, sideloaded: Boolean, sdkInt: Int): PhoneFacts {
        val advice = DeviceVendor.advice()
        return PhoneFacts(
            brand = advice.brand,
            sdkInt = sdkInt,
            sideloaded = sideloaded,
            uninstallHandler = uninstallHandler(context),
            keepAliveResolves =
                if (advice.deepLinks.isEmpty()) null else keepAliveTarget(context, advice) != null,
        )
    }

    /**
     * The profile as [BugReport] context, ready for the allow-list.
     *
     * Every value is a short literal or a joined list of our own constants, because these land in
     * a markdown table one row each and a report nobody reads is worth nothing.
     */
    fun reportContext(context: Context): Map<String, String> = runCatching {
        val advice = DeviceVendor.advice()
        val handler = uninstallHandler(context)
        val browsers = runCatching { findRealBrowserPackages(context) }.getOrDefault(emptySet())
            .filter { it in KNOWN_BROWSERS }
        val confirmed = runCatching { SettingsStore.readableBrowsers(context) }
            .getOrDefault(emptySet())

        buildMap {
            put("brand", advice.brand.ifBlank { "(unrecognised — generic advice)" })
            put("uninstallHandler", handler ?: "(did not resolve)")
            put("accessibilityScreen", accessibilityScreen(context) ?: UNRESOLVED)
            put(
                "uninstallGuard",
                uninstallGuardVerdict(handler, GuardPackages.INSTALLERS).name,
            )
            put(
                "keepAlive",
                when {
                    advice.deepLinks.isEmpty() -> "(no deep link by design)"
                    else -> keepAliveTarget(context, advice)
                        ?.let { "${it.first}/${it.second.substringAfterLast('.')}" }
                        ?: UNRESOLVED
                },
            )
            put("browsersKnown", browsers.sorted().joinToString().ifBlank { NONE })
            put(
                "browsersClaimedReadable",
                browsers.filter { it in KNOWN_READABLE_BROWSERS }.sorted()
                    .joinToString().ifBlank { NONE },
            )
            // The interesting one: a browser we claim we can read and never have. This is where
            // the Mi Browser bug lived for months, and it is invisible from anywhere else.
            put(
                "browsersClaimUnproven",
                browsers.filter { it in KNOWN_READABLE_BROWSERS && it !in confirmed }.sorted()
                    .joinToString().ifBlank { NONE },
            )
        }
    }.getOrDefault(emptyMap())

    /**
     * What makes a profile worth sending again: this phone, on this build.
     *
     * Not a random id and not a counter — the point is one report per *thing we might have got
     * wrong*, and what we might have got wrong changes when the phone changes or when we ship new
     * guesses about it. A user who opens the app twice a day for a year sends this twice: once,
     * and again after each update.
     */
    fun signature(device: String, appVersion: String): String = "$device|$appVersion"
}
