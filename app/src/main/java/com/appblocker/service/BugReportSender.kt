package com.appblocker.service

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.appblocker.BuildConfig
import com.appblocker.data.AiCoach
import com.appblocker.data.BugReport
import com.appblocker.data.AttemptCounter
import com.appblocker.data.BlockLayouts
import com.appblocker.data.BlockLatency
import com.appblocker.data.BlockLog
import com.appblocker.data.BlockThemes
import com.appblocker.data.BugReportQueue
import com.appblocker.data.DeviceProfile
import com.appblocker.data.ServiceHealth
import com.appblocker.data.DeviceBoot
import com.appblocker.data.FilterState
import com.appblocker.data.NetworkFilter
import com.appblocker.data.OutageLog
import com.appblocker.data.PinStore
import com.appblocker.data.QuickSession
import com.appblocker.data.SettingsStore
import com.appblocker.data.SilenceLog
import com.appblocker.ui.hasUsageAccess
import com.appblocker.ui.isIgnoringBattery
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
    fun appContext(ctx: Context): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var failed = 0
        // Each field is read on its own. This used to be one runCatching around the whole map,
        // which meant ONE throw among ten calls produced an empty map with nothing to say why —
        // and that is exactly what happened: the owner's first real report arrived carrying only
        // version, SDK and device, so the flashing it was meant to diagnose stayed invisible.
        // A diagnostic that fails silently is the bug it exists to catch (BLOCKING_INVARIANTS:
        // "if this itself broke, would anyone ever know?").
        fun field(key: String, read: () -> String) {
            runCatching { out[key] = read() }.onFailure { failed++ }
        }
        field("layout") { BlockLayouts.current(ctx).id }
        field("theme") { BlockThemes.current(ctx).id }
        field("serviceOn") { AccessibilityUtil.isEnabled(ctx).toString() }
        field("protection") { ProtectionWatchdog.state(ctx).name }
        field("guard") { SettingsStore.guardOffSwitch(ctx).toString() }
        field("blocksToday") { AttemptCounter.summary(ctx).sumOf { it.today }.toString() }
        // The other half of "blocksToday": the spells where it declined to block. A report that
        // only ever carries successes cannot describe an under-block, which is the failure this
        // app cannot see (SilenceLog).
        field("deafSpells") {
            val c = SilenceLog.get(ctx, SilenceLog.DEAF_DISMISSALS); "${c.today}/${c.total}"
        }
        field("lateSkips") { SilenceLog.get(ctx, SilenceLog.LATE_DECLINES).total.toString() }
        // "82% of 140, 3 slow" — the share that landed under half a second, how many blocks that
        // is out of, and how many took over two seconds. The tail is the part worth reading: a
        // good percentage with a growing tail is exactly what "sometimes it's slow" looks like.
        field("blockSpeed") {
            val quick = BlockLatency.quickShare(ctx)
            if (quick == null) "none yet" else {
                val counts = (0 until BlockLatency.SIZE).map { BlockLatency.get(ctx, it).total }
                "$quick% of ${counts.sum()}, ${counts.last()} slow"
            }
        }
        field("unreadyDecisions") {
            SilenceLog.get(ctx, SilenceLog.UNREADY_DECISIONS).total.toString()
        }
        field("adultPack") { SettingsStore.adultWordsPack(ctx).toString() }
        field("scanEverywhere") { SettingsStore.keywordsEverywhere(ctx).toString() }
        field("blockUnsupported") { SettingsStore.blockUnsupportedBrowsers(ctx).toString() }
        field("overlayPermission") { Settings.canDrawOverlays(ctx).toString() }
        field("usageAccess") { hasUsageAccess(ctx).toString() }
        field("batteryFree") { isIgnoringBattery(ctx).toString() }
        field("quickPaused") { SettingsStore.quickBlockPaused(ctx).toString() }
        field("allowlist") { SettingsStore.quickBlockAllowlist(ctx).toString() }
        // "Switched on" and "still working" are different facts, and this is the number that
        // separates them: how long since the watcher last saw ANY event. A report saying blocking
        // didn't happen, next to "lastEventMin 240", answers itself.
        field("lastEventMin") {
            val at = ServiceHealth.lastEventAt(ctx)
            if (at <= 0L) "never" else ((System.currentTimeMillis() - at) / 60_000L).toString()
        }
        // The fact `serviceOn` cannot give: Android's toggle records the user's CHOICE, so it
        // reads true over a watcher the phone has killed. These two together are the difference
        // between "he switched it off" and "his phone shut it down and told him it was on".
        field("serviceRunning") { BlockerAccessibilityService.isConnected().toString() }
        field("foundDead") { ServiceHealth.foundDeadCount(ctx).toString() }
        field("healthErrors") { ServiceHealth.errorCount(ctx).toString() }
        // The tag only. ServiceHealth's full line contains the exception message, which is where
        // a blocked word gets quoted back — see BugReport's contract.
        ServiceHealth.lastErrorWhere(ctx)?.let { w -> field("lastErrorWhere") { w } }
        // Separates "the service never came back after a reboot" from "it died an hour ago",
        // which look identical in every other field.
        field("uptimeMin") { (SystemClock.elapsedRealtime() / 60_000L).toString() }
        // The phone's own clock. Half of any schedule question is "what time was it there?", and
        // the issue's timestamp is when the report was SENT — hours later, for a queued one.
        field("localTime") {
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())
        }
        // Only when the coach has actually failed — absent means it has never failed on this
        // install, which is different from "we didn't look".
        AiCoach.lastError(ctx)?.let { (error, _) -> field("coachError") { error.name } }
        // ---- What this phone is actually set up to block -------------------------------------
        // Added 26 Aug 2026: "i want the report to include a lot more than the last report".
        // Every one is a COUNT or a named setting, never a subject — no app name, no word, no
        // host — so the contract in BugReport is unchanged. All read from prefs rather than Room,
        // because this runs on whatever thread the failure happened on and a database query there
        // would turn a bug report into a crash.
        //
        // These answer the question every "it didn't block X" report actually poses, which is not
        // "is the service on" but "was there anything for it to block". A report showing
        // blockedApps 0 next to a complaint about Instagram answers itself.
        field("updatePaused") { SettingsStore.updatePaused(ctx).toString() }
        field("ruleCount") { SettingsStore.blockedSnapshot(ctx).size.toString() }
        field("lockouts") { SettingsStore.keywordLockouts(ctx).size.toString() }
        field("browsersRead") { SettingsStore.readableBrowsers(ctx).size.toString() }
        field("autoBlockNew") { SettingsStore.addNewApps(ctx).toString() }
        field("pinSet") { PinStore.isSet(ctx).toString() }
        field("quickSession") { QuickSession.state(ctx).let { if (it.blockingNow) "on" else "off" } }
        // The danger zone and what it has learned. An armed zone changes what every other layer
        // does — every browser shut, a wider word list, scanning in apps that are normally left
        // alone — so a report sent during one is describing a different app.
        field("dangerZone") {
            val left = SettingsStore.dangerZone(ctx)?.remaining(DeviceBoot.count(ctx)) ?: 0L
            if (left > 0L) "on ${(left + 59_999L) / 60_000L}min" else "off"
        }
        field("dangerStrikes") { SettingsStore.dangerStrikes(ctx).size.toString() }
        // Whether the network-level filter is running, and whether it is being defended. A report
        // sent while the browsers are shut over this would otherwise be inexplicable.
        field("dnsFilter") {
            val state = when (NetworkFilter.read(ctx).state) {
                FilterState.FILTERING -> "filtering"
                FilterState.ON_BUT_UNKNOWN -> "encrypted-only"
                FilterState.OFF -> "off"
                FilterState.CANT_TELL -> "unknown"
            }
            val armed = SettingsStore.netFilterSeen(ctx) && SettingsStore.netFilterGuard(ctx)
            "$state${if (armed) " armed" else ""}"
        }
        field("learnedCount") { SettingsStore.learnedDomains(ctx).size.toString() }
        // Reported rather than hidden: a bare report and a healthy one must never look alike.
        if (failed > 0) out["fieldErrors"] = failed.toString()
        return out
    }

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

    /**
     * Sends what this phone says about the app's guesses — **including when they were all right.**
     *
     * This is the one report that is not about a failure, and it exists because the failures it
     * covers cannot produce one. A Samsung whose uninstall screen we mis-guessed does not throw:
     * Strict Mode simply stops protecting, and [report] never fires because nothing went wrong in
     * a way Android can see. Waiting for a crash meant never hearing from the phones the app has
     * never run on, which is every phone except the owner's two.
     *
     * **Sending on success is the other half.** Without it, no report means either "this brand is
     * fine" or "reporting is broken on this brand", and nothing tells those apart — so a clean
     * profile is filed too, and `docs/DEVICE_MATRIX.md` gets a row it can trust.
     *
     * Safe to call on every resume: [BugReportQueue] drops anything whose dedupe key has already
     * been sent, and a profile's key is this phone plus this build. No flush here — the caller
     * flushes straight after, and a profile has never been urgent enough to warrant its own post.
     */
    fun reportDeviceProfile(context: Context) {
        if (!enabled()) return
        runCatching {
            BugReportQueue.enqueue(
                context,
                BugReport.fromProfile(
                    appVersion = BuildConfig.VERSION_NAME,
                    flavor = BuildConfig.FLAVOR,
                    androidSdk = Build.VERSION.SDK_INT,
                    device = describeDevice(),
                    context = DeviceProfile.reportContext(context),
                ),
            )
        }
    }

    /**
     * Sends a **finished** outage — how long blocking was off, and what shape the failure had.
     *
     * The third report shape, and it exists for the same reason [reportDeviceProfile] does: this
     * failure cannot produce a fault. Nothing throws when Android stops delivering events, so
     * [report] never fires, and a note only exists if the owner happens to be looking. The failure
     * that costs the most was the one least able to report itself.
     *
     * Called from the watchdog's OK branch, which usually runs in a background worker, so there is
     * no flush here. It goes out on the next app open — which after an outage is soon, because
     * opening the app is how he fixes it.
     */
    fun reportOutage(context: Context, episode: OutageLog.Episode) {
        if (!enabled()) return
        runCatching {
            BugReportQueue.enqueue(
                context,
                BugReport.fromOutage(
                    appVersion = BuildConfig.VERSION_NAME,
                    flavor = BuildConfig.FLAVOR,
                    androidSdk = Build.VERSION.SDK_INT,
                    device = describeDevice(),
                    context = appContext(context) + mapOf(
                        // Seconds rather than millis: this only has to identify the episode and
                        // stay readable, and the context cap is 24 characters.
                        "outageAt" to "${episode.startedAt / 1000}",
                        "outageMin" to minutesOrUnknown(episode.durationMs),
                        "outageDetectMin" to minutesOrUnknown(episode.detectedAfterMs),
                        "outageDeaf" to "${episode.aliveButDeaf}",
                        "outagePreceded" to episode.precededBy,
                        "outageCount" to "${OutageLog.totals(context).count}",
                    ),
                    recentBlocks = BlockLog.recent(context),
                ),
            )
        }
    }

    /** Whole minutes, or `?` for the values [OutageLog] marks unmeasurable with -1. */
    private fun minutesOrUnknown(ms: Long) = if (ms < 0) "?" else "${ms / 60_000}"

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
                // **The daily cap is checked here as well as at enqueue, and it has to be.**
                // `remainingToday` only moves when a report is *sent*, and nothing is sent until
                // the next app open — so a bad day queues reports against a counter that is still
                // reading zero spent, and one flush then posts the whole queue at once. The cap
                // said 12 and the real ceiling was MAX_PENDING, 20. Checking it per report is what
                // makes the KDoc's "backstop that cannot be reasoned around" true; what does not
                // go out stays queued for tomorrow, which is the intended cost.
                for (report in BugReportQueue.pending(app)) {
                    if (BugReportQueue.remainingToday(app) <= 0) break
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
