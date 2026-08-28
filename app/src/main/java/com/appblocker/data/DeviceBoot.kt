package com.appblocker.data

import android.content.Context
import android.provider.Settings

/** Android's persistent boot sequence number, or -1 when it cannot be read. */
object DeviceBoot {
    @Volatile private var cachedCount: Int? = null

    /**
     * ⚠️ **Only a successful read is cached, and the read itself cannot throw.**
     *
     * `-1` is not an answer, it is the absence of one — and this number is load-bearing.
     * [SessionClock] trusts the monotonic clock only while `savedBootCount >= 0 && saved ==
     * current`, so one `-1` cached for the life of the process drops **every** [GuardedDeadline]
     * and every Strict / Timer / Pomodoro session onto the wall clock at once, where winding the
     * clock forward becomes a working bypass of the keyword lockout, the danger hour and both
     * off-switch delays. It would also make `InstallPrompt.openAt` answer false forever, which is
     * report #6's shape: the off-switch guard bouncing the app's own updater.
     *
     * Caching a failure makes one bad read permanent; retrying costs a `Settings.Global` lookup.
     * And the call is wrapped because an uncaught throw here would travel
     * `keywordLockoutRemaining` → `blockReason` → `handleAppBlock` into `guarded("event")`, which
     * swallows it — skipping the block decision for **every event**, silently, while the
     * accessibility switch still reads ON.
     */
    fun count(context: Context): Int = cachedCount ?: synchronized(this) {
        cachedCount ?: read(context).also { if (it >= 0) cachedCount = it }
    }

    private fun read(context: Context): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrDefault(-1)
}
