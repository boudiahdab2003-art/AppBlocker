package com.appblocker.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Telephony

/**
 * **At 5 % battery or less and not charging, every app is covered except calls and messages —
 * always, no switch** (invariant 104, his choice on 8 Nov 2026: "Low-battery lock").
 *
 * Why: Xiaomi's hibernation is its "Extreme endurance mode" (`f7.u.f0` in `com.miui.securitycenter`,
 * read from his phone's own APK). When the battery drops from 2 % to 1 % while discharging it starts
 * a 30-second countdown with **no cancel button** — only the charger (status CHARGING) or a higher
 * level stops it — then force-stops every non-whitelisted app, AppBlocker included, and takes its
 * Accessibility entry with it. Its own switch (`preference_key_extreme_mode_button`) is hidden on his
 * phone and its pref is private to Xiaomi's app. If he then charges, Xiaomi leaves hibernation at
 * 2 % but nothing restarts AppBlocker; that cost 27 hours on 7–8 Nov.
 *
 * AppBlocker is still alive at 5 %, so it can make sure the phone is never USED down to 1 %:
 * nothing but calls and messages opens until the charger is in. Plugging in also cancels Xiaomi's
 * countdown, so the hibernation never starts.
 */
internal object LowBatteryLock {

    const val KEY = "low-battery"

    /** At or below this, unplugged, the lock stands. Xiaomi's own warning step is 5 % too. */
    const val LOCK_AT_PERCENT = 5

    data class Reading(val percent: Int, val charging: Boolean)

    /** Unknown level (`-1`) never locks: a reading we could not take must not cover the phone. */
    fun isLocked(r: Reading?): Boolean =
        r != null && r.percent in 0..LOCK_AT_PERCENT && !r.charging

    /** The call and emergency screens, whichever dialer the phone uses — never covered. */
    val CALL_SURFACES = setOf(
        "com.android.phone", "com.android.server.telecom", "com.android.incallui",
        "com.android.emergency", "com.google.android.dialer",
    )

    /** Calls, messages and the phone's own essentials (home, system UI, keyboard, settings, us). */
    fun allowed(pkg: String, essential: Boolean, smsPkg: String?): Boolean =
        essential || pkg in CALL_SURFACES || (smsPkg != null && pkg == smsPkg)

    /** Any power source counts — plugged in is what the user can do; Xiaomi needs CHARGING, which follows. */
    fun fromIntent(i: Intent?): Reading? {
        if (i == null) return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val charging = plugged != 0 ||
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return Reading(level * 100 / scale, charging)
    }

    /** The sticky battery broadcast — no receiver, no permission. */
    fun read(context: Context): Reading? = runCatching {
        fromIntent(context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
    }.getOrNull()

    fun smsPackage(context: Context): String? =
        runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
}
