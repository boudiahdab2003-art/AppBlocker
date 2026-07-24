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
 * True if the YouTube Shorts player (the "reel" surface) is currently on screen. We match
 * the player's view-ids, not the always-present "Shorts" nav tab.
 */
internal fun AccessibilityService.isShortsOnScreen(): Boolean {
    val root = rootInActiveWindow ?: return false
    if (root.packageName != YOUTUBE_PKG) return false
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
    return false
}

/**
 * The browser's omnibox text (trimmed, lowercased), or null when no omnibox is on
 * screen (fullscreen video, a browser UI change) — callers must then fall back to
 * page-text matching so fullscreen can't become a bypass. Chromium browsers expose
 * the omnibox as <pkg>:id/url_bar (flagReportViewIds is set in our service config).
 */
internal fun AccessibilityService.extractBrowserUrl(pkg: String): String? {
    val urlBarId = "$pkg:id/url_bar"
    val roots = ArrayList<AccessibilityNodeInfo>()
    rootInActiveWindow?.let(roots::add) // the omnibox the user sees wins
    windows?.forEach { w ->
        if (w.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) w.root?.let(roots::add)
    }
    for (root in roots) {
        if (root.packageName?.toString() != pkg) continue
        // Nodes can be recycled under us mid page-churn — treat failures as "not found".
        val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(urlBarId) }
            .getOrNull() ?: continue
        for (n in nodes) {
            val t = n.text?.toString()?.trim()?.lowercase()
            if (!t.isNullOrBlank()) return t
        }
    }
    return null
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
