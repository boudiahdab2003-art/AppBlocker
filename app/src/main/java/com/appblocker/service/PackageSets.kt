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

/** All packages that can handle an https:// link — i.e. the device's browsers. */
internal fun findBrowserPackages(context: Context): Set<String> = runCatching {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        .addCategory(Intent.CATEGORY_BROWSABLE)
    context.packageManager.queryIntentActivities(intent, 0)
        .mapNotNull { it.activityInfo?.packageName }
        .filter { it != context.packageName }
        .toSet()
}.getOrDefault(emptySet())

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
