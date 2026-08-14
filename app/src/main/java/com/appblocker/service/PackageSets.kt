package com.appblocker.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
 * Everything that can open a web address — **deliberately generous, and used for scanning only.**
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
 * **This set contains things that are not browsers, and that is fine here.** An app registering a
 * *deep link* — its own site, opening its own screen — answers the same query a browser does, so
 * the owner's phone returns WPS Office, Coinbase, SHAREit and Bing. Scanning them costs a check
 * for blocked words, which is harmless and occasionally useful. **Do not tighten this set to fix
 * that.** The harm came from the blanket block reading the same list, and the fix for it is
 * [findRealBrowserPackages], not a narrower answer here — narrowing here is how a real browser
 * goes silently unfiltered.
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
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .mapNotNull { it.activityInfo?.packageName }
                .let(found::addAll)
        }
    }
    runCatching { defaultBrowser(context)?.let(found::add) }
    for (pkg in KNOWN_BROWSERS) {
        runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()?.let { found.add(pkg) }
    }
    return found.filter { it != context.packageName }.toSet()
}

/**
 * The subset of [findBrowserPackages] that is really a browser, rather than an app that happens
 * to open its own links.
 *
 * **Why two sets rather than one accurate one.** The two consumers want opposite mistakes:
 *
 * - *Scanning* uses the loose set. Including an app that isn't a browser costs almost nothing —
 *   it gets read for blocked words, which is fine — while excluding a real browser is a whole
 *   browser exempt from filtering, invisibly.
 * - *Blanket-blocking* ("block unsupported browsers") uses this one. Here the mistake reverses:
 *   including something wrongly means the app is **blocked outright**, which is how WPS Office,
 *   Coinbase and SHAREit ended up unusable on the owner's phone.
 *
 * **Membership is by self-declaration, not by inference from intent filters.** Two attempts were
 * made at inferring it — "handles an https link", then "handles one with no host restriction" —
 * and the owner's phone defeated both: WPS Office, Coinbase, SHAREit and Bing survived the second
 * as well, and from a distance it is not decidable whether Android returned no resolved filter or
 * those apps really do declare a scheme-only web filter. Guessing a third time is the mistake, not
 * the specific guess. What is *not* ambiguous is whether an app says it is a browser:
 *
 * 1. **[Intent.CATEGORY_APP_BROWSER]** — the category an app declares to mean "I am the browser",
 *    and what `Intent.makeMainSelectorActivity` opens. An office app does not declare it under
 *    either explanation above, which is what makes this robust where filter shape was not.
 * 2. **The default browser** — a browser by definition, whatever its filters say.
 * 3. **[KNOWN_BROWSERS]** that are installed.
 *
 * Any one is enough; none of them is inferred. The cost is a browser declaring none of the three
 * and not being the default: it escapes the *blanket block* while keeping every other layer,
 * because it is still in the loose set. That is the right way round — the blanket block is
 * belt-and-braces, and its failure mode today is a Coinbase that will not open.
 */
internal fun findRealBrowserPackages(context: Context): Set<String> {
    val pm = context.packageManager
    val found = mutableSetOf<String>()
    runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { it.activityInfo?.packageName }
            .let(found::addAll)
    }
    runCatching { defaultBrowser(context)?.let(found::add) }
    for (pkg in KNOWN_BROWSERS) {
        runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()?.let { found.add(pkg) }
    }
    return found.filter { it != context.packageName }.toSet()
}

/** The user's default browser, which is a browser by definition whatever its filters say. */
private fun defaultBrowser(context: Context): String? = runCatching {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        .addCategory(Intent.CATEGORY_BROWSABLE)
    context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName
}.getOrNull()


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
internal val KNOWN_BROWSERS = listOf(
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
    // OEM browsers. The Xiaomi pair are on the owner's phone and neither was here, which mattered
    // once this list started deciding what may be blanket-blocked as well as what gets scanned.
    //
    // The rest were added for the phones nobody here owns, and they are the ones that mattered
    // most: on a Huawei, Oppo or Vivo the OEM browser is usually the phone's *default*, which puts
    // it in findRealBrowserPackages by itself — so with "block unsupported browsers" on, the built-
    // in browser was blocked outright and, per KNOWN_READABLE_BROWSERS below, could never earn its
    // way out. Being named here is what lets the readable seed name it too (WebContentFilterTest
    // requires the pairing).
    "com.mi.globalbrowser", "com.miui.browser", // Xiaomi
    "com.tcl.browser", // TCL "BrowseHere"
    "com.huawei.browser", "com.hihonor.browser", // Huawei / Honor
    "com.heytap.browser", "com.nearme.browser", "com.coloros.browser", // Oppo/Realme/OnePlus
    "com.vivo.browser", // Vivo
    "com.sec.android.app.sbrowser.beta", // Samsung Internet Beta (stable is above)
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
    // com.miui.browser was in KNOWN_BROWSERS and not here — so on the owner's own phone, MIUI
    // Browser was eligible for the blanket block and had no way out of it, while its global
    // sibling two names back was fine. Found by the OEM-browser test below; same Chromium toolbar.
    //
    // **This entry is on probation.** The owner's screenshot (14 Aug 2026) shows instagram.com
    // open and unfiltered in Mi Browser, which means its address bar was not being read at all —
    // its toolbar is a label, not a field, so tiers 1-3 all missed it. Tier 4 in ScreenText.kt
    // exists to fix that. If Profile ▸ "What the blocker sees" still reports this one as *assumed*
    // rather than *read here* after tier 4 ships, the claim is false and the entry comes out —
    // with evidence, rather than on a second guess. Nothing here is inert: while it sits in this
    // list, Mi Browser is exempt from the unsupported-browser block.
    "com.miui.browser",
    // The OEM browsers, and **the reason this seed matters most.** On a Huawei, Oppo/OnePlus or
    // Vivo the built-in browser is usually the default, so it lands in the strict set on its own
    // and — being in neither readable list — was blanket-blocked with no way out: a blocked
    // browser sits under our own cover, its address bar is never read, `addReadableBrowser` never
    // fires, and it can never demonstrate that it is filterable. The phone's own browser, blocked
    // forever, on every brand except the two the owner happens to have.
    //
    // All are Blink forks that keep Chromium's toolbar, so the claim is `url_bar` — the same basis
    // on which Brave, Edge, Opera, Vivaldi, Kiwi and Xiaomi's browser are already seeded. **If one
    // of them renames that id the claim is wrong in the silent direction** (not blocked, not
    // filtered either). Three things bound that: the address-bar reader's third tier reads any
    // *editable* host-shaped node and needs no id at all; the phone promotes the browser itself the
    // first time it is genuinely read; and Profile ▸ "What the blocker sees" lists which browsers
    // count as readable, so a wrong claim is inspectable rather than invisible.
    "com.huawei.browser", "com.hihonor.browser",
    "com.heytap.browser", "com.nearme.browser", "com.coloros.browser",
    "com.vivo.browser",
    // <pkg>:id/location_bar_edit_text
    "com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.beta",
    // <pkg>:id/mozac_browser_toolbar_url_view
    "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix",
    "org.mozilla.focus",
)
