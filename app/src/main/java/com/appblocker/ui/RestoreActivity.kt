package com.appblocker.ui

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.appblocker.R
import com.appblocker.data.AppLocale
import com.appblocker.data.OwnUi
import com.appblocker.data.SelfRestoreLog
import com.appblocker.service.BlockerAccessibilityService
import com.appblocker.service.SelfRestore

/**
 * **The few seconds AppBlocker is open when it has reopened itself** — see [SelfRestore].
 *
 * A plain `Activity` with no Compose on purpose: the point is to be in front as fast as possible,
 * and a cold Compose start costs hundreds of milliseconds nobody is here to look at. It closes itself
 * the moment the watcher is bound again, or after [WAIT_MS] whatever happens, and a tap anywhere
 * closes it sooner — it is never a screen he has to find the way out of.
 *
 * It does not judge the attempt. [SelfRestoreLog] does, from the rebind itself, so a watcher that
 * comes back a few seconds after this has closed is still credited.
 */
class RestoreActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var openedAtRt = 0L
    private lateinit var label: TextView

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SelfRestore.removeBanner(this)
        SelfRestoreLog.noteLaunched(applicationContext)
        openedAtRt = SystemClock.elapsedRealtime()
        val density = resources.displayMetrics.density
        label = TextView(this).apply {
            text = getString(R.string.restore_working)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            val pad = (24 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = 28 * density
                setColor(0xFF1E2A44.toInt())
            }
        }
        val margin = (32 * density).toInt()
        val root = FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt())
            addView(
                label,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ).apply { setMargins(margin, margin, margin, margin) },
            )
            setOnClickListener { finish() }
        }
        setContentView(root)
        handler.post(watch)
    }

    private val watch = object : Runnable {
        override fun run() {
            if (isFinishing) return
            when {
                BlockerAccessibilityService.isConnected() -> {
                    label.text = getString(R.string.restore_done)
                    handler.postDelayed({ finish() }, DONE_SHOWN_MS)
                }
                SystemClock.elapsedRealtime() - openedAtRt >= WAIT_MS -> finish()
                else -> handler.postDelayed(this, POLL_MS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        OwnUi.visible = true
        // A rebind in the next few seconds followed our own screen — see ProtectionWatchdog.
        OwnUi.resumedAtRt = SystemClock.elapsedRealtime()
    }

    override fun onPause() {
        super.onPause()
        OwnUi.visible = false
    }

    override fun onStop() {
        super.onStop()
        // It exists for a few seconds or not at all; never left waiting behind another app.
        if (!isFinishing) finish()
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val POLL_MS = 200L
        const val WAIT_MS = 5_000L
        const val DONE_SHOWN_MS = 900L
    }
}
