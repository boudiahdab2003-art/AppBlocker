package com.appblocker

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.appblocker.data.SetupGuides
import com.appblocker.service.SetupStepsNotifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The steps that follow the user into Settings.
 *
 * Once "Turn on blocking" is pressed, the wizard is behind them and every picture on it is out of
 * reach — this notification is the only thing still carrying the instructions. Two properties are
 * worth pinning, and the second is the one that turns a helper into a nuisance if it breaks:
 * it says the actual steps, and **it goes away again**.
 */
@RunWith(AndroidJUnit4::class)
class SetupStepsNotifierTest {

    /**
     * **Without this the whole class quietly skips in CI**, which is the one place it needs to
     * run. `connectedAndroidTest` installs without runtime permissions, so
     * `areNotificationsEnabled()` is false there and every assertion below is assumed away —
     * it went green on a hand-granted emulator and tested nothing on the release gate.
     */
    @get:Rule
    val notificationPermission: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            GrantPermissionRule.grant()
        }

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private val guide = SetupGuides.forPermission("accessibility", "")!!

    private fun ours() = manager.activeNotifications.filter { it.id == NOTIFICATION_ID }

    /**
     * Posting and cancelling both cross into NotificationManagerService, so `activeNotifications`
     * lags the call by a beat. Reading it immediately made this suite claim the notification had
     * outlived a `clear` that had in fact worked — measured on the device, where it really was
     * gone. So the assertion is about where things settle, not about the same millisecond.
     */
    private fun waitForCount(expected: Int, what: String) {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            if (ours().size == expected) return
            Thread.sleep(100)
        }
        assertEquals(what, expected, ours().size)
    }

    @Before
    fun clearFirst() {
        SetupStepsNotifier.clear(context)
        // Denied notifications make every assertion below meaningless rather than false — the
        // notifier is deliberately a no-op there, since this is help and not a requirement.
        assumeTrue(
            "notifications are switched off for this build, so nothing can be posted",
            manager.areNotificationsEnabled(),
        )
    }

    @After
    fun cleanUp() {
        SetupStepsNotifier.clear(context)
    }

    @Test
    fun itPostsTheStepsWhileTheUserIsAway() {
        SetupStepsNotifier.show(context, guide, "Blocking needs one switch")
        waitForCount(1, "expected exactly one setup-steps notification")

        val posted = ours()

        val extras = posted.first().notification.extras
        val body = extras.getCharSequence("android.bigText")?.toString().orEmpty()
        assertTrue("the notification carries no steps at all", body.isNotBlank())
        for ((i, shot) in guide.shots.withIndex()) {
            assertTrue(
                "step ${i + 1} is missing from the notification — it must hold the whole list, " +
                    "because the screen it came from is no longer reachable",
                body.contains(shot.caption.take(20)),
            )
        }
    }

    /**
     * **The half that matters more.** An instruction for a job already done, left sitting in the
     * shade, is litter — and this one is posted at the exact moment the user's attention is
     * elsewhere, so nobody would notice it never left.
     */
    @Test
    fun itGoesAwayAgain() {
        SetupStepsNotifier.show(context, guide, "Blocking needs one switch")
        waitForCount(1, "nothing was posted, so there is nothing to prove about clearing")

        SetupStepsNotifier.clear(context)

        waitForCount(0, "the steps outlived the trip to Settings")
    }

    /** Clearing something never posted must not throw — it runs on every recomposition. */
    @Test
    fun clearingNothingIsHarmless() {
        SetupStepsNotifier.clear(context)
        SetupStepsNotifier.clear(context)

        waitForCount(0, "clearing nothing should leave nothing")
    }

    private companion object {
        /** Mirrors the notifier's own id; kept private there, so it is restated rather than opened
         *  up purely for a test. If it drifts, `itPostsTheStepsWhileTheUserIsAway` fails loudly. */
        const val NOTIFICATION_ID = 5301
    }
}
