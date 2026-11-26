package com.appblocker.service

/**
 * **Instagram is blocked on this phone, completely and for good** (invariant 112).
 *
 * 26 Nov 2026, the evening he relapsed by uninstalling AppBlocker: "can we make something special
 * for instagram". Instagram is where his relapses start (his own words on 16 Oct: "all of my
 * relapses are from instagram") — not adult content, which every earlier wall guarded. The PC was
 * walled off that day; the phone was left "as it is" until now. His picks:
 *  - **"Block it completely"**: the Instagram app, Threads, instagram.com and the viewer/mirror
 *    sites that show Instagram without an account, in every browser. Messages go too.
 *  - **"Never from the phone"**: no switch, no wait, no setting. It does not read the block list,
 *    Allowlist mode, Quick Block, a session or the update pause; only a new version of the app can
 *    change it. The same shape as the Ultra battery saver lock (invariant 102).
 *
 * ⚠️ Never read the mirror sites out to him — name the category, not the sites
 * (`feedback_never_name_porn_sites`, same rule). The list is the one his PC blocks
 * (`PCBlocker/personal/Block Instagram.bat`) plus Threads and Instagram's image host.
 *
 * Pure, so [decideBlock] and [WebContentFilter.checkUrl] can ask it and the tests can pin it.
 */
internal object InstagramBan {

    /** Every Meta app published under Instagram's name: Instagram, Lite, Threads
     *  (`barcelona`), Edits, Layout, Boomerang — and anything new under the same prefix. */
    const val PACKAGE_PREFIX = "com.instagram."

    val KNOWN_PACKAGES = setOf(
        "com.instagram.android",
        "com.instagram.lite",
        "com.instagram.barcelona",
        "com.instagram.basel",
        "com.instagram.layout",
        "com.instagram.boomerang",
    )

    fun isBannedApp(pkg: String): Boolean = pkg in KNOWN_PACKAGES || pkg.startsWith(PACKAGE_PREFIX)

    /**
     * Matched as whole domains anywhere in the address (`.` and `/` are boundaries), so
     * `m.instagram.com/reels` and a search for one of these sites both hit, while a host that merely
     * contains the letters (`notinstagram.com`, `instagrammer.com`) does not.
     */
    val DOMAINS = listOf(
        "instagram.com", "instagr.am", "ig.me", "cdninstagram.com",
        "threads.net", "threads.com",
        "anonyig.com", "dumpoir.com", "dumpor.com", "gramhir.com", "greatfon.com", "iganony.io",
        "imginn.com", "imginn.org", "imgsed.com", "inflact.com", "ingramer.com",
        "insta-stories-viewer.com", "instanavigation.com", "mystalk.net", "picbon.com",
        "picnob.com", "picuki.com", "pixwox.com", "smihub.com", "storiesig.info", "storysaver.net",
    )

    /** The banned domain [url] is on, or null. [url] is the address as the browser shows it. */
    fun bannedSite(url: String): String? {
        val lower = url.lowercase()
        if (lower.isBlank()) return null
        return DOMAINS.firstOrNull { WebContentFilter.containsWord(lower, it) }
    }
}
