package com.appblocker

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblocker.service.ProtectionNotifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    }

    @After fun tidy() = ProtectionNotifier.cancel(context)

    private fun stalledAlert(): Notification? = manager.activeNotifications
        .firstOrNull { it.notification.extras.getString(Notification.EXTRA_TITLE) == TITLE }
        ?.notification

    @Test
    fun theAlertIsPostedAndCannotBeSwipedAway() {
        ProtectionNotifier.createChannel(context)
        ProtectionNotifier.notifyStalled(context)

        val alert = stalledAlert()
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
        assertNotNull(stalledAlert())

        ProtectionNotifier.cancel(context)

        assertNull("cancel() must clear the stalled alert's own id", stalledAlert())
    }

    private companion object {
        const val TITLE = "Blocking has stopped"
    }
}
