package com.appblocker

import com.appblocker.service.ContentEvents
import com.appblocker.service.ContentEvents.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Invariant 86: a content event re-reads the app in front only when it comes from the app in front.
 * Everything else — the status bar, the keyboard, a notification — used to cost a window lookup and
 * a full page walk of a screen that had not changed.
 */
class ContentEventsTest {

    private val front = "com.android.chrome"
    private val transient = setOf("com.android.systemui", "com.google.android.inputmethod.latin")

    private fun route(eventPkg: String?, frontPkg: String? = front) =
        ContentEvents.route(eventPkg, frontPkg) { it in transient }

    @Test
    fun `the app in front changing is re-read`() {
        assertEquals(Route.RESCAN_FRONT, route(front))
    }

    @Test
    fun `an event naming no package re-reads the front, as it always did`() {
        assertEquals(Route.RESCAN_FRONT, route(null))
    }

    @Test
    fun `the status bar ticking does not re-read the app in front`() {
        assertEquals(Route.ELSEWHERE, route("com.android.systemui"))
    }

    @Test
    fun `the keyboard redrawing does not re-read the app in front`() {
        assertEquals(Route.ELSEWHERE, route("com.google.android.inputmethod.latin"))
    }

    @Test
    fun `another app may be a new app arriving, so the window is still asked`() {
        // A NEW app's content events usually beat its window-state event; the fast path that
        // covers it sooner must survive this change untouched.
        assertEquals(Route.CONFIRM_SWITCH, route("com.instagram.android"))
    }

    @Test
    fun `with nothing known in front yet, any app is asked about`() {
        assertEquals(Route.CONFIRM_SWITCH, route("com.instagram.android", frontPkg = null))
    }

    @Test
    fun `the front app's own events never pay for the transient-surface lookup`() {
        // isTransientSurface can read Settings.Secure for the keyboard; the front app's own events
        // are nearly all of them and must not reach it.
        ContentEvents.route(front, front) { fail("asked about $it"); false }
        ContentEvents.route(null, front) { fail("asked about $it"); false }
    }
}
