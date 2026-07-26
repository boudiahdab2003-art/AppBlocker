package com.appblocker.service

import android.content.Context
import android.util.Log
import com.appblocker.data.ServiceHealth

/**
 * The blocking watcher's safety net.
 *
 * An exception thrown out of an accessibility callback, a Handler runnable or a coroutine takes
 * the whole process down — and with it every block, silently, until the user notices a blocked app
 * opening. The watcher touches a lot of surface it doesn't control (OEM window trees, nodes that
 * get recycled mid-walk, the database, location services), so one unexpected value must not be
 * able to end blocking for the day.
 *
 * Swallowing is not the same as ignoring: every catch is logged and counted in [ServiceHealth], so
 * a recurring bug shows up instead of hiding. Errors that mean the process is already doomed
 * (OutOfMemory, StackOverflow — anything the runtime can't continue past) are re-thrown; pretending
 * to survive those would be worse than dying honestly.
 */
internal inline fun guarded(context: Context, where: String, block: () -> Unit) =
    runGuarded(
        where,
        { w, t ->
            ServiceHealth.recordError(context, w, t)
            // And off the device, if reporting is configured. This is the half the owner can
            // never file himself: a swallowed error means blocking carried on with something
            // quietly broken, and nothing on screen changes. Queued, deduplicated and sent on
            // next app open — see BugReportSender, which is explicitly not allowed to fail
            // loudly, since the reporter must never be able to break what it reports on.
            BugReportSender.report(context, w, t)
        },
        block,
    )

/**
 * [guarded] with the reporting split out, so the catch/re-throw rule can be unit-tested without
 * an Android Context.
 */
internal inline fun runGuarded(
    where: String,
    report: (String, Throwable) -> Unit,
    block: () -> Unit,
) {
    try {
        block()
    } catch (fatal: VirtualMachineError) {
        throw fatal
    } catch (t: Throwable) {
        Log.w("AppBlocker", "guarded($where) swallowed an error — blocking continues", t)
        runCatching { report(where, t) }
    }
}
