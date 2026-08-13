package com.appblocker.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.Settings
import android.telecom.TelecomManager

/**
 * "Which packages are launchers / browsers / must-never-be-blocked essentials" — pure
 * PackageManager lookups, kept out of [BlockerAccessibilityService] so the service file stays
 * about deciding and enforcing blocks. The service caches the results and re-runs these on
 * package changes; nothing here holds state.
 */

/**
 * All installed home-screen (launcher) apps — never keyword-scanned: a keyword matching an
 * app's label on the home screen would cover Home itself, and Close→home would loop forever.
 * The *current default* home is also resolved explicitly — MATCH_ALL has missed it on some
 * OEM builds, and a missed launcher means false blocks on the home screen.
 */
internal fun findLauncherPackages(context: Context): Set<String> = runCatching {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val all = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        .mapNotNull { it.activityInfo?.packageName }
    val default = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName
    (all + listOfNotNull(default)).toSet()
}.getOrDefault(emptySet())

/**
 * Browsers this device has, gathered from four sources and unioned.
 *
 * **Being missing from this set switches a browser's entire filtering off, silently.** It gates
 * the web scan (`shouldScanPkg`), the address-bar read, blocked apps' websites, the adult site
 * list, and the "block unsupported browsers" switch — every one of them is `pkg in
 * browserPackages`. So the failure mode is not "one query came back a bit short", it is a whole
 * browser quietly exempt from blocking, with nothing on screen to say so. [findLauncherPackages]
 * already carries the same lesson in its comment (MATCH_ALL has missed the default home on some
 * OEM builds); this asked one question, with flags `0`, and trusted the answer.
 *
 * Hence three sources rather than one, on the principle that a browser only has to be found *once*:
 *
 * 1. `https://` + BROWSABLE with **MATCH_ALL**, matching `findLauncherPackages`, then `http://`
 *    as well — filters are usually declared for both, but "usually" is what put this comment here.
 * 2. The **default browser**, resolved explicitly — the exact belt-and-braces the launcher lookup
 *    needed for the same reason.
 * 3. [KNOWN_BROWSERS] that are actually installed. The vendor-string tier, last on purpose: it
 *    only ever *adds*, and a browser it doesn't name is no worse off than before.
 *
 * **[acceptsAnyWebAddress] is what makes the answer mean "browser".** Asking who can
 * open an https link is not that question, and the difference is not academic: the owner's phone
 * answered it with WPS Office, Coinbase, SHAREit and Bing, none of which browse anything. They
 * were there because an app that registers a *deep link* — its own site, opening its own screen —
 * matches the same query a browser does. Being wrongly in this set is not harmless: with "block
 * unsupported browsers" on, every one of them is a browser we cannot read, so all four were
 * **blocked outright**. A real browser declares it accepts *any* web address, and that flag is
 * Android's own way of saying so.
 *
 * Each source is wrapped separately: one throwing must not empty the set (invariant 11 — an empty
 * answer is not data; the caller only adopts a non-empty result).
 */
internal fun findBrowserPackages(context: Context): Set<String> {
    val pm = context.packageManager
    val found = mutableSetOf<String>()
    for (scheme in listOf("https", "http")) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://example.com"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
            val flags = PackageManager.MATCH_ALL or PackageManager.GET_RESOLVED_FILTER
            pm.queryIntentActivities(intent, flags)
                .filter { acceptsAnyWebAddress(it) }
                .mapNotNull { it.activityInfo?.packageName }
                .let(found::addAll)
        }
    }
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val flags = PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_RESOLVED_FILTER
        pm.resolveActivity(intent, flags)
            ?.takeIf { acceptsAnyWebAddress(it) }
            ?.activityInfo?.packageName?.let(found::add)
    }
    for (pkg in KNOWN_BROWSERS) {
        runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()?.let { found.add(pkg) }
    }
    return found.filter { it != context.packageName }.toSet()
}

/**
 * Whether [info] is a browser rather than an app that merely opens its own links.
 *
 * `IntentFilter.handleAllWebDataURI()` is Android's own phrasing of the question — *does this
 * filter accept **any** web address* — and it is the difference between Chrome and the
 * Coinbase/WPS/SHAREit/Bing entries that were being treated as browsers, and therefore blocked
 * as unreadable ones. The filter is only attached when `GET_RESOLVED_FILTER` was asked for, which
 * is why both queries pass it.
 *
 * **A missing filter counts as yes**, deliberately. It should not happen with that flag, but if
 * it ever does the choice is between an app wrongly filtered (visible, annoying) and a browser
 * silently exempt from all blocking (invisible, and the failure this whole file is about). The
 * app's standing rule is that can't-tell never takes the permissive branch.
 */
private fun acceptsAnyWebAddress(info: ResolveInfo): Boolean =
    info.filter?.handleAllWebDataURI() ?: true

/**
 * Browsers named outright, as the backstop tier of [findBrowserPackages].
 *
 * A list of vendor strings is exactly what invariant 12 warns against *relying* on — so it is not
 * relied on. It runs after three vendor-agnostic queries and can only add to them, which makes it
 * additive insurance rather than the mechanism. Adding a name here is cheap and safe; the list
 * being incomplete costs nothing that the queries already cover.
 *
 * The `resolveActivity` above returns the default browser under whatever name it has, so a
 * browser missing from this list is still found whenever it is the user's default.
 */
private val KNOWN_BROWSERS = listOf(
    "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
    "com.brave.browser", "com.brave.browser_beta", "com.brave.browser_nightly",
    "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix", "org.mozilla.focus",
    "com.microsoft.emmx", // Edge
    "com.opera.browser", "com.opera.mini.native", "com.opera.gx",
    "com.sec.android.app.sbrowser", // Samsung Internet
    "com.vivaldi.browser", "com.kiwibrowser.browser", "com.duckduckgo.mobile.android",
    "com.UCMobile.intl", "org.torproject.torbrowser", "com.yandex.browser",
    "com.ecosia.android", "acr.browser.lightning", "org.adblockplus.browser",
    "mark.via.gp", "com.qwant.liberty", "com.aloha.browser", "idm.internet.download.manager",
    "com.android.browser", "com.google.android.apps.chrome",
)

/**
 * System packages that must stay usable in Allowlist mode or the phone bricks: the core
 * Android system, System UI (status bar / recents / power menu), Settings, and the default
 * phone/dialer app (so calls work). The launcher is handled by the service's launcher check
 * and the current keyboard by [currentImePackage] (re-read live, since it can change).
 */
internal fun findEssentialPackages(context: Context): Set<String> {
    val set = mutableSetOf("android", "com.android.systemui", "com.android.settings")
    runCatching {
        val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        tm?.defaultDialerPackage?.let { set.add(it) }
    }
    // Whatever handles the phone/dialer intent (covers OEMs whose dialer differs from the
    // default-dialer role, and the incoming-call UI).
    runCatching {
        val dial = Intent(Intent.ACTION_DIAL)
        context.packageManager.resolveActivity(dial, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.let { set.add(it) }
    }
    return set
}


/**
 * The package of the keyboard the user currently has selected, or null. Read live so a
 * keyboard swap never locks typing out.
 */
internal fun currentImePackage(context: Context): String? = runCatching {
    Settings.Secure.getString(
        context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD,
    )?.substringBefore('/')?.takeIf { it.isNotBlank() }
}.getOrNull()

/**
 * Browsers whose toolbar spelling the app knows, so it can read the address bar in them.
 *
 * **This was `setOf("com.android.chrome")`, and that one line was doing real damage.**
 * Everything else counted as "can't be filtered", which with "block unsupported browsers"
 * on means *blocked outright* — so Brave, which is Chromium underneath and whose address
 * bar the app reads perfectly well, was having the whole browser blocked because a
 * constant said it couldn't be read. Over-blocking justified by a stale guess.
 *
 * The list now says what it means: these are the packages whose omnibox matches one of
 * `OMNIBOX_ID_SUFFIXES` in `ScreenText.kt`, and the two must be edited together — a
 * browser named here whose id is not there is claimed readable and is not, which is the
 * silent under-block this whole area keeps producing.
 *
 * It is a seed, not the whole answer. Any browser at all joins
 * [SettingsStore.readableBrowsers] the moment its address bar is genuinely read, so a
 * browser missing here is only ever *temporarily* treated as unreadable — and one that
 * is never readable keeps exactly the old treatment. The seed exists because a browser
 * that is blanket-blocked never gets scanned, so without it a filterable browser could
 * never demonstrate that it is filterable.
 */
internal val KNOWN_READABLE_BROWSERS = setOf(
    // Chromium and its forks — all expose the omnibox as <pkg>:id/url_bar.
    "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
    "com.google.android.apps.chrome",
    "com.brave.browser", "com.brave.browser_beta", "com.brave.browser_nightly",
    "com.microsoft.emmx", "com.opera.browser", "com.opera.gx",
    "com.vivaldi.browser", "com.kiwibrowser.browser", "com.duckduckgo.mobile.android",
    "com.ecosia.android", "com.mi.globalbrowser", "com.android.browser",
    // <pkg>:id/location_bar_edit_text
    "com.sec.android.app.sbrowser",
    // <pkg>:id/mozac_browser_toolbar_url_view
    "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix",
    "org.mozilla.focus",
)
