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
import com.appblocker.data.HealthFacts
import com.appblocker.data.OutageLog
import com.appblocker.data.PinStore
import com.appblocker.data.QuickSession
import com.appblocker.data.SettingsStore
import com.appblocker.data.ProtectionPulse
import com.appblocker.data.SilenceLog
import com.appblocker.ui.hasUsageAccess
import com.appblocker.ui.isIgnoringBattery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
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

    /**
     * **True while a [flush] is draining the queue.** Invariant 36.
     *
     * `markSent` only drops a report once its POST has *returned*, so a second flush starting
     * mid-drain reads a `pending()` that still lists everything in flight and sends it all again.
     * On 1 Sep 2026 that put four duplicates into the tracker out of thirteen — the back of the
     * queue, which is exactly the half a second flush would still have been holding.
     *
     * `MainActivity` flushes on resume, so this needs no unusual timing to happen; it needs him to
     * come back to the app twice while a slow drain is running.
     */
    private val flushing = java.util.concurrent.atomic.AtomicBoolean(false)

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
    // `internal` because it takes ProtectionWatchdog.Reading, which is internal by design —
    // the watchdog's raw reading is not something outside this module should be handling.
    internal fun appContext(
        ctx: Context,
        watch: ProtectionWatchdog.Reading? = null,
    ): Map<String, String> {
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
        // One read, then every number that fell out of it — the watchdog answers this question by
        // gathering them all anyway, and asking twice is the two-sources-of-truth shape its own
        // KDoc warns about. **Passed in by the caller**, because the health verdicts need the same
        // reading and `ProtectionWatchdog.read` is not cheap: it walks the usage-event stream from
        // the last event seen until now, which on a phone that has been quiet for hours is a long
        // walk. Doing that twice per report would have put two of them inside the uncaught-
        // exception handler, in a process that is already dying — the exact cost this method's
        // KDoc refuses Room for.
        val reading = watch ?: runCatching { ProtectionWatchdog.read(ctx) }.getOrNull()
        field("protection") { reading?.state?.name ?: ProtectionWatchdog.state(ctx).name }
        // **The number the STALLED verdict actually turns on**, and it was computed on every check
        // and thrown away. Quiet is only evidence when something was happening: `lastEventMin 240`
        // beside `usedMinutes 90` is four unprotected hours, and beside `usedMinutes 0` it is a
        // phone on a table. Those were the same report until now. `?` = usage access never
        // answered, which is a third thing again and must not read as zero.
        field("usedMinutes") { reading?.usedMinutes?.toString() ?: "?" }
        // Whether the answer above came from the bind grace rather than from evidence. A STALLED
        // that is really "we have not waited long enough yet" is not a finding.
        field("bindPending") { reading?.bindPending?.toString() ?: "?" }
        // How long THIS PROCESS has been alive, against `uptimeMin`'s whole-phone figure. A
        // watcher missing three seconds after a cold start has not died; one missing after two
        // hours of process life has.
        field("processAgeMin") { ((reading?.sinceProcessStartMs ?: 0L) / 60_000L).toString() }
        // The watchdog re-checked and the watcher was still gone. Non-zero means the phone is
        // killing the process faster than the 45-second grace can wait for it.
        field("bindDeferrals") { SettingsStore.bindDeferrals(ctx).toString() }
        // Is blocking stopped AT THIS MOMENT? Every other `outage*` key describes the last
        // *finished* episode, so a report written during a stoppage used to look exactly like one
        // written after it.
        field("outageNow") { OutageLog.isOpen(ctx).toString() }
        field("guard") { SettingsStore.guardOffSwitch(ctx).toString() }
        // Which install this is. Two were reporting from what looked like one phone on 2 Sep
        // 2026 and nothing could separate them; their histories were nothing alike.
        field("installId") { SettingsStore.installId(ctx) }

        // ⚠️ **Which Xiaomi Space this copy lives in.** He confirmed on 3 Sep 2026 that the
        // second install is Second Space, not a second phone — and the two had been read as one
        // device for two days, because model, Android version and boot count are all identical
        // across spaces.
        //
        // It matters far more than a label. A space that is not the active one is SUSPENDED, so
        // the copy living there records long "outages" that are not failures of blocking at all;
        // the 13-hour stoppage that looked alarming is most likely a space he simply was not in.
        // Reading those next to the active space's overstates the problem and hides the real one.
        //
        // Android encodes the user id in the UID: uid / 100000 is 0 for the owner and 10, 11 …
        // for the spaces after it. No permission, no reflection, no API level to guard.
        field("space") { (android.os.Process.myUid() / 100_000).toString() }
        // Read off the snapshot, which is a plain string in prefs — the live list lives in Room
        // and this builder is synchronous. Nothing knew whether he uses schedules at all, which
        // is the first thing worth knowing before spending anything on them.
        field("scheduleCount") { SettingsStore.scheduleSnapshot(ctx).size.toString() }
        field("blocksToday") { AttemptCounter.summary(ctx).sumOf { it.today }.toString() }
        // The other half of "blocksToday": the spells where it declined to block. A report that
        // only ever carries successes cannot describe an under-block, which is the failure this
        // app cannot see (SilenceLog).
        field("deafSpells") {
            val c = SilenceLog.get(ctx, SilenceLog.DEAF_DISMISSALS); "${c.today}/${c.total}"
        }
        field("lateSkips") { SilenceLog.get(ctx, SilenceLog.LATE_DECLINES).total.toString() }
        // The other half of `deafSpells`: how many of those silences the app came back from on
        // its own. A climbing deafSpells beside a flat zero here means the return never fires.
        field("graceRecovers") { SilenceLog.get(ctx, SilenceLog.GRACE_RECOVERS).total.toString() }
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
        // The same measurement without the collapse: every bucket, fastest to slowest. The summary
        // above can read 90% while the middle of the distribution walks steadily to the right, and
        // "sometimes it's slow" is a claim about the shape rather than the headline. Eleven
        // characters for five numbers, so it fits the context cap with room to spare.
        field("speedBuckets") {
            (0 until BlockLatency.SIZE).joinToString("/") { BlockLatency.get(ctx, it).total.toString() }
        }
        // Total time unprotected, and the worst single stoppage. Both shown on the diagnostics
        // screen since v1.143 and neither ever sent — they are the two headline numbers about the
        // owner's actual complaint.
        field("outageTotalMin") { (OutageLog.totals(ctx).totalMs / 60_000L).toString() }
        field("outageWorstMin") { (OutageLog.totals(ctx).longestMs / 60_000L).toString() }
        // ⚠️ The share of `outageTotalMin` that is a measurement rather than a ceiling. Without
        // it the headline is two quantities added together: stoppages the watcher timed itself,
        // and stoppages that also contain however long it took a throttled job to come looking.
        // `outageTimedCount` says how many of `outageCount` that covers — a small count against a
        // big one means most of the total is still an upper bound.
        field("outageTimedMin") { (OutageLog.totals(ctx).timedMs / 60_000L).toString() }
        field("outageTimedCount") { OutageLog.totals(ctx).timedCount.toString() }
        // The live gap since the background scheduler last ran, against `workerSilent`'s lifetime
        // count. "It has been quiet for six hours" is a different statement from "it has gone
        // quiet nine times", and only the first describes right now.
        field("workerSilentMin") {
            val ms = ProtectionPulse.silentFor(ctx)
            if (ms == ProtectionPulse.UNKNOWN) "?" else (ms / 60_000L).toString()
        }
        // ⚠️ The reporter describing its own channel. Written after five days in which every
        // report the phone produced was queued and none arrived, with nothing in any report able
        // to say so. A backlog visible on arrival means the reports before this one did not get
        // through — a fact about the route that only the route can tell us.
        field("reportQueue") {
            "${BugReportQueue.pending(ctx).size} left ${BugReportQueue.remainingToday(ctx)}"
        }
        field("unreadyDecisions") {
            SilenceLog.get(ctx, SilenceLog.UNREADY_DECISIONS).total.toString()
        }
        // ⚠️ The half of `unreadyDecisions` that is actually a fault: the window was entered with
        // an EMPTY snapshot, so "not blocked" was a shrug rather than an answer. Read the pair
        // together — the first alone says only how often the watcher restarts.
        field("unreadyBlind") {
            SilenceLog.get(ctx, SilenceLog.UNREADY_BLIND).total.toString()
        }
        // "12 shut, 2 blind" — Shorts dismissals where the reel was confirmed closed before
        // leaving, against ones where it could not be confirmed and the walk pressed nothing.
        // Whether BACK actually pops YouTube's reel is a fact about someone else's app on his
        // phone, so it cannot be tested here and is measured instead. A rising "blind" against a
        // flat "shut" means the reel markers or the BACK behaviour have moved.
        field("shortsExit") {
            val shut = SilenceLog.get(ctx, SilenceLog.SHORTS_EXIT_CLOSED).total
            val blind = SilenceLog.get(ctx, SilenceLog.SHORTS_EXIT_BLIND).total
            if (shut == 0 && blind == 0) "none yet" else "$shut shut, $blind blind"
        }
        // The pull side of liveness. A non-zero streak in a report is the watcher saying, at the
        // moment the report was written, that it could not read a lit screen — which is the
        // "alive but deaf" case that used to take two hours to admit to.
        field("probeStreak") { ServiceHealth.probeFailStreak(ctx).toString() }
        // Did the binding ever come down in an orderly way? An OEM force-stop never reaches
        // onDestroy, so "no" alongside outages means the process is being killed outright.
        // How often the alarm caught WorkManager not running. A climbing number here means the
        // scheduler every other background check depends on is being throttled or killed, which
        // is a live hypothesis for the outages and has never been measurable.
        field("workerSilent") { ProtectionPulse.silentCount(ctx).toString() }
        field("unbindSeen") {
            val n = ServiceHealth.unbindCount(ctx)
            if (n == 0) "never" else "$n"
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
        // The inside view of aliveButDeaf: how often the heartbeat found the watcher silent for
        // three minutes and re-posted its event mask, and how often that re-post itself threw.
        // "12/2" reads as twelve nudges, two of which found the binding already gone.
        field("revives") {
            "${ServiceHealth.reviveCount(ctx)}/${ServiceHealth.reviveFailCount(ctx)}"
        }
        // ⚠️ The line that says whether the self-repair is a repair at all. `revives` counts
        // nudges that did not throw — 67 of 67 on a phone losing hours of blocking. This counts
        // the ones after which an event actually arrived. "3/24" is a placebo.
        field("revivesHelped") {
            "${ServiceHealth.reviveHelpedCount(ctx)}/${ServiceHealth.reviveFutileCount(ctx)}"
        }
        // ⚠️ **Read this first on any recovery question.** warm/cold: warm means our process was
        // already awake when Android reconnected the watcher, so something woke it and the
        // reconnection followed — a lever the app could pull itself instead of waiting a quarter
        // of an hour. Cold means the reconnection is what started us and Android acted alone.
        // Counted only on a rebind that ended a real stoppage.
        field("reboundWake") {
            "${ServiceHealth.reboundWarmCount(ctx)}/${ServiceHealth.reboundColdCount(ctx)}"
        }
        // onInterrupt, which used to be an empty body. Not a failure by itself; a number that
        // moves either side of an outage is the first description anyone has of what precedes one.
        field("interrupts") { ServiceHealth.interruptCount(ctx).toString() }
        field("healthErrors") { ServiceHealth.errorCount(ctx).toString() }
        // The tag only. ServiceHealth's full line contains the exception message, which is where
        // a blocked word gets quoted back — see BugReport's contract.
        ServiceHealth.lastErrorWhere(ctx)?.let { w -> field("lastErrorWhere") { w } }
        // *When*, not just where and how many. "3 errors, last one four months ago" and "3 errors,
        // last one two minutes ago" were the same report until now, and only one of them is about
        // the thing being reported. Absent when nothing has ever thrown.
        ServiceHealth.lastErrorAt(ctx).takeIf { it > 0L }?.let { at ->
            field("lastErrorMin") { ((System.currentTimeMillis() - at) / 60_000L).toString() }
        }
        // How long since the watcher was last known to be alive at all, as opposed to last
        // *hearing* something ([lastEventMin]). The two together separate a phone left on the side
        // from a watcher that stopped: a quiet hour with a fresh heartbeat is somebody not using
        // their phone, and a quiet hour with a stale one is the failure.
        field("aliveMin") {
            val at = ServiceHealth.lastAliveAt(ctx)
            if (at <= 0L) "never" else ((System.currentTimeMillis() - at) / 60_000L).toString()
        }
        // Android's own boot counter. Reboots are invisible to every other field here, and half
        // the outage hypotheses are about what a restart does — this is what makes "it stops after
        // the phone restarts" checkable rather than a feeling. `-1` means it could not be read,
        // which is itself worth knowing: DeviceBoot's KDoc explains what a lost boot count breaks.
        field("boots") { DeviceBoot.count(ctx).toString() }
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
        // --- the last stoppage, on EVERY report ---
        // These used to be attached only to the automatic outage report, which meant the one
        // report shape the owner writes himself — "it stopped working" — carried no trace of the
        // stoppage he was describing. He asked why, and he was right: the diagnostics screen shows
        // him this and the report he sends from the same app did not. Same key names as
        // [reportOutage]'s own map, so on an outage report that map still wins and describes the
        // episode being filed rather than the newest one on record.
        OutageLog.last(ctx)?.let { last ->
            field("outageAt") { "${last.startedAt / 1000}" }
            field("outageMin") { minutesOrUnknown(last.durationMs) }
            field("outageDetectMin") { minutesOrUnknown(last.detectedAfterMs) }
            field("outageDeaf") { "${last.aliveButDeaf}" }
            field("outagePreceded") { last.precededBy }
            field("outageDetectedBy") { last.detectedBy }
            field("outageCount") { OutageLog.totals(ctx).count.toString() }
        }
        // Reported rather than hidden: a bare report and a healthy one must never look alike.
        if (failed > 0) out["fieldErrors"] = failed.toString()
        return out
    }

    /** Records an error for later sending. Safe to call from anywhere, including the watcher. */
    fun report(context: Context, where: String, t: Throwable) {
        if (!enabled()) return
        val watch = watchReading(context)
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
                    context = appContext(context, watch),
                    recentBlocks = BlockLog.recent(context),
                    recentOutages = OutageLog.recent(context),
                    healthFacts = healthLines(context, watch),
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
        // No watchdog reading here on purpose: a profile is about what this phone IS, not how
        // blocking is doing, and it is filed from `onResume` on every launch. Taking the usage
        // walk for a report that would not print it would be paying the most expensive read in
        // the file for nothing.
        runCatching {
            BugReportQueue.enqueue(
                context,
                BugReport.fromProfile(
                    appVersion = BuildConfig.VERSION_NAME,
                    flavor = BuildConfig.FLAVOR,
                    androidSdk = Build.VERSION.SDK_INT,
                    device = describeDevice(),
                    context = DeviceProfile.reportContext(context),
                    // No watchdog reading is taken for a profile, so this passes none — the
                    // health facts still gather cheaply from prefs, and they include the delivery
                    // verdicts, which is the whole reason a profile is worth reading right now.
                    healthFacts = healthLines(context, watch = null),
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
     * Called from the watchdog on any exit from STALLED, usually in a background worker, so there
     * is no flush here. It goes out on the next app open — which after an outage is soon, because
     * opening the app is how he fixes it.
     *
     * @param endedBy how the outage stopped — `recovered`, `switched-off` or `paused`. Only the
     *   first means blocking came back. Without it the other two read as recoveries, which would
     *   overstate how well the app repairs itself in exactly the log built to measure that.
     */
    fun reportOutage(context: Context, episode: OutageLog.Episode, endedBy: String) {
        if (!enabled()) return
        val watch = watchReading(context)
        runCatching {
            BugReportQueue.enqueue(
                context,
                BugReport.fromOutage(
                    appVersion = BuildConfig.VERSION_NAME,
                    flavor = BuildConfig.FLAVOR,
                    androidSdk = Build.VERSION.SDK_INT,
                    device = describeDevice(),
                    context = appContext(context, watch) + mapOf(
                        // Seconds rather than millis: this only has to identify the episode and
                        // stay readable, and the context cap is 24 characters.
                        "outageAt" to "${episode.startedAt / 1000}",
                        "outageMin" to minutesOrUnknown(episode.durationMs),
                        "outageDetectMin" to minutesOrUnknown(episode.detectedAfterMs),
                        "outageDeaf" to "${episode.aliveButDeaf}",
                        "outagePreceded" to episode.precededBy,
                        "outageEnded" to endedBy,
                        // Which detector caught it. Read alongside outageDetectMin: the same gap
                        // means something completely different depending on which arm produced it.
                        "outageDetectedBy" to episode.detectedBy,
                        "outageCount" to "${OutageLog.totals(context).count}",
                    ),
                    recentBlocks = BlockLog.recent(context),
                    recentOutages = OutageLog.recent(context),
                    healthFacts = healthLines(context, watch),
                ),
            )
        }
    }

    /** Whole minutes, or `?` for the values [OutageLog] marks unmeasurable with -1. */
    /**
     * The blocker's own reading of itself, already turned into sentences.
     *
     * Rendered here rather than in [BugReport] because the interpretation belongs to
     * [HealthFacts], which both this and the diagnostics screen read — the two must never be able
     * to disagree about the same phone, which is the rule [DeviceProfile] exists to enforce for
     * the device facts. Wrapped because a report is never allowed to be the thing that breaks:
     * a failure here costs the section, not the report.
     */
    private fun healthLines(
        ctx: Context,
        watch: ProtectionWatchdog.Reading?,
    ): List<String> = runCatching {
        HealthFacts.render(HealthFacts.verdicts(HealthReader.read(ctx, watch = watch)))
    }.getOrDefault(emptyList())

    /**
     * The one expensive reading a report needs, taken once and shared.
     *
     * [ProtectionWatchdog.read] walks the usage-event stream, so it is the costliest thing in a
     * report by a wide margin — and both halves of a report want it. Taking it here means the
     * crash handler pays for one walk instead of two.
     */
    private fun watchReading(ctx: Context): ProtectionWatchdog.Reading? =
        runCatching { ProtectionWatchdog.read(ctx) }.getOrNull()

    private fun minutesOrUnknown(ms: Long) = if (ms < 0) "?" else "${ms / 60_000}"

    /** Records what the owner typed, and tries to send it straight away. */
    fun reportNote(context: Context, note: String, kind: String? = null) {
        if (!enabled()) return
        val watch = watchReading(context)
        // Built off the main thread because this shape reads the database — see [ruleCounts].
        // The screen has already thanked him by the time this runs, which is correct: whether
        // the report reaches the tracker is not something he should be made to wait on.
        scope.launch { buildNote(context, note, kind, watch) }
    }

    private suspend fun buildNote(
        context: Context,
        note: String,
        kind: String?,
        watch: ProtectionWatchdog.Reading?,
    ) {
        val rules = ruleCounts(context)
        runCatching {
            BugReportQueue.enqueue(
                context,
                BugReport.fromNote(
                    note = note,
                    appVersion = BuildConfig.VERSION_NAME,
                    flavor = BuildConfig.FLAVOR,
                    androidSdk = Build.VERSION.SDK_INT,
                    device = describeDevice(),
                    // The moment he pressed Send, which is what [BugReport.dedupeKey] identifies a
                    // typed report by. Added here rather than in [appContext] because it means
                    // "this send" — every other report shape has its own identity already, and a
                    // crash report carrying a send time would be claiming something untrue.
                    context = appContext(context, watch) + rules + buildMap {
                        put("noteAt", "${System.currentTimeMillis() / 1000}")
                        // One of our own three literals, chosen by a tap, or absent. Absent is the
                        // ordinary case and must stay unremarkable: the chips are optional, and a
                        // report with no chip is a complete report.
                        kind?.let { put("reportKind", it) }
                    },
                    recentBlocks = BlockLog.recent(context),
                    recentOutages = OutageLog.recent(context),
                    healthFacts = healthLines(context, watch),
                ),
            )
            flush(context)
        }
    }

    /**
     * Files the weekly health summary, if this is the first app open of a new week.
     *
     * **Not scheduled.** Every background check in this app is a WorkManager job, and WorkManager
     * stopping is one of the live suspects for the outages — `ProtectionPulse` exists only to
     * measure how often it happens. Hanging the health summary off the component under suspicion
     * would put the report inside the failure it is meant to describe, which this file's own KDoc
     * forbids. So it rides on `onResume`, the same way [reportDeviceProfile] rides on "once per
     * phone per build": no worker, no alarm, no permission, and nothing new that can break.
     *
     * The cost is stated rather than hidden: a week he never opens the app files nothing, so the
     * next summary carries `weeksSkipped` and says so out loud. A gap means "not opened" or "not
     * getting through" and this deliberately does not pretend to know which.
     */
    fun reportWeekly(context: Context) {
        if (!enabled()) return
        scope.launch { buildWeekly(context) }
    }

    private suspend fun buildWeekly(context: Context) {
        runCatching {
            val week = currentWeek()
            val last = SettingsStore.lastWeeklyReport(context)
            if (last == week) return
            // First run on an install writes the marker without filing: there is no week to
            // summarise yet, and a summary of nothing would train the eye to skip these.
            if (last.isBlank()) {
                SettingsStore.setLastWeeklyReport(context, week)
                return
            }
            val watch = watchReading(context)
            BugReportQueue.enqueue(
                context,
                BugReport.fromWeekly(
                    appVersion = BuildConfig.VERSION_NAME,
                    flavor = BuildConfig.FLAVOR,
                    androidSdk = Build.VERSION.SDK_INT,
                    device = describeDevice(),
                    context = appContext(context, watch) + ruleCounts(context) + mapOf(
                        "weekOf" to week,
                        "weeksSkipped" to "${weeksBetween(last, week)}",
                    ),
                    recentOutages = OutageLog.recent(context),
                    healthFacts = healthLines(context, watch),
                ),
            )
            // Written whether or not the enqueue took it. A queue that is full or capped must not
            // make the app retry the same week on every single open for the rest of the week.
            SettingsStore.setLastWeeklyReport(context, week)
        }
    }

    /**
     * The owner's configuration, **as counts only** — and never on the error path.
     *
     * [appContext]'s KDoc refuses to touch Room on purpose: it runs inside the crash handler,
     * where the app is already in trouble and a synchronous query is a new way to make things
     * worse. That stands. This is the other half of the split — the report shapes that are built
     * from a healthy app (the note he sends, the weekly summary) can afford one read, and they
     * are the shapes that need it.
     *
     * ⚠️ **Do not "tidy" this by folding it into [appContext].** The split is the point, and a
     * crash report must keep reading nothing but preferences.
     *
     * The best line here is `limits`. "It didn't block X" is the commonest thing he reports and
     * the commonest innocent explanation is that the rule existed and the allowance had not run
     * out yet — which no report could previously distinguish from the blocker being broken.
     *
     * ⚠️ **Counts, never rows.** `Schedule` carries a name, a wifi SSID and the owner's
     * latitude/longitude; `AppRule` carries a package name and an app label; a blocked keyword IS
     * its own primary key. None of those may ever appear in a report — see [BugReport]'s contract.
     */
    private suspend fun ruleCounts(ctx: Context): Map<String, String> = runCatching {
        val db = com.appblocker.data.BlockerDatabase.get(ctx)
        val rules = db.appRuleDao().getAll().first()
        val schedules = db.scheduleDao().getAll().first()
        val words = db.blockedKeywordDao().getAll().first()
        val limits = rules.filter { it.dailyLimitMinutes >= 0 }
        val used = runCatching { UsageTracker.minutesByPackageToday(ctx) }.getOrDefault(emptyMap())
        val spent = limits.count { (used[it.packageName] ?: 0) >= it.dailyLimitMinutes }
        mapOf(
            "ruleBlocked" to rules.count { it.isBlocked }.toString(),
            "ruleAllowed" to rules.count { it.isAllowed }.toString(),
            "limits" to "${limits.size}, $spent spent",
            "schedules" to "${schedules.size}, ${schedules.count { it.enabled }} on",
            "filterEntries" to words.size.toString(),
        )
    }.getOrDefault(emptyMap())

    /** `2026-W35`. ISO week, so a summary's name sorts and compares as plain text. */
    private fun currentWeek(): String {
        val c = java.util.Calendar.getInstance()
        c.firstDayOfWeek = java.util.Calendar.MONDAY
        c.minimalDaysInFirstWeek = 4
        val week = c.get(java.util.Calendar.WEEK_OF_YEAR)
        val year = c.get(java.util.Calendar.YEAR)
        return "%d-W%02d".format(year, week)
    }

    /** How many whole weeks were skipped between two labels; 0 when they are consecutive or the
     *  labels cannot be compared (a year boundary counts as consecutive rather than guessing). */
    private fun weeksBetween(from: String, to: String): Int = runCatching {
        val (fy, fw) = from.split("-W").let { it[0].toInt() to it[1].toInt() }
        val (ty, tw) = to.split("-W").let { it[0].toInt() to it[1].toInt() }
        if (fy != ty) 0 else (tw - fw - 1).coerceAtLeast(0)
    }.getOrDefault(0)

    /**
     * Sends everything queued, one at a time. Called on app resume, where a network is most
     * likely and where taking a moment costs nothing.
     */
    fun flush(context: Context) {
        if (!enabled()) return
        val app = context.applicationContext
        // Claimed BEFORE the launch, so two resumes in the same instant cannot both get in.
        // A dropped flush costs nothing: the drain already running will send whatever is queued,
        // and anything enqueued after it has passed by goes out on the next resume.
        if (!flushing.compareAndSet(false, true)) return
        scope.launch {
            try {
                runCatching {
                    // **The daily cap is checked here as well as at enqueue, and it has to be.**
                    // `remainingToday` only moves when a report is *sent*, and nothing is sent
                    // until the next app open — so a bad day queues reports against a counter that
                    // is still reading zero spent, and one flush then posts the whole queue at
                    // once. The cap said 12 and the real ceiling was MAX_PENDING, 20. Checking it
                    // per report is what makes the KDoc's "backstop that cannot be reasoned
                    // around" true; what does not go out stays queued for tomorrow, which is the
                    // intended cost.
                    // Newest first. A backlog drains at MAX_PER_DAY, so the order decides
                    // whether today's twelve describe this morning or last week.
                    for (report in BugReportQueue.sendOrder(BugReportQueue.pending(app))) {
                        if (BugReportQueue.remainingToday(app) <= 0) break
                        val outcome = post(report)
                        // Recorded for every attempt, delivered or not. This is the line that
                        // turns "nothing is arriving" from a mystery into a sentence on his screen.
                        BugReportQueue.recordAttempt(app, outcome)
                        if (delivered(outcome)) BugReportQueue.markSent(app, report)
                        else BugReportQueue.markFailed(app, report)
                    }
                }.onFailure {
                    // Swallowed on purpose, and NOT reported — see the class KDoc.
                    Log.w(TAG, "flush failed", it)
                }
            } finally {
                // In a finally, not on the happy path. `runCatching` cannot catch a coroutine
                // cancellation, and a guard released only on success would wedge reporting off for
                // the life of the process — silently, which is the one failure this file exists
                // to stop.
                flushing.set(false)
            }
        }
    }

    /**
     * Sends one report and **says what happened**.
     *
     * It used to return a bare `Boolean`, which threw away the only fact that could have explained
     * six days of silence in Aug-Sep 2026: the app's own DNS filter blocks dynamic-DNS domains as a
     * category, so the phone could not resolve its own reporting host and every send died with an
     * `UnknownHostException`. "Failed" and "was refused" and "could not find the server" are three
     * different diagnoses and they all looked identical — to the owner and to me.
     *
     * @return an HTTP status as text ("201", "403"), or the exception's class name when the
     *   request never got an answer at all. **Never a response body**: a failure body can echo what
     *   was submitted, and [BugReport]'s privacy contract governs what may be stored just as much
     *   as what may be sent.
     */
    private fun post(report: BugReport): String = runCatching {
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
        code.toString()
    }.getOrElse { it.javaClass.simpleName }

    /** GitHub answers 201 on create. Any 2xx is done; anything else stays queued, so a
     *  misconfigured secret or an unreachable host retries rather than discarding the report. */
    internal fun delivered(outcome: String): Boolean =
        outcome.toIntOrNull()?.let { it in 200..299 } == true
}
