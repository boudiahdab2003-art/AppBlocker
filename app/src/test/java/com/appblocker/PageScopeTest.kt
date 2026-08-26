package com.appblocker

import com.appblocker.service.PageScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 4's "is this the page or the browser's own furniture?" rule — the only way Mi Browser's
 * address is ever read, and until now the most expensive thing in a scan: it was answered by
 * climbing up to thirteen parents per candidate node, each one a lookup into the browser.
 *
 * The walk descends from the window root, so the answer can travel down with the node instead.
 * These tests pin the two halves of that: it still classifies the same way, and it no longer
 * depends on how deep the node sits — which the thirteen-hop cap did, in the refusing direction.
 */
class PageScopeTest {

    private val webView = "android.webkit.WebView"

    @Test
    fun `a label in the browser's own chrome is not the page`() {
        assertFalse(
            PageScope.insideAt(
                listOf("android.widget.FrameLayout", "android.widget.LinearLayout", "android.widget.TextView"),
            ),
        )
    }

    @Test
    fun `anything under the WebView is the page`() {
        assertTrue(PageScope.insideAt(listOf("android.widget.FrameLayout", webView, "android.widget.TextView")))
    }

    @Test
    fun `the WebView itself counts as the page`() {
        // The old climb started at the node itself, not its parent. Same answer here.
        assertTrue(PageScope.insideAt(listOf("android.widget.FrameLayout", webView)))
    }

    @Test
    fun `a chrome label stays chrome however deep it sits`() {
        // THE behaviour change. The old rule gave up after thirteen hops and answered "page",
        // so a toolbar label nested deeper than that was discarded and its browser read as
        // unreadable — a silent under-block, in the one tier Mi Browser depends on.
        val deep = List(40) { "android.widget.FrameLayout" } + "android.widget.TextView"
        assertFalse(PageScope.insideAt(deep))
    }

    @Test
    fun `page content stays page however deep it sits`() {
        val deep = listOf("android.widget.FrameLayout", webView) + List(40) { "android.view.View" }
        assertTrue(PageScope.insideAt(deep))
    }

    @Test
    fun `a chrome-looking class under the WebView is still the page`() {
        // Once inside, always inside: the page can draw anything, including a node whose class
        // looks like furniture. Descending keeps that, because the flag only ever turns on.
        assertTrue(PageScope.insideAt(listOf(webView, "android.widget.LinearLayout", "android.widget.TextView")))
    }

    @Test
    fun `the class name is matched however it is capitalised`() {
        assertTrue(PageScope.insideAt(listOf("org.mozilla.gecko.WEBVIEW")))
        assertTrue(PageScope.insideAt(listOf("com.example.webview")))
    }

    @Test
    fun `a node that cannot report its class does not change the answer`() {
        // runCatching hands us null when the node has been recycled mid-churn. That must not
        // read as "the page" (it would discard a real address bar) nor cancel a WebView above it.
        assertFalse(PageScope.insideAt(listOf("android.widget.FrameLayout", null)))
        assertTrue(PageScope.insideAt(listOf(webView, null, "android.widget.TextView")))
    }

    @Test
    fun `each step only needs its parent's answer`() {
        // The property the walk relies on: the fold above is just descend, applied per node.
        assertFalse(PageScope.descend(parentInside = false, className = "android.widget.TextView"))
        assertTrue(PageScope.descend(parentInside = true, className = "android.widget.TextView"))
        assertTrue(PageScope.descend(parentInside = false, className = webView))
    }
}
