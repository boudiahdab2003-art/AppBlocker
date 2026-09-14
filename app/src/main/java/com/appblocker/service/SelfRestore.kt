package com.appblocker.service

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.appblocker.R
import com.appblocker.data.OutageLog
import com.appblocker.data.SelfRestoreLog
import com.appblocker.data.Words
import com.appblocker.ui.RestoreActivity

/**
 * **Opens AppBlocker for a few seconds when the watcher is found unbound while the phone is in use.**
 *
 * The owner's choice, 14 Sep 2026: on that day the alert he saw sat for six hours, and opening the
 * app brought blocking back within a second. See [SelfRestoreLog] for the rules and the verdicts;
 * this is only the part that needs a phone.
 *
 * ⚠️ **The banner comes first, and it is load-bearing.** From Android 10 an app in the background
 * may not open a screen, and holding "display over other apps" is the exemption this app has — but
 * on current Android that exemption counts only while one of the app's overlay windows is actually
 * showing. So a small "Turning blocking back on…" banner goes up, and the screen is asked for once
 * it is on screen. The banner is also simply the honest thing to show while it happens.
 */
object SelfRestore {

    /** How long after the banner is attached before the screen is asked for. */
    private const val BANNER_LEAD_MS = 300L

    /** The banner's hard ceiling, whatever happens to the launch. */
    private const val BANNER_MAX_MS = 4_000L

    private val main by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var banner: View? = null

    /**
     * Called by the watchdog on every check that finds blocking stalled. Almost always does nothing:
     * see [SelfRestoreLog.decide] for the seven reasons it declines.
     */
    fun maybeReopen(context: Context, arm: String, calledBy: String) =
        guarded(context, "selfRestore") {
            val app = context.applicationContext
            SelfRestoreLog.resolveStale(app)
            val skip = SelfRestoreLog.decide(
                watcherUnbound = arm == OutageLog.DetectedBy.UNBOUND &&
                    !BlockerAccessibilityService.isConnected(),
                appAlreadyOpen = calledBy == OutageLog.EndedBy.APP_OPENED,
                phoneInUse = phoneInUse(app),
                overlayAllowed = Settings.canDrawOverlays(app),
                attemptPending = SelfRestoreLog.isPending(app),
                sinceLastAttemptMs = SelfRestoreLog.sinceLastAttemptMs(app),
                futileStreak = SelfRestoreLog.futileStreak(app),
            )
            if (skip != null) return@guarded
            SelfRestoreLog.markAttempt(app)
            main.post { launch(app) }
        }

    /** Lit and unlocked. Anything that cannot be told answers no: nobody is interrupted by a guess. */
    private fun phoneInUse(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return false
        return power.isInteractive && !keyguard.isKeyguardLocked
    }

    private fun launch(app: Context) = guarded(app, "selfRestoreLaunch") {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // No background-launch rule to satisfy, and no overlay window type to satisfy it with.
            startScreen(app)
            return@guarded
        }
        val view = showBanner(app)
        if (view == null) {
            startScreen(app)
        } else {
            view.post { main.postDelayed({ startScreen(app) }, BANNER_LEAD_MS) }
            main.postDelayed({ removeBanner(app) }, BANNER_MAX_MS)
        }
    }

    private fun startScreen(app: Context) = guarded(app, "selfRestoreStart") {
        app.startActivity(
            Intent(app, RestoreActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        )
    }

    private fun showBanner(app: Context): View? {
        banner?.let { return it }
        val wm = app.getSystemService(WindowManager::class.java) ?: return null
        val density = app.resources.displayMetrics.density
        val view = TextView(app).apply {
            text = Words.of(app).get(R.string.restore_banner)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            val side = (20 * density).toInt()
            val top = (12 * density).toInt()
            setPadding(side, top, side, top)
            background = GradientDrawable().apply {
                cornerRadius = 24 * density
                setColor(0xFF1E2A44.toInt())
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (64 * density).toInt()
            // Android 12+ lets touches through another app's window only below this opacity, and
            // this banner must never be the thing that swallows a tap.
            alpha = 0.8f
        }
        return runCatching { wm.addView(view, params) }.map { view.also { banner = it } }.getOrNull()
    }

    /** Takes the banner down. Main thread; safe to call twice and from the screen it announced. */
    fun removeBanner(context: Context) {
        val view = banner ?: return
        banner = null
        runCatching {
            context.applicationContext.getSystemService(WindowManager::class.java)?.removeView(view)
        }
    }
}
