package com.appblocker

import com.appblocker.service.pkgToRedecide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which app to look at again once the rules have arrived after a rebind.
 *
 * The window is real and already counted (`SilenceLog.UNREADY_DECISIONS`): every boot, update,
 * Second Space switch and revive spends a moment with no rules. `blockedSnapshot` carries HARD
 * blocks through it; a schedule or a daily limit is not in that snapshot, and nothing used to
 * re-ask once Room finally spoke.
 */
class RedecideTest {

    private val OWN = "com.appblocker"

    @Test
    fun `what is on screen beats what we remember`() {
        assertEquals(
            "com.instagram.android",
            pkgToRedecide(
                cached = "com.android.chrome",
                actual = "com.instagram.android",
                own = OWN,
                actualIsTransient = false,
            ),
        )
    }

    /**
     * The case that matters most: the service was revived underneath an app that never produced a
     * fresh window-state event, so the cache is empty and the screen is the only witness.
     */
    @Test
    fun `an empty cache is answered by the screen`() {
        assertEquals(
            "com.instagram.android",
            pkgToRedecide(null, "com.instagram.android", OWN, actualIsTransient = false),
        )
    }

    /** Our own cover is a window too, and it can report as the active one. Never adopt it. */
    @Test
    fun `our own window is never adopted`() {
        assertEquals(
            "com.instagram.android",
            pkgToRedecide("com.instagram.android", OWN, OWN, actualIsTransient = false),
        )
    }

    /**
     * A shade, keyboard or volume dialog sits OVER an app and says nothing about which app it is —
     * the rule the re-check tick learned the hard way. Fall back to the cache instead.
     */
    @Test
    fun `a transient surface is not evidence`() {
        assertEquals(
            "com.instagram.android",
            pkgToRedecide("com.instagram.android", "com.android.systemui", OWN, true),
        )
    }

    /** An unreadable root is not an answer either (invariant 11) — the cache is all there is. */
    @Test
    fun `an unreadable root falls back to the cache`() {
        assertEquals(
            "com.instagram.android",
            pkgToRedecide("com.instagram.android", null, OWN, actualIsTransient = false),
        )
    }

    /** Nothing to act on: answering null is always safe, because the next event decides normally. */
    @Test
    fun `nothing known means nothing to do`() {
        assertNull(pkgToRedecide(null, null, OWN, actualIsTransient = false))
        assertNull(pkgToRedecide(null, OWN, OWN, actualIsTransient = false))
        assertNull(pkgToRedecide(OWN, null, OWN, actualIsTransient = false))
    }

    /** Sitting in our own app with the shade pulled down is still nothing to act on. */
    @Test
    fun `our own app under a transient surface is not blocked`() {
        assertNull(pkgToRedecide(OWN, "com.android.systemui", OWN, actualIsTransient = true))
    }
}
