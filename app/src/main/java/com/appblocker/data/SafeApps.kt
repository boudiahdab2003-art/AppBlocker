package com.appblocker.data

/**
 * Apps that "Add newly installed apps" leaves alone — his ask, 9 Nov 2026: "some apps are really
 * safe like reverso or google translate so why dont you create a free list for normal apps that
 * are really really safe".
 *
 * ⚠️ **A fixed list in the code, on purpose, and never a list he can edit.** An editable "safe
 * list" is an unblock button with extra steps: add the app in a weak moment, then install it. Here
 * the only way onto the list is a new version of AppBlocker.
 *
 * What earns a place: an app with one narrow job and **no open content** — nothing to browse,
 * no feed, no search of the web, no strangers. Translators and dictionaries, sign-in code apps,
 * document editors, a calculator, the train and the parcel. Maps, messengers, shops, anything with
 * videos, reviews or a built-in browser do not qualify, however useful.
 *
 * Three limits that keep it honest:
 * - **Only the automatic path.** Blocking one of these by hand still works, and an app already
 *   blocked stays blocked.
 * - **A browser is never safe** — `NewAppRules` checks that first, whatever this list says.
 * - **Allowlist mode ignores it**: there a new app is blocked by not being on his own list.
 *
 * Exact package names only. A name that is wrong matches nothing, which is the harmless side.
 */
internal object SafeApps {

    val PACKAGES: Set<String> = setOf(
        // Translators and dictionaries
        "com.google.android.apps.translate",
        "com.softissimo.reverso.context",
        "com.deepl.mobiletranslator",
        "com.microsoft.translator",
        "cc.dict.dictcc",
        "org.leo.android.dict",
        "com.linguee.linguee",
        // Language and study
        "com.duolingo",
        "com.ichi2.anki",
        // Sign-in codes
        "com.google.android.apps.authenticator2",
        "com.azure.authenticator",
        "com.authy.authy",
        // Documents and everyday tools
        "com.google.android.calculator",
        "com.google.android.deskclock",
        "com.google.android.keep",
        "com.google.android.calendar",
        "com.google.android.apps.docs.editors.docs",
        "com.google.android.apps.docs.editors.sheets",
        "com.google.android.apps.docs.editors.slides",
        "com.microsoft.office.word",
        "com.microsoft.office.excel",
        "com.microsoft.office.powerpoint",
        "com.adobe.reader",
        // Travel and parcels
        "de.hafas.android.db",
        "de.dhl.paket",
    )

    fun isSafe(pkg: String): Boolean = pkg in PACKAGES
}
