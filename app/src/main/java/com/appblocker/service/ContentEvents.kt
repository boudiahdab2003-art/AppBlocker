package com.appblocker.service

/**
 * **What a content event may cost** (invariant 86) — content changed, text changed or scrolled.
 *
 * These are the most frequent thing the watcher is ever sent, and most of them are not about the
 * app in front at all: the status-bar clock and network meter, a notification sliding in, the
 * keyboard redrawing its suggestions, a widget or a picture-in-picture video repainting. Each of
 * those used to cost two things for nothing:
 *
 *  - **a window lookup** (`rootInActiveWindow`, a cross-process call that the framework answers
 *    by prefetching nodes from the app in front) to ask whether that package had just come to the
 *    front — for the status bar and the keyboard, whose answer `onForegroundChanged` throws away
 *    on its first line (`isTransientSurface`);
 *  - **a full re-read of the app in front** — the address bar and the page text, up to four
 *    hundred nodes — although nothing in it had changed. With "check every app" on, that was a
 *    page walk after almost every status-bar tick while he read something static.
 *
 * A screen cannot change without an event from its own package, so a re-read prompted by someone
 * else's event can only find what the last read found — the rule invariant 56's blind scans were
 * built on. The app in front's own events still re-read it exactly as before, and a different
 * package that might be a new app arriving ahead of its window event is still asked about.
 *
 * Plain values in, decision out, like [CoverGate]: the service keeps the live state.
 */
internal object ContentEvents {

    enum class Route {
        /** The app in front changed (or the event names no package, as before): run the guard and
         *  re-read it — address bar, page text, Shorts. */
        RESCAN_FRONT,

        /** Another package, possibly a new app whose content beat its own window event: ask the
         *  window, and treat it as a foreground change if it really is in front. Otherwise it is
         *  [ELSEWHERE]. */
        CONFIRM_SWITCH,

        /** Something other than the app in front changed. Only the settings guard and the Shorts
         *  scan's own clean-up run; the app in front is not re-read. */
        ELSEWHERE,
    }

    /**
     * [isTransientSurface] is asked only for another package's events, so the front app's own
     * events — the overwhelming majority — never pay for it.
     */
    fun route(eventPkg: String?, frontPkg: String?, isTransientSurface: (String) -> Boolean): Route =
        when {
            eventPkg == null || eventPkg == frontPkg -> Route.RESCAN_FRONT
            isTransientSurface(eventPkg) -> Route.ELSEWHERE
            else -> Route.CONFIRM_SWITCH
        }
}
