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
 *
 * ⚠️ **Every app event here names its screen, because a stop belongs to a screen.** The first
 * version of this file had no way to say which screen an event came from, so a walker that ended an
 * app's stretch when ANY of its screens stopped passed every test in it — while counting 8 of 23
 * real seconds in Settings.
 */
class UsageWalkTest {

    private val minute = 60_000L
    private val second = 1_000L
    private val resumed = UsageEvents.Event.ACTIVITY_RESUMED
    private val paused = UsageEvents.Event.ACTIVITY_PAUSED
    private val stopped = UsageEvents.Event.ACTIVITY_STOPPED

    private class Ev(val type: Int, val pkg: String, val at: Long, val screen: String?)

    /** An event from one of an app's screens, [atMin] minutes in. */
    private fun ev(type: Int, pkg: String, atMin: Long, screen: String = "Main") =
        Ev(type, pkg, atMin * minute, screen)

    /** A device-wide event: Android files these under "android", with no screen. */
    private fun device(type: Int, atMin: Long) = Ev(type, "android", atMin * minute, null)

    private fun walkMs(start: Long, end: Long, events: List<Ev>): List<UsageTracker.Session> {
        val w = UsageTracker.StretchWalker(start, resumed, paused)
        events.forEach { w.onEvent(it.type, it.pkg, it.screen, it.at) }
        return w.finish(end)
    }

    private fun walk(startMin: Long, endMin: Long, vararg events: Ev): List<UsageTracker.Session> =
        walkMs(startMin * minute, endMin * minute, events.toList())

    private fun minutes(sessions: List<UsageTracker.Session>, pkg: String? = null): Long =
        sessions.filter { pkg == null || it.pkg == pkg }.sumOf { it.to - it.from } / minute

    // ---- use ends when use ends -------------------------------------------------------------------

    /** The case this exists for: without the closer this reads 360 minutes. */
    @Test fun `an app left in front when the screen went dark stops counting there`() {
        val s = walk(0, 360, ev(resumed, "video", 0), device(UsageEvents.Event.SCREEN_NON_INTERACTIVE, 10))
        assertEquals(10L, minutes(s))
    }

    @Test fun `the keyguard coming up ends use`() {
        val s = walk(0, 100, ev(resumed, "chat", 0), device(UsageEvents.Event.KEYGUARD_SHOWN, 7))
        assertEquals(7L, minutes(s))
    }

    @Test fun `a stop with no pause before it still ends the stretch`() {
        val s = walk(0, 60, ev(resumed, "game", 0), ev(stopped, "game", 5))
        assertEquals(5L, minutes(s))
    }

    @Test fun `a shutdown closes every open stretch at once`() {
        val s = walk(
            0, 300,
            ev(resumed, "a", 0), ev(resumed, "b", 1), device(UsageEvents.Event.DEVICE_SHUTDOWN, 20),
        )
        assertEquals(20L, minutes(s, "a"))
        assertEquals(19L, minutes(s, "b"))
    }

    // ---- a stop belongs to a screen, not to an app (15 Sep 2026) ----------------------------------

    /**
     * The order Android really writes: the old screen pauses, the new one resumes, and only THEN does
     * the old one stop. Matched to the app, that stop ended the new screen's stretch — this read 10.
     */
    @Test fun `moving to another screen of the same app does not end the stretch`() {
        val s = walk(
            0, 60,
            ev(resumed, "app", 0, "Home"), ev(paused, "app", 10, "Home"),
            ev(resumed, "app", 10, "Detail"), ev(stopped, "app", 10, "Home"),
            ev(paused, "app", 40, "Detail"),
        )
        assertEquals(40L, minutes(s))
    }

    @Test fun `going back does not end the stretch at the stop of the screen that was left`() {
        val s = walk(
            0, 90,
            ev(resumed, "app", 0, "Home"), ev(paused, "app", 5, "Home"),
            ev(resumed, "app", 5, "Detail"), ev(stopped, "app", 5, "Home"),
            ev(paused, "app", 20, "Detail"), ev(resumed, "app", 20, "Home"),
            ev(stopped, "app", 20, "Detail"), ev(paused, "app", 60, "Home"),
        )
        assertEquals(60L, minutes(s))
    }

    /** A second copy of one screen in front while the first copy stops — same class, both times. */
    @Test fun `a second copy of the same screen is not ended by the first copy stopping`() {
        val s = walk(
            0, 60,
            ev(resumed, "app", 0, "Page"), ev(paused, "app", 10, "Page"),
            ev(resumed, "app", 10, "Page"), ev(stopped, "app", 11, "Page"),
            ev(paused, "app", 30, "Page"),
        )
        assertEquals(30L, minutes(s))
    }

    /** The screen that was left paused just before the window began, so only its stop falls inside. */
    @Test fun `a stop whose pause fell before the window does not end another screen's stretch`() {
        val s = walk(
            0, 60,
            ev(resumed, "app", 0, "Detail"), ev(stopped, "app", 1, "Home"),
            ev(paused, "app", 30, "Detail"),
        )
        assertEquals(30L, minutes(s))
    }

    /** The count of stops still to come is spent as they arrive, not kept for ever: a screen that
     *  once stopped properly can later be ended by a stop that had no pause. */
    @Test fun `a screen that once stopped properly can later be ended by a stop without a pause`() {
        val s = walk(
            0, 60,
            ev(resumed, "app", 0), ev(paused, "app", 5), ev(stopped, "app", 6),
            ev(resumed, "app", 10), ev(stopped, "app", 15),
        )
        assertEquals(10L, minutes(s))
    }

    /**
     * **Recorded, not invented.** Settings on the API 35 emulator, 15 Sep 2026, copied from `dumpsys
     * usagestats` in the order Android wrote it (seconds from the first event): the home screen, the
     * Display screen, back, then App info twice. App info passes through a trampoline screen, and the
     * second App info is a second copy of the same `SpaActivity` — whose first copy stops while the
     * second is in front. Settings was in front for 23 seconds; the walker that matched stops to the
     * app counted 8.
     */
    @Test fun `a real run through Settings counts every second it was in front`() {
        val settings = "com.android.settings"
        val launcher = "com.google.android.apps.nexuslauncher"
        val home = "com.android.settings.homepage.SettingsHomepageActivity"
        val display = "com.android.settings.Settings\$DisplaySettingsActivity"
        val trampoline = "com.android.settings.applications.InstalledAppDetailsTop"
        val spa = "com.android.settings.spa.SpaActivity"
        val nexus = "com.google.android.apps.nexuslauncher.NexusLauncherActivity"
        fun at(sec: Long, type: Int, pkg: String, screen: String) = Ev(type, pkg, sec * second, screen)
        val recorded = listOf(
            at(0, paused, launcher, nexus),
            at(0, resumed, settings, home),
            at(2, stopped, launcher, nexus),
            at(6, paused, settings, home),
            at(6, resumed, settings, display),
            at(6, stopped, settings, home),
            at(10, paused, settings, display),
            at(10, resumed, settings, home),
            at(10, stopped, settings, display),
            at(14, paused, settings, home),
            at(14, resumed, settings, trampoline),
            at(15, paused, settings, trampoline),
            at(15, resumed, settings, spa),
            at(15, stopped, settings, trampoline),
            at(15, stopped, settings, home),
            at(19, paused, settings, spa),
            at(19, resumed, settings, trampoline),
            at(19, paused, settings, trampoline),
            at(19, resumed, settings, spa),
            at(20, stopped, settings, trampoline),
            at(20, stopped, settings, spa),
            at(23, paused, settings, spa),
            at(23, resumed, launcher, nexus),
            at(24, stopped, settings, spa),
        )
        val s = walkMs(0L, 30 * second, recorded)
        assertEquals(23L, s.filter { it.pkg == settings }.sumOf { it.to - it.from } / second)
    }

    // ---- nothing that was right before changes -------------------------------------------------

    @Test fun `an ordinary run of pauses is counted exactly as before`() {
        val s = walk(
            0, 120,
            ev(resumed, "a", 0), ev(paused, "a", 10), ev(resumed, "b", 10), ev(paused, "b", 30),
            device(UsageEvents.Event.SCREEN_NON_INTERACTIVE, 31),
        )
        assertEquals(10L, minutes(s, "a"))
        assertEquals(20L, minutes(s, "b"))
        assertEquals(2, s.size)
    }

    @Test fun `a pause arriving after the screen went dark adds nothing`() {
        val s = walk(
            0, 120,
            ev(resumed, "a", 0), device(UsageEvents.Event.SCREEN_NON_INTERACTIVE, 10), ev(paused, "a", 11),
        )
        assertEquals(10L, minutes(s))
        assertEquals(1, s.size)
    }

    @Test fun `the screen coming back on does not open a stretch by itself`() {
        val s = walk(0, 60, device(UsageEvents.Event.SCREEN_INTERACTIVE, 5), ev(resumed, "a", 6))
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
        w.onEvent(UsageEvents.Event.SCREEN_INTERACTIVE, "android", null, minute)
        assertTrue(w.sawAnyEvent)
        assertTrue(w.finish(60 * minute).isEmpty())
    }
}
