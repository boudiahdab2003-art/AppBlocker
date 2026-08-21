package com.appblocker.service

import android.accessibilityservice.AccessibilityService
import android.os.Build
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

/** How far up tier 4 looks for a WebView ancestor before giving up — see [insidePage]. */
private const val MAX_ANCESTORS = 12

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
 *
 * It reads only the text, so it never sees [BrowserAddress.Blank] — which is why [looseAddress]
 * refuses on a blank bar rather than [extractBrowserAddress] filtering afterwards. Answering
 * null here is how the start page reaches the fast path at all.
 */
internal fun AccessibilityService.extractSettledBrowserUrl(pkg: String): String? =
    omniboxText(pkg, settledOnly = true)

/**
 * The address bar as one of three answers rather than a nullable string — see [BrowserAddress].
 * The address itself is the omnibox text, trimmed and lowercased; how it is found across the
 * different browsers is [omniboxRead] (flagReportViewIds is set in our service config).
 *
 * The distinction this adds is **empty bar** vs **no bar**, and it is the whole of the start-page
 * fix: an empty bar is a browser sitting on its start page (or waiting to be typed into), where
 * everything on screen is the phone's own furniture — most-visited tiles, the suggestion list,
 * recently closed tabs, the feed. A missing bar is a failed reading and keeps its old, cautious
 * treatment.
 *
 * **Only the tiers that identify the bar structurally may answer [BrowserAddress.Blank].** Tiers 1
 * and 2 find it by view id, so they can see it is there and empty. Tiers 3 and 4 identify it *by
 * its text* — an editable host-shaped field, a sole host-shaped label in the chrome — so to them an
 * empty bar and no bar are literally the same observation, and they only ever produce
 * [BrowserAddress.At]. That is invariant 12's rule applied to a new answer: each tier inherits an
 * assumption from the browser it was written against, so a browser we can only read by its text
 * keeps exactly today's behaviour instead of being quietly declared blank.
 *
 * **They may not override one either**, which is the half that was missing and is now
 * [looseAddress]: the text tiers went on walking after tier 1 had seen the bar and found it
 * empty, and a lone host-shaped label in the chrome outranked that measurement here, because
 * `read.text` is tested first. A bookmark row was enough to put the start page back in front of
 * the site layer.
 *
 * **And "empty bar" was not enough, which is why this was measured instead of reasoned about.**
 * On Chrome's new tab page there is no address bar in the tree *at all* — the toolbar is there,
 * `url_bar` is not, and the address moves into the page as a "fakebox" ([isStartPageId]). Read as
 * "no bar", that is a failed measurement and the start page would have gone on being scanned;
 * the fix would have looked right, passed its unit tests, and changed nothing on the phone. So a
 * start-page search box is the second thing that answers [BrowserAddress.Blank], and it says the
 * same thing the empty bar does, more directly: *this browser is not showing a page*.
 */
internal fun AccessibilityService.extractBrowserAddress(pkg: String): BrowserAddress {
    val read = omniboxRead(pkg, settledOnly = false)
    return when {
        read.text != null -> BrowserAddress.At(read.text)
        read.blankBar || read.startPage -> BrowserAddress.Blank
        else -> BrowserAddress.Unreadable
    }
}

/**
 * View-id suffixes that name a browser's **start-page search box** — the "fakebox" Chromium draws
 * in the middle of the new tab page, which is where the address bar goes while there is no page.
 *
 * Same suffix rule and same reason as [isOmniboxId]: the id is prefixed with the browser's own
 * package, so matching the tail covers Chrome, Brave, Edge, Opera and the rest of the fork family
 * at once. Matching an id rather than any text is also what makes this safe to trust — a rendered
 * web page exposes no Android view ids at all, so nothing a site can draw reaches this test.
 */
internal fun isStartPageId(id: String): Boolean = START_PAGE_ID_SUFFIXES.any { id.endsWith(it) }

private val START_PAGE_ID_SUFFIXES = listOf(
    ":id/search_box_text", // Chromium's new-tab fakebox: Chrome and every fork of it
)

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
 * 4. **A host-shaped label in the browser's chrome** — outside any WebView ("not the page", see
 *    [insidePage]) and only when the candidates agree on one host (see [soleHost]).
 *
 *    **Tier 3's safety argument turned out to be Chrome-shaped.** "The only editable field in its
 *    chrome" assumes the address bar is a field at all, and Mi Browser's is a *label* in a bottom
 *    bar that you tap to open a separate editor. So all three tiers missed it, website blocking
 *    was silently off in that browser, and the owner found instagram.com opening freely with
 *    Instagram blocked (14 Aug 2026). The two structural rules above are what replace
 *    editability: a link lives inside the WebView, and a suggestion or history list shows several
 *    addresses where a toolbar shows one.
 *
 * A browser none of the four can read behaves exactly as every non-Chrome browser did before:
 * page-text matching only. So this is still purely additive in the *site* direction — it can start
 * blocking a site that used to slip through. What tier 4 does add is a way to be wrong: a browser
 * that renders its page outside a WebView-classed node and shows exactly one host-shaped label
 * would be misread. That is bounded by both rules, and unlike the failure it fixes it is visible —
 * the cover names the address, and Profile ▸ "What the blocker sees" prints what was read.
 */
private fun AccessibilityService.omniboxText(pkg: String, settledOnly: Boolean): String? =
    omniboxRead(pkg, settledOnly).text

/**
 * What the walk found: the address, and — separately — whether it saw an address bar it could
 * name by view id that was **empty**. The two are independent answers to different questions
 * ("where is he?" and "is there a bar at all?"), which is why they are not one nullable string.
 */
private class OmniboxRead(val text: String?, val blankBar: Boolean, val startPage: Boolean)

/**
 * Whether an id-matched address bar is empty rather than holding an address.
 *
 * **The hint check is not defensive, it is the case that actually happens.** Measured on Chrome
 * 14 (20 Aug 2026): tapping the address bar gives a node whose `text` *and* `hintText` are both
 * "Search or type web address". So an empty bar does not look empty — it looks like an address —
 * and the app had been reading that placeholder as the site the user was on, which is what
 * `diag_host` showed on the diagnostics screen. Text equal to its own hint is a placeholder in
 * every build that shows one, so treating it as empty costs nothing and is what makes the blank
 * case detectable at all. `hintText` is API 26; below that the plain empty test stands alone.
 */
private fun blankBar(node: AccessibilityNodeInfo): Boolean {
    val t = node.text?.toString()?.trim().orEmpty()
    if (t.isEmpty()) return true
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    val hint = runCatching { node.hintText?.toString()?.trim() }.getOrNull().orEmpty()
    return hint.isNotEmpty() && t.equals(hint, ignoreCase = true)
}

private fun AccessibilityService.omniboxRead(pkg: String, settledOnly: Boolean): OmniboxRead {
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
        val full = n.text?.toString() ?: return null
        val focused = runCatching { n.isFocused }.getOrDefault(true)
        // **A field being edited holds the phone's guess as well as the user's input.** Chrome
        // inline-autocompletes the omnibox out of his own history, so only the part before the
        // selection is his — see [typedPortion]. Sliced BEFORE the trim, because the indices
        // the node reports are into the text the node reports.
        val own = if (focused) {
            typedPortion(
                full,
                runCatching { n.textSelectionStart }.getOrDefault(-1),
                runCatching { n.textSelectionEnd }.getOrDefault(-1),
            )
        } else full
        val t = own.trim().lowercase()
        if (t.isBlank()) return null
        if (settledOnly && (focused || !looksLikeHost(t))) return null
        return t
    }

    // An address bar we could name but which held nothing, and a start-page search box standing
    // in for one. Both are only ever set from a view id — see [extractBrowserAddress] for why the
    // text-based tiers are not allowed to answer "blank".
    var blankBarSeen = false
    var startPageSeen = false

    // 1. The exact id.
    for (root in mine) {
        // Nodes can be recycled under us mid page-churn — treat failures as "not found".
        val nodes = runCatching { root.findAccessibilityNodeInfosByViewId("$pkg:id/url_bar") }
            .getOrNull() ?: continue
        for (n in nodes) {
            // Emptiness is decided BEFORE the text is accepted, and that order is the whole
            // point: Chrome hands its placeholder out as the node's text, so accepting first
            // read "search or type web address" as the site he was on. It was in the
            // diagnostics screen as the host, and it is what made the blank case invisible.
            if (blankBar(n)) blankBarSeen = true
            else accept(n)?.let { return OmniboxRead(it, blankBar = false, startPage = false) }
        }
    }

    // 2, 3 and 4, in one walk each: a known omnibox id wins outright, a host-shaped editable field
    // is held back as the answer if no id matched anywhere, and the chrome labels collected for
    // tier 4 are used only if there was no editable field either.
    var editableHost: String? = null
    val chromeLabels = ArrayList<String>()
    for (root in mine) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            val id = runCatching { node.viewIdResourceName }.getOrNull()
            if (id != null && isOmniboxId(id)) {
                if (blankBar(node)) blankBarSeen = true
                else accept(node)?.let { return OmniboxRead(it, blankBar = false, startPage = false) }
            }
            if (id != null && isStartPageId(id)) startPageSeen = true
            val editable = runCatching { node.isEditable }.getOrDefault(false)
            if (editable) {
                if (editableHost == null) {
                    accept(node)?.takeIf { looksLikeHost(it) }?.let { editableHost = it }
                }
            } else if (editableHost == null && !insidePage(node)) {
                // Tier 4 candidate: a label in the browser's own chrome. Collected rather than
                // returned — soleHost decides, and it refuses when they disagree.
                accept(node)?.takeIf { looksLikeHost(it) }?.let { chromeLabels.add(it) }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
    }
    return OmniboxRead(
        looseAddress(blankBarSeen, startPageSeen, editableHost, chromeLabels),
        blankBarSeen, startPageSeen,
    )
}

/**
 * Whether [node] is part of the rendered page rather than the browser's own chrome.
 *
 * Chromium exposes page content beneath an `android.webkit.WebView` node, so an ancestor of that
 * class is what separates a link *on the page* from a label in the toolbar — structurally, without
 * knowing any vendor's view ids. That is the substitute for tier 3's editability rule, and it is
 * the part that stops the URL-shaped fallback from covering a page that merely links to a blocked
 * site (the over-block the address-only rule exists to prevent).
 *
 * Bounded to [MAX_ANCESTORS] hops: a toolbar sits a handful of levels down, page content sits far
 * deeper, and an unbounded walk up a churning tree is exactly the kind of per-node cost this file
 * caps everywhere else. **Running out of hops answers "inside the page"** — the refusing
 * direction, so a node too deep to classify is never treated as an address.
 */
private fun insidePage(node: AccessibilityNodeInfo): Boolean {
    var current: AccessibilityNodeInfo? = node
    var hops = 0
    while (current != null) {
        if (hops++ > MAX_ANCESTORS) return true
        val cn = runCatching { current?.className?.toString() }.getOrNull().orEmpty()
        if (cn.contains("WebView", ignoreCase = true)) return true
        current = runCatching { current?.parent }.getOrNull()
    }
    return false
}

/**
 * The address, when the non-editable candidates agree on exactly one host — otherwise nothing.
 *
 * **This is the safety rule for tier 4**, and it replaces the argument tier 3 relies on. Tier 3 is
 * safe because it only accepts an *editable* node: "a page's links are not editable; a browser's
 * address bar is the only editable field in its chrome". True of Chrome, and false of Mi Browser,
 * which shows its address in a bottom bar that is a label — you tap it to open a separate editor.
 * So the site layer was silently off there, and the owner found instagram.com opening freely with
 * Instagram blocked.
 *
 * Dropping the editable requirement means the toolbar's label qualifies — and so would a
 * suggestion list, a bookmarks panel or a "recently closed" row, any of which would raise a cover
 * over a page the user is not on. That is the visible, painful failure this project keeps paying
 * for, so it is bounded structurally rather than by hoping: a toolbar shows the current address
 * **once**, while every one of those lists shows several. Disagreement answers null, which leaves
 * exactly today's behaviour.
 *
 * Same shape as the rest of this file: a failed identification is a failed measurement, never a
 * confident answer (docs/BLOCKING_INVARIANTS.md, invariant 4).
 */
internal fun soleHost(candidates: List<String>): String? {
    val hostShaped = candidates.mapNotNull { c ->
        c.trim().lowercase().takeIf { it.isNotBlank() && looksLikeHost(it) }
    }
    // Grouped by HOST, not by the raw string: a toolbar that shows "instagram.com" while a chip
    // beside it shows "https://instagram.com/" is one address twice, not two candidates, and
    // treating it as disagreement would throw away the reading we came for. The full text is
    // what's returned — WebContentFilter does its own host extraction and the caller's
    // `rememberedUrl` is compared as text.
    val byHost = hostShaped.groupBy { it.substringAfter("://").substringBefore('/') }
    return byHost.values.singleOrNull()?.first()
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
 * What the user actually typed into a field, given its full [text] and the selection the node
 * reports.
 *
 * **Chrome finishes the address for you, out of your own history.** Type `yo` into the omnibox
 * and the node's text is already `youtube.com`, with `utube.com` selected. That completed half
 * is the phone's guess about where he *might* be going — not a page he opened. Read whole, it
 * made the site layer answer *"which site is this?"* with a site he had never visited, and the
 * cover came up mid-search over a link he never followed (reported 21 Aug 2026).
 *
 * That is invariant 20 one step further in. The start-page fix took away the tiles, the
 * suggestion list and the feed — all of it *what the phone shows him about where he has already
 * been* — and this is the same history, sitting inside the one node that fix still trusted
 * completely.
 *
 * **The autocomplete shape is specific, and everything else is left exactly alone:**
 *  - [selStart] `> 0` — a completion requires something typed in front of it. A selection
 *    starting at 0 is a *select-all*, which is what Chrome does when you tap the bar on a loaded
 *    page: that text is the real address and must go on matching.
 *  - a non-empty selection ending no later than the end of [text].
 *
 * Anything else — no selection (`-1`), a collapsed cursor, indices that don't make sense, a
 * browser that reports none of this — returns [text] unchanged. That is the blocking direction:
 * a failed measurement is never permission (invariant 4), so the worst case here is today's
 * behaviour, never a bypass.
 */
internal fun typedPortion(text: String, selStart: Int, selEnd: Int): String {
    if (selStart <= 0 || selEnd <= selStart || selEnd > text.length) return text
    return text.substring(0, selStart)
}

/**
 * The text-shaped tiers' answer — or nothing, when a tier that could *name* the address bar has
 * already established that there is no page.
 *
 * Invariant 20 settled who may **answer** [BrowserAddress.Blank]: only tiers 1 and 2, which find
 * the bar by view id and can therefore see that it is there and empty. It left the other half of
 * the question open, and the gap survived the fix — nothing stopped tiers 3 and 4 from
 * **overriding** a blank one. The walk carried on after `blankBarSeen` was set, and one
 * host-shaped label anywhere in the browser's chrome — a bookmark row, a "recently closed"
 * entry, a most-visited tile showing a host — came back as the address. The start page went
 * straight back in front of the site layer through a different door.
 *
 * So the rule runs both ways now: **a bar we could name outranks one we merely recognised by its
 * shape** — when it holds an address (tiers 1-2 return early on that) and equally when it holds
 * nothing. Same tier discipline as invariant 12. What it costs when it refuses is the
 * pre-tier-3 behaviour on a start page, which is nothing at all, and a start page is not a page.
 *
 * This is also what carries the fix into the undebounced path: [extractSettledBrowserUrl] reads
 * only the text and throws the blank/start-page answers away, so returning null here is what
 * makes its `?: return` correct — without the fast path having to learn about [BrowserAddress].
 */
internal fun looseAddress(
    blankBar: Boolean,
    startPage: Boolean,
    editableHost: String?,
    chromeLabels: List<String>,
): String? {
    if (blankBar || startPage) return null
    return editableHost ?: soleHost(chromeLabels)
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
