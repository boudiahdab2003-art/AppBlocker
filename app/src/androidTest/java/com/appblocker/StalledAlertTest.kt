package com.appblocker

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblocker.service.ProtectionNotifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **"Blocking has stopped" must be an alert you cannot lose.**
 *
 * The old version of this alert was dismissible and shared the four-hour re-notify throttle with
 * every other protection notification. So swiping it away — which is what anyone does with a
 * notification — bought four hours of silence while the phone was blocking absolutely nothing.
 * For the failure it reports (switched on in Settings, not actually running: a Second Space
 * switch, an OEM battery manager) that is the worst possible trade.
 *
 * These assertions are about *flags on a real posted notification*, which is why they run on a
 * device: `setOngoing`/`setAutoCancel` are exactly the kind of builder call that can be quietly
 * dropped in a refactor with nothing to notice, and their effect only exists once the system has
 * the notification.
 */
@RunWith(AndroidJUnit4::class)
class StalledAlertTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    /**
     * Grants POST_NOTIFICATIONS (Android 13+ refuses to post without it, and the notifier bails
     * early rather than throwing — which would make these tests fail for the wrong reason), and
     * resets the notifier's "already posted" guard so each test posts for real.
     */
    @Before
    fun clear() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName, "android.permission.POST_NOTIFICATIONS",
        )
        ProtectionNotifier.cancel(context)
        awaitNotificationsEnabled()
    }

    @After fun tidy() = ProtectionNotifier.cancel(context)

    private fun stalledAlert(): Notification? = manager.activeNotifications
        .firstOrNull { it.notification.extras.getString(Notification.EXTRA_TITLE) == TITLE }
        ?.notification

    /**
     * **These assertions are about a system that answers asynchronously, and they were being made
     * synchronously.** Both tests failed in the release gate on 21 Aug 2026, on a commit whose
     * identical suite had passed ten minutes earlier in the Build check — same code, same emulator
     * image, opposite results. That is the shape of a race, and there are two of them here.
     *
     * Nothing below weakens what is asserted: the alert must still be posted, ongoing, not
     * auto-cancelling, and removable by `cancel`. What changes is that each is checked once the
     * state it is about has actually landed.
     */
    private fun awaitNotificationsEnabled(timeoutMs: Long = 5_000L) {
        // The FIRST race, and the one that fails hardest. `notifyStalled` bails early and silently
        // when notifications are off (deliberately — it must never throw from a watchdog), so if
        // the runtime grant above has not reached the app process yet, nothing is posted at all
        // and no amount of waiting afterwards will find it. `grantRuntimePermission` returns as
        // soon as the system has recorded the grant, not as soon as this process can see it, so
        // the thing to wait on is exactly what the notifier itself checks.
        val compat = NotificationManagerCompat.from(context)
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!compat.areNotificationsEnabled() && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50)
        }
        assertTrue(
            "POST_NOTIFICATIONS never reached this process, so notifyStalled would post nothing " +
                "and every assertion below would blame the wrong thing",
            compat.areNotificationsEnabled(),
        )
    }

    /**
     * The alert once the system has caught up, or null if it never does.
     *
     * The SECOND race: `notify()` and `cancel()` are one-way calls into the system, while
     * `activeNotifications` is a query answered by it. Reading once, immediately after posting,
     * asserts that the system got there first — usually true, and on a loaded CI machine not
     * always. Polling asks the same question, just not before there can be an answer.
     */
    private fun awaitAlert(timeoutMs: Long = 5_000L, wanted: (Notification?) -> Boolean): Notification? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var seen = stalledAlert()
        while (!wanted(seen) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50)
            seen = stalledAlert()
        }
        return seen
    }

    @Test
    fun theAlertIsPostedAndCannotBeSwipedAway() {
        ProtectionNotifier.createChannel(context)
        ProtectionNotifier.notifyStalled(context)

        val alert = awaitAlert { it != null }
        assertNotNull("the stalled alert was not posted at all", alert)
        assertEquals(
            "the alert must be ongoing — a swipe must not buy hours of silent non-blocking",
            Notification.FLAG_ONGOING_EVENT,
            alert!!.flags and Notification.FLAG_ONGOING_EVENT,
        )
        assertEquals(
            "the alert must not auto-cancel on tap: opening the repair screen does not fix it, " +
                "and the state is still true afterwards",
            0,
            alert.flags and Notification.FLAG_AUTO_CANCEL,
        )
    }

    /**
     * And it has to be removable by the one thing that means it is over. `cancel` is called from
     * the watchdog's OK branch; if it missed this id the alert would sit there for ever, claiming
     * blocking is broken after it was fixed — which would teach the owner to ignore it.
     */
    @Test
    fun aHealthyCheckTakesItDownAgain() {
        ProtectionNotifier.createChannel(context)
        ProtectionNotifier.notifyStalled(context)
        assertNotNull("the stalled alert was not posted at all", awaitAlert { it != null })

        ProtectionNotifier.cancel(context)

        assertNull(
            "cancel() must clear the stalled alert's own id",
            awaitAlert { it == null },
        )
    }

    private companion object {
        const val TITLE = "Blocking has stopped"
    }
}
