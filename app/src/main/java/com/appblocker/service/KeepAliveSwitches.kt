package com.appblocker.service

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import com.appblocker.data.DeviceVendor

/**
 * **The two switches that bring AppBlocker back, and the warning when either is off** (invariant 109).
 *
 * Proven on his phone on 8 Nov 2026: after the phone force-stops AppBlocker (a cleaner, a battery
 * mode, an empty battery) nothing of ours runs again until something starts it, and the only things
 * that do are **Notification access** (Android binds our listener again by itself, within seconds)
 * and, on Xiaomi, **Autostart** (appop `MIUIOP(10008)`; without it HyperOS refuses that start). Every
 * long gap in his reports — 35 h in October, 27 h on 7–8 Nov, 15 h 43 min in Second Space — had one
 * of them off.
 *
 * His ask on 10 Nov 2026: *"we need more warning if they are off"*. His picks: a warning that
 * **stays in the shade and floats again every hour until fixed**, and **warn only** — no cover on the
 * settings pages. So this reads both switches and keeps [ProtectionNotifier.notifyKeepAliveOff] up
 * while either reads OFF.
 *
 * ⚠️ **UNKNOWN never warns.** Autostart is a Xiaomi setting with no public API; on other brands, or
 * where HyperOS refuses the read, there is nothing true to say, and a warning he cannot clear would
 * teach him to ignore the one that matters.
 *
 * ⚠️ The honest limit: a warning needs AppBlocker running. It fires the moment a switch goes off —
 * before the next shutdown — and cannot fire during one.
 */
object KeepAliveSwitches {

    enum class State { ON, OFF, UNKNOWN }

    /** Xiaomi's own app-op for Autostart, as `cmd appops get <pkg> 10008` prints it on his phone. */
    const val MIUI_OP_AUTOSTART = 10008

    /** How often the watcher's minute heartbeat reads the switches. Autostart has no change
     *  broadcast, so this is how soon a switch turned off in Security is noticed. */
    internal const val HEARTBEAT_CHECK_MS = 2 * 60_000L

    /** After the listener disconnects: long enough for the setting to have been written, short
     *  enough that he is still on the page he turned it off on. */
    internal const val DISCONNECT_CHECK_DELAY_MS = 3_000L

    data class Reading(val notifAccess: State, val autostart: State) {
        val notifOff: Boolean get() = notifAccess == State.OFF
        val autostartOff: Boolean get() = autostart == State.OFF
        val anyOff: Boolean get() = notifOff || autostartOff
    }

    /** When the heartbeat last read the switches, monotonically (invariant 9). */
    @Volatile private var lastHeartbeatCheckRt = 0L

    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun read(context: Context): Reading = Reading(notifAccess(context), autostart(context))

    fun notifAccess(context: Context): State = runCatching {
        if (NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)) {
            State.ON
        } else {
            State.OFF
        }
    }.getOrDefault(State.UNKNOWN)

    /**
     * Xiaomi Autostart for THIS space's copy: `Process.myUid()` carries the user id, so Second Space
     * reads its own switch. `checkOpNoThrow(int, int, String)` is hidden; reflection, and any refusal
     * is [State.UNKNOWN].
     */
    fun autostart(context: Context, manufacturer: String? = Build.MANUFACTURER): State {
        if (!isXiaomi(manufacturer)) return State.UNKNOWN
        val mode = runCatching {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val m = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java,
            )
            m.invoke(ops, MIUI_OP_AUTOSTART, Process.myUid(), context.packageName) as Int
        }.getOrNull()
        return fromMode(mode)
    }

    internal fun isXiaomi(manufacturer: String?): Boolean =
        DeviceVendor.advice(manufacturer).brand == "Xiaomi"

    /**
     * App-op mode → switch. `0` allowed = ON. `1` ignored (what his phone read with it off),
     * `2` errored and `3` default are all "HyperOS will refuse the start" = OFF, and each is cleared
     * by switching it on. Anything else, or no answer, is UNKNOWN.
     */
    internal fun fromMode(mode: Int?): State = when (mode) {
        AppOpsManager.MODE_ALLOWED -> State.ON
        AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED, AppOpsManager.MODE_DEFAULT -> State.OFF
        else -> State.UNKNOWN
    }

    /** `on` / `off` / `unknown` — the report's spelling. */
    fun reportValue(state: State): String = state.name.lowercase()

    /**
     * Read both switches and put the warning up or take it down. Called only where blocking is
     * running (the watchdog's OK verdict, the watcher's heartbeat, the listener's own connect and
     * disconnect), so it never stands beside a "blocking has stopped" alert that matters more.
     */
    fun check(context: Context) {
        val r = read(context)
        if (r.anyOff) {
            ProtectionNotifier.notifyKeepAliveOff(context, r.notifOff, r.autostartOff)
        } else {
            ProtectionNotifier.cancelKeepAliveOff(context)
        }
    }

    /** From the watcher's minute heartbeat, at most every [HEARTBEAT_CHECK_MS]. */
    fun checkFromHeartbeat(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (!heartbeatCheckDue(lastHeartbeatCheckRt, now)) return
        lastHeartbeatCheckRt = now
        check(context)
    }

    /** Pure, for the test: a clock gone backwards answers "check". */
    internal fun heartbeatCheckDue(lastAtRt: Long, nowRt: Long): Boolean {
        if (lastAtRt <= 0L) return true
        val since = nowRt - lastAtRt
        return since < 0L || since >= HEARTBEAT_CHECK_MS
    }

    /** From the listener's connect/disconnect: the moment Notification access changes. */
    fun checkSoon(context: Context) {
        val app = context.applicationContext
        main.postDelayed({ runCatching { check(app) } }, DISCONNECT_CHECK_DELAY_MS)
    }
}
