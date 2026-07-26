package com.appblocker.service

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.appblocker.BuildConfig
import com.appblocker.data.BugReport
import com.appblocker.data.AttemptCounter
import com.appblocker.data.BlockLayouts
import com.appblocker.data.BlockLog
import com.appblocker.data.BlockThemes
import com.appblocker.data.BugReportQueue
import com.appblocker.data.SettingsStore
import com.appblocker.ui.hasUsageAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/**
 * Posts queued bug reports to the report endpoint (see docs/SERVER.md), which forwards them to a
 * private issue tracker.
 *
 * Shaped like the coach's proxy call in [com.appblocker.data.AiCoach] — same shared-secret header,
 * same timeouts — because it talks to the same Caddy on the same VM, just a different route.
 *
 * **Nothing here is allowed to matter.** The first principle in docs/SERVER.md is that the app
 * works fully with the VM offline, and a bug reporter is the last thing that should be able to
 * break the thing it reports on. So: every call is wrapped, failures leave the report queued for
 * next time, and — the one that is easy to get wrong — **a failure in here is never itself
 * reported**. Reporting a failed report is how a dead endpoint turns into an infinite loop.
 *
 * Reporting is off entirely when [BuildConfig.REPORT_URL] or [BuildConfig.REPORT_SECRET] is empty,
 * which is the default for local builds: the secret is injected at release time and deliberately
 * not committed, since this repo is public.
 */
object BugReportSender {
    private const val TAG = "BugReportSender"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Whether reporting is configured at all. */
    fun enabled(): Boolean =
        BuildConfig.REPORT_URL.isNotBlank() && BuildConfig.REPORT_SECRET.isNotBlank()

    /** The device facts a report is allowed to carry. Kept here so the payload builder stays
     *  free of Android types and therefore unit-testable. */
    fun describeDevice(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    /**
     * The app's *configuration* at the moment of the report: which look is chosen, whether
     * blocking is actually running, what the owner has switched on, how many blocks happened
     * today.
     *
     * Without this a report reads "the block screen didn't appear" with nothing to reason from —
     * the owner cannot be expected to know that "the service was in its STALLED state and you had
     * no usage access" is the useful half of that sentence.
     *
     * **Every value here is a setting or a count. None can hold content** — no keyword, no URL, no
     * app or package name. [BugReport.sanitizeContext] drops anything whose key is not on the
     * allow-list, so if a later change adds something it shouldn't, it never leaves the device.
     *
     * Deliberately reads nothing from the database. This runs on the error path, where the app is
     * already in trouble, and a synchronous Room query there would be a new way to make things
     * worse. That is why there is no "how many rules" or "is Strict running" — both live in Room.
     * Everything below is SharedPreferences or a system flag.
     */
    fun appContext(ctx: Context): Map<String, String> = runCatching {
        mapOf(
            "layout" to BlockLayouts.current(ctx).id,
            "theme" to BlockThemes.current(ctx).id,
            "serviceOn" to AccessibilityUtil.isEnabled(ctx).toString(),
            "protection" to ProtectionWatchdog.state(ctx).name,
            "guard" to SettingsStore.guardOffSwitch(ctx).toString(),
            "blocksToday" to AttemptCounter.summary(ctx).sumOf { it.today }.toString(),
            "adultPack" to SettingsStore.adultWordsPack(ctx).toString(),
            "scanEverywhere" to SettingsStore.keywordsEverywhere(ctx).toString(),
            "overlayPermission" to Settings.canDrawOverlays(ctx).toString(),
            "usageAccess" to hasUsageAccess(ctx).toString(),
        )
    }.getOrDefault(emptyMap())

    /** Records an error for later sending. Safe to call from anywhere, including the watcher. */
    fun report(context: Context, where: String, t: Throwable) {
        if (!enabled()) return
        runCatching {
            BugReportQueue.enqueue(
                context,
                BugReport.fromThrowable(
                    where = where,
                    t = t,
                    appVersion = BuildConfig.VERSION_NAME,
                    flavor = BuildConfig.FLAVOR,
                    androidSdk = Build.VERSION.SDK_INT,
                    device = describeDevice(),
                    context = appContext(context),
                    recentBlocks = BlockLog.recent(context),
                ),
            )
        }
    }

    /** Records what the owner typed, and tries to send it straight away. */
    fun reportNote(context: Context, note: String) {
        if (!enabled()) return
        runCatching {
            BugReportQueue.enqueue(
                context,
                BugReport.fromNote(
                    note = note,
                    appVersion = BuildConfig.VERSION_NAME,
                    flavor = BuildConfig.FLAVOR,
                    androidSdk = Build.VERSION.SDK_INT,
                    device = describeDevice(),
                    context = appContext(context),
                    recentBlocks = BlockLog.recent(context),
                ),
            )
            flush(context)
        }
    }

    /**
     * Sends everything queued, one at a time. Called on app resume, where a network is most
     * likely and where taking a moment costs nothing.
     */
    fun flush(context: Context) {
        if (!enabled()) return
        val app = context.applicationContext
        scope.launch {
            runCatching {
                BugReportQueue.pending(app).forEach { report ->
                    if (post(report)) BugReportQueue.markSent(app, report)
                    else BugReportQueue.markFailed(app, report)
                }
            }.onFailure {
                // Swallowed on purpose, and NOT reported — see the class KDoc.
                Log.w(TAG, "flush failed", it)
            }
        }
    }

    private fun post(report: BugReport): Boolean = runCatching {
        val conn = (URL(BuildConfig.REPORT_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${BuildConfig.REPORT_SECRET}")
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
        }
        conn.outputStream.use { it.write(report.toJson().toByteArray()) }
        val code = conn.responseCode
        conn.disconnect()
        // GitHub answers 201 on create. Treat any 2xx as done; anything else stays queued, so a
        // misconfigured secret retries rather than silently discarding the report.
        code in 200..299
    }.getOrDefault(false)
}
