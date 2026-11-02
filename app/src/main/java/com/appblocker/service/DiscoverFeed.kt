package com.appblocker.service

/**
 * **Google's news feed (Discover)** — blocked always, wherever it is drawn. His ask, 2 Nov 2026:
 * *"it would be great also if you can block google news"*, then "Always", then he showed the page:
 * the feed one swipe right of the home screen. Invariant 101.
 *
 * ## What it is recognised by, measured on his phone (2 Nov 2026, HyperOS, main space)
 *
 * - The Google News app is NOT installed in either space; "Google News" is the Discover feed.
 * - The feed is a window the launcher hosts (`GoogleDiscoverWindow`, owner `com.miui.home`), but
 *   its nodes belong to the Google app: `com.google.android.googlequicksearchbox`, with the list's
 *   view id **`googleapp_discover_recycler_view`** (`uiautomator dump`).
 * - Swiping it open sends `TYPE_WINDOW_STATE_CHANGED` from the Google app (class `FrameLayout`), so
 *   the watcher's front package becomes the Google app; scrolling sends content and scroll events
 *   from it (`uiautomator events`).
 * - **HOME from the feed lands on the home screen** (`com.miui.home…Launcher` window-state event,
 *   then the page indicator) — so the exit is the settings guard's: cover, HOME at once, and a
 *   safety net that takes the cover down.
 *
 * ⚠️ The same list id is the Google app's own home feed, so that is covered too — it is the same
 * news. Search, Lens and Gemini (also inside the Google app) do not draw it and keep working.
 * ⚠️ Only a node that is **visible to the user** counts: the launcher keeps the feed window alive
 * off screen, and a hidden list must never bounce the home screen.
 */
internal object DiscoverFeed {

    /** The counter key and the log's why. */
    const val KEY = "discover"

    /** The Google app — where the feed's nodes and events come from. */
    const val PACKAGE = "com.google.android.googlequicksearchbox"

    /** The feed list's view id, as read on his phone. */
    private const val LIST_ID = "googleapp_discover_recycler_view"

    /** Gap kept between two bounces, so one feed opening is one HOME however many events follow. */
    const val BOUNCE_GAP_MS = 1_500L

    /** Whether [viewId] is the feed's list. */
    fun isFeedList(viewId: String?): Boolean = viewId != null && viewId.endsWith(":id/$LIST_ID")

    /** Whether to bounce: the list is on screen, and no bounce went out in the last [BOUNCE_GAP_MS]. */
    fun shouldBounce(listVisible: Boolean, sinceLastBounceMs: Long): Boolean =
        listVisible && sinceLastBounceMs >= BOUNCE_GAP_MS
}
