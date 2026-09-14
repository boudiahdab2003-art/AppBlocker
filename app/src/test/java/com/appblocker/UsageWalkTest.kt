package com.appblocker

import android.app.usage.UsageEvents
import com.appblocker.service.UsageTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **When a stretch of use ends** (invariant 75).
 *
 * `used=` in the stoppage log, the stalled detector's minutes of use, the screen-time headline and
 * daily limits all come out of this walk. It used to end a stretch only on a pause, so an app whose
 * pause never arrived counted as in use until the window closed — and on 14 Sep 2026 a six-hour
 * outage reported 323 minutes of use that nothing could cross-check.
 */
class UsageWalkTest {

    private val minute = 60_000L
    private val resumed = UsageEvents.Event.ACTIVITY_RESUMED
    private val paused = UsageEvents.Event.ACTIVITY_PAUSED

    private fun ev(type: Int, pkg: String, atMin: Long) = Triple(type, pkg, atMin * minute)

    private fun walk(
        startMin: Long,
        endMin: Long,
        vararg events: Triple<Int, String, Long>,
    ): List<UsageTracker.Session> {
        val w = UsageTracker.StretchWalker(startMin * minute, resumed, paused)
        events.forEach { (type, pkg, at) -> w.onEvent(type, pkg, at) }
        return w.finish(endMin * minute)
    }

    private fun minutes(sessions: List<UsageTracker.Session>, pkg: String? = null): Long =
        sessions.filter { pkg == null || it.pkg == pkg }.sumOf { it.to - it.from } / minute

    // ---- use ends when use ends -------------------------------------------------------------------

    /** The case this exists for: without the closer this reads 360 minutes. */
    @Test fun `an app left in front when the screen went dark stops counting there`() {
        val s = walk(0, 360, ev(resumed, "video", 0), ev(UsageEvents.Event.SCREEN_NON_INTERACTIVE, "android", 10))
        assertEquals(10L, minutes(s))
    }

    @Test fun `the keyguard coming up ends use`() {
        val s = walk(0, 100, ev(resumed, "chat", 0), ev(UsageEvents.Event.KEYGUARD_SHOWN, "android", 7))
        assertEquals(7L, minutes(s))
    }

    @Test fun `a stop with no pause before it still ends the stretch`() {
        val s = walk(0, 60, ev(resumed, "game", 0), ev(UsageEvents.Event.ACTIVITY_STOPPED, "game", 5))
        assertEquals(5L, minutes(s))
    }

    @Test fun `a shutdown closes every open stretch at once`() {
        val s = walk(
            0, 300,
            ev(resumed, "a", 0), ev(resumed, "b", 1), ev(UsageEvents.Event.DEVICE_SHUTDOWN, "android", 20),
        )
        assertEquals(20L, minutes(s, "a"))
        assertEquals(19L, minutes(s, "b"))
    }

    // ---- nothing that was right before changes -------------------------------------------------

    @Test fun `an ordinary run of pauses is counted exactly as before`() {
        val s = walk(
            0, 120,
            ev(resumed, "a", 0), ev(paused, "a", 10), ev(resumed, "b", 10), ev(paused, "b", 30),
            ev(UsageEvents.Event.SCREEN_NON_INTERACTIVE, "android", 31),
        )
        assertEquals(10L, minutes(s, "a"))
        assertEquals(20L, minutes(s, "b"))
        assertEquals(2, s.size)
    }

    @Test fun `a pause arriving after the screen went dark adds nothing`() {
        val s = walk(
            0, 120,
            ev(resumed, "a", 0), ev(UsageEvents.Event.SCREEN_NON_INTERACTIVE, "android", 10), ev(paused, "a", 11),
        )
        assertEquals(10L, minutes(s))
        assertEquals(1, s.size)
    }

    @Test fun `the screen coming back on does not open a stretch by itself`() {
        val s = walk(0, 60, ev(UsageEvents.Event.SCREEN_INTERACTIVE, "android", 5), ev(resumed, "a", 6))
        assertEquals(54L, minutes(s))
    }

    @Test fun `a stretch still open when the window closes counts to the end`() {
        // The app in front at the moment the reading is taken.
        assertEquals(10L, minutes(walk(0, 60, ev(resumed, "a", 50))))
    }

    @Test fun `a stretch that began before the window counts from the window's start`() {
        assertEquals(10L, minutes(walk(0, 60, ev(resumed, "a", -30), ev(paused, "a", 10))))
    }

    @Test fun `device events alone still count as something having been read`() {
        val w = UsageTracker.StretchWalker(0L, resumed, paused)
        w.onEvent(UsageEvents.Event.SCREEN_INTERACTIVE, "android", minute)
        assertTrue(w.sawAnyEvent)
        assertTrue(w.finish(60 * minute).isEmpty())
    }
}
