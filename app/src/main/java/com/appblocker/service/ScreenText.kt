package com.appblocker.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Reading what is currently on screen through the accessibility node tree: the browser's
 * omnibox, all visible text, and whether the YouTube Shorts player is up. Extracted from
 * [BlockerAccessibilityService] so the node-walking lives apart from the blocking decisions;
 * these are extensions on the service because only it can reach the node tree.
 *
 * Every walk is capped ([MAX_NODES], [MAX_TEXT]) — an uncapped tree walk on a churning feed
 * is a battery drain.
 */

private const val MAX_NODES = 400
private const val MAX_TEXT = 4000

/** Packages we never scan for keywords: they list other apps' names (see the service). */
private val TEXT_SCAN_EXCLUDED = setOf("com.android.systemui", "com.android.settings")

private const val YOUTUBE_PKG = "com.google.android.youtube"

// YouTube Shorts player view-id fragments (Shorts is "reel" internally). These match the
// full-screen Short player, NOT the always-present "Shorts" nav tab. Exact ids vary by
// YouTube version, so this list is intentionally broad and may need tuning over time.
private val SHORTS_ID_MARKERS = listOf(
    "reel_recycler", "reel_player", "reel_watch", "reels_player",
    "reel_progress", "shorts_",
)

/**
 * Whether the YouTube Shorts player (the "reel" surface) is on screen: true / false / **null
 * meaning "can't tell"**. We match the player's view-ids, not the always-present "Shorts" nav tab.
 *
 * The three-way answer matters because the caller *removes* a cover on a false, and there are two
 * routine ways to be unable to read the screen at all — an unreadable tree mid-churn, and our own
 * non-focusable cover reporting as the active window (a documented quirk of this device, and the
 * cover is up in exactly the situation this gets asked in). Both used to answer "false", i.e.
 * "not on Shorts", so the cover came off while the user was still watching one. Same
 * can't-tell-is-not-a-no mistake as the covers parked on the home screen in v1.96.
 */
internal fun AccessibilityService.isShortsOnScreen(): Boolean? {
    val root = rootInActiveWindow ?: return null
    val active = root.packageName?.toString()
    // Our own window says nothing about what is behind it; never reconcile to ourselves.
    if (active == packageName) return null
    if (active != YOUTUBE_PKG) return false
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(root)
    var visited = 0
    while (queue.isNotEmpty() && visited < MAX_NODES) {
        val node = queue.removeFirst()
        visited++
        node.viewIdResourceName?.let { id ->
            if (SHORTS_ID_MARKERS.any { id.contains(it) }) return true
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
    }
    // Deliberately "no", not "can't tell", even when the walk stopped at MAX_NODES: the screen WAS
    // readable, we just didn't finish it, and the walk is breadth-first while the reel markers are
    // player/container ids high in the tree. Answering null here would instead risk stranding a
    // Shorts cover over ordinary YouTube — a visible over-block, and the harder one to get out of.
    return false
}

/**
 * The browser's omnibox text (trimmed, lowercased), or null when no omnibox is on
 * screen (fullscreen video, a browser UI change) — callers must then fall back to
 * page-text matching so fullscreen can't become a bypass. How it is found across the
 * different browsers is [omniboxText] (flagReportViewIds is set in our service config).
 */
internal fun AccessibilityService.extractBrowserUrl(pkg: String): String? =
    omniboxText(pkg, settledOnly = false)

/**
 * The omnibox, but only once it has **settled into an address the user actually went to** —
 * null while they are still typing into it.
 *
 * The undebounced address-bar check acts within a couple of hundred milliseconds, which is
 * quick enough to fire on half-typed text: "instagr…" on the way to a search would cover the
 * screen before the user finished the word. The owner asked for the block to wait until he has
 * really gone to the site, so this rejects a focused omnibox (Chrome focuses it while editing)
 * and anything that doesn't look like a host.
 *
 * **Declining here costs no coverage**, which is what makes the strictness safe: the debounced
 * whole-page scan still reads the omnibox through [extractVisibleText], so a blocked word typed
 * into the address bar is still caught — at the speed it was always caught at. This function
 * failing to recognise some future Chrome layout therefore degrades to the old behaviour rather
 * than to no blocking.
 */
internal fun AccessibilityService.extractSettledBrowserUrl(pkg: String): String? =
    omniboxText(pkg, settledOnly = true)

/**
 * View-id suffixes that name a browser's address bar.
 *
 * A suffix rather than the whole id because the id is prefixed with the *browser's own package*
 * — `com.android.chrome:id/url_bar`, `com.brave.browser:id/url_bar` — so matching the tail is
 * what makes one rule cover every browser instead of one.
 *
 * Pure so it can be unit-tested; `endsWith` and not `contains` on purpose, or `url_bar_scrim`
 * and friends would match.
 */
internal fun isOmniboxId(id: String): Boolean = OMNIBOX_ID_SUFFIXES.any { id.endsWith(it) }

private val OMNIBOX_ID_SUFFIXES = listOf(
    ":id/url_bar", // Chromium and every fork of it: Chrome, Brave, Edge, Opera, Vivaldi, Kiwi
    ":id/location_bar_edit_text", // Samsung Internet
    ":id/mozac_browser_toolbar_url_view", // Firefox and the other Gecko/Fenix builds
)

/**
 * The address bar, found three ways — **in a browser that isn't Chrome, this is the difference
 * between website blocking working and doing nothing at all.**
 *
 * Blocking a site (`instagram.com` because the Instagram app is blocked) is matched against the
 * address **only**, never the page text — deliberately, so an article that merely mentions
 * Instagram doesn't cover the screen ([WebContentFilter.check]). That makes reading the address
 * bar the single point of failure for the whole layer: fail, and the site layer goes *silent*
 * rather than falling back to something safe. It looked up exactly one id,
 * `"<pkg>:id/url_bar"`, written against Chrome — so a blocked app's website opened freely in
 * any browser whose toolbar is spelled differently, with nothing on screen to say so. The owner
 * found it in Brave.
 *
 * Three tiers, cheapest first, each a strict fallback from the last:
 *
 * 1. **The exact id** — one indexed lookup, no walk. What Chrome has always taken.
 * 2. **Any [isOmniboxId] view id** — same idea, but found by walking, so a fork that keeps
 *    Chromium's layout under its own package name is read the same way Chrome is.
 * 3. **Any *editable* node whose text is host-shaped** — no id at all, for browsers not on the
 *    list. Editability is what keeps this tier safe. The one thing a URL-shaped fallback could
 *    get wrong is mistaking a link *on the page* for the address and blocking a page that merely
 *    links to a blocked site — which is the exact over-block the URL-only rule exists to
 *    prevent. A page's links are not editable; a browser's address bar is the only editable
 *    field in its chrome. So the mistake is unreachable rather than merely unlikely.
 *
 * A browser none of the three can read behaves exactly as every non-Chrome browser did before:
 * page-text matching only. So this is purely additive — it can start blocking a site that used
 * to slip through, and it cannot start blocking anything that used to be allowed.
 */
private fun AccessibilityService.omniboxText(pkg: String, settledOnly: Boolean): String? {
    val roots = ArrayList<AccessibilityNodeInfo>()
    rootInActiveWindow?.let(roots::add) // the omnibox the user sees wins
    windows?.forEach { w ->
        if (w.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) w.root?.let(roots::add)
    }
    // distinct(): the active window is normally in `windows` too, and tiers 2/3 walk each root.
    val mine = roots.filter { it.packageName?.toString() == pkg }.distinct()

    // isFocused is the edit state: Chrome gives the omnibox input focus while it is being typed
    // into, and takes it away once a page is showing. The host shape is the backstop for
    // anything that reports focus differently.
    fun accept(n: AccessibilityNodeInfo): String? {
        val t = n.text?.toString()?.trim()?.lowercase()
        if (t.isNullOrBlank()) return null
        if (settledOnly && (runCatching { n.isFocused }.getOrDefault(true) || !looksLikeHost(t))) {
            return null
        }
        return t
    }

    // 1. The exact id.
    for (root in mine) {
        // Nodes can be recycled under us mid page-churn — treat failures as "not found".
        val nodes = runCatching { root.findAccessibilityNodeInfosByViewId("$pkg:id/url_bar") }
            .getOrNull() ?: continue
        for (n in nodes) accept(n)?.let { return it }
    }

    // 2 and 3, in one walk each: a known omnibox id wins outright, and a host-shaped editable
    // field is held back as the answer only if no id matched anywhere.
    var editableHost: String? = null
    for (root in mine) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            val id = runCatching { node.viewIdResourceName }.getOrNull()
            if (id != null && isOmniboxId(id)) accept(node)?.let { return it }
            if (editableHost == null && runCatching { node.isEditable }.getOrDefault(false)) {
                accept(node)?.takeIf { looksLikeHost(it) }?.let { editableHost = it }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
    }
    return editableHost
}

/**
 * Whether omnibox text reads as an address rather than something being typed or searched for.
 *
 * Deliberately crude — it only has to separate "he is on a site" from "he is typing" — and
 * deliberately strict, because a false NO costs nothing (the page scan still runs) while a false
 * YES is the instant-block-while-typing behaviour the owner asked not to have.
 */
internal fun looksLikeHost(text: String): Boolean {
    val t = text.trim()
    if (t.isEmpty() || t.contains(' ')) return false // a search query, not an address
    val host = t.substringAfter("://").substringBefore('/')
    return host.contains('.') && !host.startsWith('.') && !host.endsWith('.')
}

/**
 * Collects on-screen text (URL bar, search fields, page) across all windows — not
 * just the active one, since Chrome's omnibox/page can sit in a non-active window
 * (suggestion popup, dialog). [isLauncherPkg] comes from the service, which keeps the
 * self-healing launcher set.
 */
internal fun AccessibilityService.extractVisibleText(isLauncherPkg: (String) -> Boolean): String {
    val sb = StringBuilder()
    val roots = ArrayList<AccessibilityNodeInfo>()
    windows?.forEach { w ->
        // Skip the keyboard: its suggestion strip shows a blocked word while it's being
        // typed. (windows is empty without flagRetrieveInteractiveWindows on stock builds,
        // but some OEMs populate it — this keeps them safe too.)
        if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return@forEach
        w.root?.let(roots::add)
    }
    rootInActiveWindow?.let(roots::add)
    // Never scan our own windows (e.g. the block screen, whose message can itself
    // contain the keyword) — that would re-trigger the block in a loop. Same for
    // launcher/System UI windows overlaying the scanned app on OEM builds.
    roots.removeAll {
        val p = it.packageName?.toString() ?: return@removeAll false
        p == packageName || isLauncherPkg(p) || p in TEXT_SCAN_EXCLUDED
    }
    if (roots.isEmpty()) return ""

    val queue = ArrayDeque<AccessibilityNodeInfo>()
    roots.forEach(queue::add)
    var visited = 0
    while (queue.isNotEmpty() && visited < MAX_NODES && sb.length < MAX_TEXT) {
        val node = queue.removeFirst()
        visited++
        node.text?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
        node.contentDescription?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { queue.add(it) }
        }
    }
    return sb.toString()
}
