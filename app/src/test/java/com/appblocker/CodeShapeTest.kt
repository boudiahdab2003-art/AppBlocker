package com.appblocker

import com.appblocker.data.BugReport
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Bug shapes, enforced against the source rather than described in a document.**
 *
 * Every rule here was already written down before it was broken. `docs/BLOCKING_INVARIANTS.md` has
 * said since its first version that the recurring failure is *"the rule had been written down as a
 * fact about one screen and never grepped for"* — and then invariants 32-35 happened anyway, on
 * 29 Aug 2026, in code that was three days old. Two of the four had a correct sibling implementation
 * sitting forty lines away in the same file.
 *
 * So these are not comments. They read the shipped `.kt` files and fail the build when a shape comes
 * back. A prose invariant tells the next reader what to do; this tells them they got it wrong.
 *
 * ⚠️ **When one of these fails, the fix is almost never to widen the test.** It is the same
 * temptation `BugReportTest`'s key tripwire warns about: the check exists precisely because the
 * mistake looks reasonable at the moment you are making it. If a genuinely correct new case does not
 * fit, add it to that check's allow-list *with the reason written down*, the way the existing
 * entries are.
 */
class CodeShapeTest {

    private fun source(path: String): File =
        listOf(File("src/main/java/com/appblocker/$path"), File("app/src/main/java/com/appblocker/$path"))
            .firstOrNull { it.isFile }
            ?: error("cannot find $path from ${File(".").absolutePath}")

    private fun sourceTree(): List<File> =
        listOf(File("src/main/java/com/appblocker"), File("app/src/main/java/com/appblocker"))
            .firstOrNull { it.isDirectory }
            ?.walkTopDown()?.filter { it.isFile && it.extension == "kt" }?.toList()
            ?: error("cannot find the source tree from ${File(".").absolutePath}")

    // ---- invariant 35 ------------------------------------------------------------------------

    /**
     * **A self-rescheduling loop may not re-arm itself from inside its own error handler.**
     *
     * `recheckRunnable` re-posted itself as the last line inside `guarded("recheck")`, so one
     * swallowed throw ended the 30-second mid-use loop for good — taking limit crossings, schedule
     * starts, Pomodoro flips and stale-cover releases with it, silently, until the next foreground
     * change happened to re-arm it. `heartbeatRunnable`, forty lines above in the same file, had
     * always re-posted *outside* its guard. Two answers to one question, in one file.
     *
     * The scan is deliberately crude: find every `postDelayed(this`, walk back up counting braces,
     * and fail if the enclosing block was opened by a `guarded(` or a `runCatching {`.
     */
    @Test
    fun `a runnable never re-posts itself inside a guard`() {
        val offenders = mutableListOf<String>()
        for (file in sourceTree()) {
            val text = file.readText()
            var from = 0
            while (true) {
                val at = text.indexOf("postDelayed(this", from)
                if (at < 0) break
                from = at + 1
                // Walk backwards to the innermost unclosed `{`, then look at what opened it.
                var depth = 0
                var i = at
                while (i > 0) {
                    i--
                    when (text[i]) {
                        '}' -> depth++
                        '{' -> if (depth == 0) break else depth--
                    }
                }
                // What opened that brace? `guarded(` / `runCatching` with no brace between it and
                // the `{`, so a multi-line `guarded(\n ctx, "x"\n) {` is caught too, and an
                // unrelated earlier guard in the same function is not.
                val opener = text.substring(maxOf(0, i - 200), i)
                if (Regex("""(guarded\s*\(|runCatching)[^{}]*$""").containsMatchIn(opener)) {
                    offenders += "${file.name}: ${text.take(at).count { it == '\n' } + 1}"
                }
            }
        }
        assertEquals(
            "A runnable re-posts itself inside a guarded/runCatching block. One swallowed throw " +
                "then ends the loop permanently — see invariant 35, and heartbeatRunnable for the " +
                "shape that is correct: capture the decision inside the guard, re-post outside it.",
            emptyList<String>(), offenders,
        )
    }

    // ---- invariant 32 ------------------------------------------------------------------------

    /**
     * **The two update-pause flags may only be moved through the one atomic door.**
     *
     * They are a pair: `updatePausePending` is the intent, `updatePaused` is the decision. Written
     * separately they can survive half-applied, and — the part that actually bit — the Reactivate
     * tap cleared the decision while leaving the intent, which every later service connect re-read.
     * Blocking switched itself back off on the next boot, update or space switch, with the
     * accessibility switch still reading ON.
     *
     * `SettingsStore.writeUpdatePause` is the only correct way to move them. This fails if a new
     * call site reaches for either single-flag setter again, which is exactly how the bug would
     * come back: writing one of them looks obviously right in isolation.
     */
    @Test
    fun `the update-pause flags are only written through writeUpdatePause`() {
        val allowed = setOf("SettingsStore.kt", "UpdatePause.kt")
        val offenders = sourceTree()
            .filter { it.name !in allowed }
            .filter { f ->
                val t = f.readText()
                t.contains("setUpdatePaused(") || t.contains("setUpdatePausePending(")
            }
            .map { it.name }
        assertEquals(
            "Something writes one update-pause flag on its own. They are a pair — use " +
                "SettingsStore.writeUpdatePause, or UpdatePause.reactivate for the Reactivate tap. " +
                "See invariant 32: an instruction must not outlive the decision it asked for.",
            emptyList<String>(), offenders,
        )
    }

    // ---- invariants 9 and 26 -----------------------------------------------------------------

    /**
     * **The liveness stamps may not be measured with a clock the phone can move.**
     *
     * `ServiceHealth`'s write-throttle subtracted two `System.currentTimeMillis()` readings. After
     * the wall clock moved backwards — which phones do at start-up, before they have checked the
     * time — that subtraction is negative forever, `health_last_event_at` stopped advancing, and
     * `protectionState` then measured against a stamp from the future and answered OK. The one
     * detector written for "it says it's on and blocks nothing" went blind, in the file whose whole
     * job is to notice that.
     *
     * Storing a wall-clock instant is fine and necessary — a report has to say *when*. **Subtracting
     * two of them is the mistake**, and that is what this looks for.
     */
    @Test
    fun `ServiceHealth never does wall-clock arithmetic`() {
        val text = source("data/ServiceHealth.kt").readText()
        val code = text.lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
        val bad = code.filter { Regex("""System\.currentTimeMillis\(\)\s*-|-\s*System\.currentTimeMillis\(\)""").containsMatchIn(it) }
        assertEquals(
            "ServiceHealth subtracts wall-clock readings. A backward clock change then freezes the " +
                "stamp the whole watchdog reads — use SystemClock.elapsedRealtime for any interval " +
                "(invariant 9). Storing an instant is fine; measuring with one is not.",
            emptyList<String>(), bad,
        )
    }

    // ---- invariant 34 ------------------------------------------------------------------------

    /**
     * **The browser sets that gate a protection are never read raw.**
     *
     * `realBrowserPackages` was allowed to be empty on the stated grounds that an empty set "costs
     * only the blunt block, never filtering". That was true when written, and stopped being true
     * when the danger zone (v1.139) and the DNS-filter browser shutdown (v1.142) started gating on
     * it. One swallowed PackageManager failure at connect then switched off two whole protections
     * for the life of the service.
     *
     * Reads go through `isRealBrowserPkg`, which re-detects an empty set rather than believing it.
     */
    @Test
    fun `nothing tests realBrowserPackages membership directly`() {
        val text = source("service/BlockerAccessibilityService.kt").readText()
        val direct = Regex("""\bin\s+realBrowserPackages""").findAll(text)
            .map { text.take(it.range.first).count { c -> c == '\n' } + 1 }
            .filterNot { line ->
                // The accessor's own membership test is the one legitimate use.
                val body = text.lines().subList(maxOf(0, line - 12), line).joinToString("\n")
                body.contains("private fun isRealBrowserPkg")
            }
            .toList()
        assertTrue(
            "A block decision reads realBrowserPackages directly, at line(s) $direct. An empty set " +
                "is an unanswered question, not a 'no' — go through isRealBrowserPkg. Invariant 34.",
            direct.isEmpty(),
        )
    }

    // ---- invariant 36 ------------------------------------------------------------------------

    /**
     * **Every background job the watcher owns is cancelled when the screen goes off.**
     *
     * A job outlives the moment it was started for. `shortsScanJob` was the one thing `onScreenOff`
     * never stopped, so locking the phone mid-Shorts let a scan finish *after* the whole cleanup had
     * run and raise a cover with nothing left to take it down — a cover stranded over whatever was
     * on screen at the next unlock (v1.98). The Shorts *exit* added on 30 Aug 2026 is the same shape
     * with a sharper edge: left running, it would keep pressing BACK into a phone that had just been
     * locked.
     *
     * `onDestroy` needs no equivalent check — every one of these is launched on `scope`, and it
     * calls `scope.cancel()`. Screen-off is the case that has to name them one by one, because the
     * service keeps running and there is nothing structural to catch an omission.
     *
     * ⚠️ Adding a job and *not* listing it here is exactly the mistake this catches, so a new job
     * belongs in `onScreenOff`, not in this test's exceptions. There are none.
     */
    @Test
    fun `every background job is cancelled when the screen goes off`() {
        val text = source("service/BlockerAccessibilityService.kt").readText()
        val jobs = Regex("""@Volatile\s+private\s+var\s+(\w+):\s*Job\?""")
            .findAll(text).map { it.groupValues[1] }.toList()
        assertTrue(
            "Expected to find the watcher's Job fields; the declaration shape must have changed, " +
                "which means this check is no longer looking at anything.",
            jobs.size >= 3,
        )
        val screenOff = text.substringAfter("private fun onScreenOff()").substringBefore("\n    }")
        val unstopped = jobs.filterNot { screenOff.contains("$it?.cancel()") }
        assertTrue(
            "onScreenOff does not cancel $unstopped. A job that outlives screen-off finishes after " +
                "the cleanup it was meant to be part of — raising a cover nothing will take down, " +
                "or pressing BACK into a locked phone. Cancel it there; do not add it here.",
            unstopped.isEmpty(),
        )
        assertTrue(
            "onDestroy must cancel the scope: it is what makes enumerating jobs there unnecessary.",
            text.substringAfter("override fun onDestroy()").contains("scope.cancel()"),
        )
    }

    // ---- the dedupe key's own inputs ---------------------------------------------------------

    /**
     * **Every context key a `dedupeKey` reads must survive `sanitizeContext`.**
     *
     * `dedupeKey` builds an outage's identity out of `context["outageAt"]`, and the sanitiser that
     * runs one step earlier dropped that key because it was never added to `ALLOWED_CONTEXT_KEYS`.
     * So every outage on every phone keyed as `outage:`, and `enqueue` discarded the second one as
     * a duplicate of the first — the app could report one stoppage for the life of the install,
     * while `OutageLog` exists precisely to measure how *often* blocking stops. Nothing failed: the
     * report was built correctly, then quietly lost the field that made it distinguishable, which
     * is the same shape as the `context`/`recentBlocks` loss `BugReportQueue` already documents.
     *
     * The key's own comment said the start stamp "is what makes them distinguishable at all". It
     * was right, and unenforced. This is the enforcement, and it covers keys not yet written.
     */
    @Test
    fun `a dedupe key never reads a field the sanitiser strips`() {
        val text = source("data/BugReport.kt").readText()
        val key = text.substringAfter("fun dedupeKey()").substringBefore("\n    }")
        val read = Regex("""context\["(\w+)"\]""").findAll(key).map { it.groupValues[1] }.toList()
        assertTrue(
            "Expected dedupeKey to read at least one context field; if it no longer does, this " +
                "check is looking at nothing and should be removed rather than left passing.",
            read.isNotEmpty(),
        )
        val allowed = BugReport.ALLOWED_CONTEXT_KEYS + BugReport.PROFILE_CONTEXT_KEYS
        val stripped = read.filterNot { it in allowed }
        assertTrue(
            "dedupeKey reads $stripped, which sanitizeContext removes before the key is built. " +
                "Every report of that kind will share one key and the queue will drop all but the " +
                "first as duplicates. Add the key to ALLOWED_CONTEXT_KEYS with its reason — do " +
                "not stop keying on it.",
            stripped.isEmpty(),
        )
    }
    /**
     * **Every field the report builder writes must survive `sanitizeContext`.**
     *
     * The check above guards only the keys `dedupeKey` reads. The general rule was available when
     * it was written and was not taken: a `field("x")` in `BugReportSender` whose name is on no
     * allow-list is built correctly and then thrown away one step later — silently, with the
     * report still arriving and still looking complete.
     *
     * It happened again immediately. `installId`, `space`, `scheduleCount`, `graceRecovers` and
     * `revivesHelped` — the two fields that separate the Second Space install from the main one,
     * and the two instruments the recovery investigation was waiting on — shipped in v1.152 and
     * v1.153 and reached no report at all. Reports #63-#80 carry `revives: 92/0` and nothing to
     * read it against. A release's worth of measurement produced no measurement, and the loss was
     * only visible by counting rows in a table that looked full.
     *
     * A wrong key here is not a crash and not a visibly broken report; it is a number nobody
     * collected, found weeks later. This covers the keys not yet written.
     */
    @Test
    fun `every reported field survives the sanitiser`() {
        val text = source("service/BugReportSender.kt").readText()
        val written = text.split("field(\"").drop(1).map { it.substringBefore(Char(34)) }
        assertTrue(
            "Expected BugReportSender to write its fields through field(\"name\"). If that helper " +
                "has been renamed, this check is reading nothing and must be rewritten rather " +
                "than left passing.",
            written.size > 20,
        )
        val allowed = BugReport.ALLOWED_CONTEXT_KEYS + BugReport.PROFILE_CONTEXT_KEYS
        val stripped = written.filterNot { it in allowed }.distinct()
        assertTrue(
            "BugReportSender writes $stripped, which sanitizeContext strips before the report is " +
                "sent. Nothing fails: the report still arrives and still looks complete, so the " +
                "number is simply never collected. Add each key to ALLOWED_CONTEXT_KEYS with the " +
                "reason it is safe to send — do not stop writing the field.",
            stripped.isEmpty(),
        )
    }

    // ---- invariant 36 ------------------------------------------------------------------------

    /**
     * **`flush` may not run twice at once.**
     *
     * Found on 1 Sep 2026 in the first reports ever delivered through the new relay. Thirteen
     * arrived and four were exact duplicates — and not a random four: the *back* of the queue,
     * 1.145 and 1.146, while 1.141-1.144 at the front came through once each. That is the
     * signature of a second flush reading `pending()` partway through the first, because
     * `markSent` only removes a report *after* its POST returns.
     *
     * It is not cosmetic. `MAX_PER_DAY` is 12, and every duplicate spends one — so on the one day
     * a six-day backlog went out, a third of his daily budget was burnt re-sending reports we
     * already had, and real ones stayed queued behind them.
     *
     * `MainActivity` calls `flush` on resume, so two resumes inside one slow drain is an ordinary
     * Tuesday, not a race you have to go looking for. The guard must be claimed *before* the
     * launch and released in a `finally`, or one throw wedges reporting off until the next boot.
     */
    @Test
    fun `flush claims a guard before launching and releases it in a finally`() {
        val text = source("service/BugReportSender.kt").readText()
        val body = text.substringAfter("fun flush(context: Context) {", "")
        assertTrue("BugReportSender no longer has a flush(context) to check", body.isNotEmpty())
        val beforeLaunch = body.substringBefore("scope.launch", "")

        assertTrue(
            "flush must claim a re-entrancy guard with compareAndSet BEFORE scope.launch, or two " +
                "app resumes drain the same queue snapshot and every report still in flight is " +
                "sent twice — spending the daily cap on duplicates.",
            "compareAndSet(false, true)" in beforeLaunch,
        )
        assertTrue(
            "flush's guard must be released in a finally block. Released on the happy path only, " +
                "one swallowed throw leaves it claimed forever and no report is ever sent again.",
            Regex("""finally\s*\{[^}]*\.set\(false\)""").containsMatchIn(body),
        )
    }
    // ---- invariant 37 ------------------------------------------------------------------------

    /**
     * **Opening and closing an outage episode must happen under a lock.**
     *
     * The same 1 Sep 2026 backlog that exposed invariant 36 showed a *second* duplicate, in a
     * different place: the phone's own stoppage log listed `at=Mon 21:02 down=14min
     * noticedAfter=2min` twice, identical in every field. Two closes of one episode.
     *
     * `begin` and `end` are both check-then-act on `KEY_OPEN_STARTED` — read the key, decide,
     * then write. Nothing held a lock between the read and the write, and **seven** independent
     * things call `ProtectionWatchdog.checkAndNotify`: the boot receiver, the notification
     * listener, the alarm receiver, the WorkManager job, the quick-block tile, the UI on resume
     * and the service itself. Two of them landing together is ordinary, not exotic.
     *
     * The cost is not one ugly line. `end` also bumps `KEY_TOTAL_COUNT` and `KEY_TOTAL_MS`, so a
     * double close inflates the two figures the whole outage investigation is being judged on —
     * it reports more stoppages and more lost time than actually happened. A log that overstates
     * the problem is as useless as one that hides it.
     */
    @Test
    fun `an outage episode is opened and closed under a lock`() {
        val text = source("data/OutageLog.kt").readText()
        for (name in listOf("begin", "end")) {
            val start = text.indexOf("    fun $name(")
            assertTrue("OutageLog no longer has a $name( to check", start >= 0)
            val tail = text.substring(start)
            val body = tail.substringBefore(System.lineSeparator() + "    fun ", tail)
            val guard = Regex("""synchronized\s*\(""").containsMatchIn(body) ||
                Regex("""lock\.with""").containsMatchIn(body)
            assertTrue(
                "OutageLog.$name touches KEY_OPEN_STARTED without holding a lock. It is a " +
                    "check-then-act, and seven separate callers reach it, so two can open or " +
                    "close the same episode at once — which duplicates the line in his log AND " +
                    "double-counts outageCount and outageTotalMin.",
                guard,
            )
        }
    }
    // ---- invariant 38 ------------------------------------------------------------------------

    /**
     * **A backlog is sent newest first, and `flush` must go through `sendOrder` to do it.**
     *
     * `MAX_PER_DAY` is 12. On 2 Sep 2026 exactly twelve reports went out and eleven stayed queued,
     * oldest first — so what arrived described the previous week while the stoppage he had just
     * watched happen sat behind them. He asked whether it had arrived. It had not.
     *
     * The ordering itself is pinned by `BugReportQueueOrderTest`; this stops `flush` quietly going
     * back to iterating `pending()` directly, which is the shape that produced the wrong order and
     * reads perfectly naturally.
     */
    @Test
    fun `flush drains the queue through sendOrder`() {
        val text = source("service/BugReportSender.kt").readText()
        val start = text.indexOf("    fun flush(context: Context) {")
        assertTrue("BugReportSender no longer has a flush(context) to check", start >= 0)
        val tail = text.substring(start)
        val body = tail.substringBefore(System.lineSeparator() + "    fun ", tail)
        assertTrue(
            "flush must iterate BugReportQueue.sendOrder(...), not pending() directly, or a " +
                "backlog spends the whole daily cap on the oldest reports and the ones describing " +
                "what the phone is doing now wait days.",
            "sendOrder(" in body,
        )
    }
    // ---- invariant 39 ------------------------------------------------------------------------

    /**
     * **Everything the rules flow carries needs a fallback for the window before it emits.**
     *
     * `RuleSnapshot` closed that hole for app rules in v1.143 and the reasoning was written down
     * carefully. It was never applied to the other three things arriving on the same `combine`
     * flow — the Strict session, the blocked words, the schedules — so all three stayed empty
     * for the same window, beside a file explaining exactly why empty is not an answer.
     *
     * The Strict session is the one that bit. `strictRemaining()` read five zeroed fields, so
     * `SessionClock` returned 0 and Strict Mode was simply not enforced on every bind. On the
     * owner's phone that is his main protection — his block log is mostly `why=strict` — and
     * the 2 Sep 2026 reports show 67 revives in two days, each one reopening it.
     *
     * This is the third time the shape has recurred (invariant 11, RuleSnapshot, here). Grep for
     * it rather than trusting the next reader to remember the file.
     */
    @Test
    fun `strictRemaining consults the Strict snapshot`() {
        val text = source("service/BlockerAccessibilityService.kt").readText()
        val start = text.indexOf("    private fun strictRemaining()")
        assertTrue("the service no longer has a strictRemaining() to check", start >= 0)
        val tail = text.substring(start)
        val body = tail.substringBefore(System.lineSeparator() + "    private fun ", tail)
        assertTrue(
            "strictRemaining must go through StrictSnapshot.sessionFor. Reading the five focus " +
                "fields directly means Strict is unenforced from every bind until Room's first " +
                "emission, and the report only ever counted that window, never closed it.",
            "StrictSnapshot.sessionFor(" in body,
        )
    }
    // ---- invariant 40 ------------------------------------------------------------------------

    /**
     * **Nothing decides anything from the raw `userKeywords` or `schedules` fields.**
     *
     * The companion to invariant 39. Rules, the Strict session, the blocked words and the
     * schedules all arrive on one `combine` flow, so all four are empty until it first emits —
     * and empty is not an answer, it is "we have not been told". `activeKeywords()` and
     * `activeSchedules()` fall back to the last known value for that window; reading the fields
     * directly walks straight back into the hole.
     *
     * The allow-list is the places that legitimately touch the raw field: its declaration, the
     * flow assigning it, the snapshot write, and the accessor itself.
     */
    @Test
    fun `the block decision reads keywords and schedules through the accessors`() {
        val text = source("service/BlockerAccessibilityService.kt").readText()

        // The two places a decision is actually made from these fields. Named exactly, because a
        // word-frequency scan over this file is hopeless: "schedules" appears in a dozen comments
        // and a debug log, and a check that cries wolf gets widened until it means nothing.
        assertTrue(
            "the BlockInputs built in blockReason must pass schedules = activeSchedules(). " +
                "Passing the raw field leaves every schedule unenforced from each bind until " +
                "Room's first emission.",
            "schedules = activeSchedules()," in text,
        )
        assertTrue(
            "the word scanners must take their list from activeKeywords(). Reading userKeywords " +
                "directly leaves keyword and site blocking off for that same window.",
            "else activeKeywords()" in text,
        )
        assertEquals(
            "both word scanners must go through activeKeywords(), not just one of them.",
            2,
            Regex("""else activeKeywords\(\)""").findAll(text).count(),
        )
        assertTrue(
            "activeKeywords()/activeSchedules() must actually consult the snapshots, or they are " +
                "just the raw fields under a longer name.",
            "keywordSnapshot.toList()" in text && "schedules.ifEmpty { scheduleSnapshot }" in text,
        )
    }
    // ---- invariant 41 ------------------------------------------------------------------------

    /**
     * **Every caller of the watchdog says who it is.**
     *
     * `calledBy` is what lets a closed episode record whether blocking came back on its own or
     * only once the app was opened — the question the whole recovery effort rests on. It has a
     * default, so a new call site that forgets compiles perfectly and silently files its
     * recoveries as `unknown`, quietly diluting the one measurement that matters.
     */
    @Test
    fun `every watchdog call says who is asking`() {
        val offenders = sourceTree()
            .filter { it.name != "ProtectionWatchdog.kt" }
            .flatMap { f -> f.readText().lines().map { f.name to it } }
            .filter { (_, line) -> "checkAndNotify(" in line && "calledBy" !in line }
            .map { (name, line) -> "$name: ${line.trim()}" }
        assertEquals(
            "these call the watchdog without saying who is asking, so any recovery they observe " +
                "is filed as \"unknown\":" +
                offenders.joinToString(System.lineSeparator(), System.lineSeparator()),
            emptyList<String>(),
            offenders,
        )
    }
    /**
     * **Every profile key the report acts on must be one the phone actually produces.**
     *
     * `DeviceProfile` writes the rows with `put("keepAlive", ...)`; `BugReport` decides from
     * `context["keepAlive"]` whether the phone is healthy, what to mark with a cross and what fix
     * to print. The name is spelled by hand at both ends and nothing joins them, so a rename on
     * the producing side does not break a build, does not throw, and does not empty the report --
     * the row simply stops matching, every phone reads healthy, and the report that exists to
     * catch a wrong guess about a phone becomes a report that can never catch one.
     *
     * A silent, permanent blindness is the worst failure this file guards against, and it is the
     * one that leaves the least evidence.
     */
    @Test
    fun `the report only acts on profile keys the device profile writes`() {
        val produced = source("data/DeviceProfile.kt").readText()
            .split("put(").drop(1)
            .map { it.trimStart().removePrefix("\n").trim().substringBefore(",").trim() }
            .filter { it.startsWith("\"") }
            .map { it.trim('"') }
            .toSet()
        assertTrue(
            "no put(\"key\", ...) rows found in DeviceProfile; this check is reading nothing",
            produced.size >= 5,
        )
        val report = source("data/BugReport.kt").readText()
        // The keys the report makes a DECISION from, not every string it happens to contain.
        val consumed = (
            Regex("""context\["(\w+)"\]""").findAll(report).map { it.groupValues[1] } +
                Regex("""key == "(\w+)"""").findAll(report).map { it.groupValues[1] } +
                Regex(""""(\w+)" in todo""").findAll(report).map { it.groupValues[1] }
            ).toSet()
        val profileish = consumed.filter { it in PROFILE_ONLY }
        assertTrue("expected the report to read some profile rows", profileish.isNotEmpty())
        val orphans = profileish.filterNot { it in produced }
        assertEquals(
            "these profile keys are read by BugReport and written by nothing in DeviceProfile, " +
                "so the row silently stops matching and every phone reports healthy: $orphans",
            emptyList<String>(),
            orphans,
        )
    }

    /** The profile rows the report reasons about, named here so the check above cannot drift. */
    private val PROFILE_ONLY = setOf(
        "uninstallGuard", "keepAlive", "browsersClaimUnproven", "uninstallHandler",
        "accessibilityScreen", "brand", "browsersKnown", "browsersClaimedReadable",
    )

    /**
     * **The daily cap may delay a report. It may never refuse one.**
     *
     * `enqueue` used to return false when `remainingToday` was spent, so a report was destroyed at
     * the door instead of being held — while `flush` already enforces the same cap per report on
     * the way out, and its own comment promises "what does not go out stays queued for tomorrow,
     * which is the intended cost". The changelog told the owner the same thing in his own words.
     * Both were false.
     *
     * It cost exactly the measurement the day was spent building: on 4 Sep 2026 two releases and a
     * backlog spent the twelve, and the first report from the build written to answer the open
     * question was refused here and is gone. There is no recovering it — that is what makes this a
     * check rather than a paragraph.
     *
     * A throttle may delay evidence. It may not delete it. The queue's own `MAX_PENDING` is what
     * bounds growth, and it drops the oldest rather than the newest.
     */
    @Test
    fun `the daily cap never refuses a report at the door`() {
        val text = source("data/BugReportQueue.kt").readText()
        val body = text.substringAfter("fun enqueue(", "").substringBefore("\n    }")
        assertTrue("BugReportQueue no longer has an enqueue to check", body.isNotEmpty())
        assertFalse(
            "enqueue consults the daily cap, so a report created once the day's sends are spent " +
                "is thrown away instead of waiting for tomorrow. The cap belongs in flush, which " +
                "already applies it per report on the way out. Delete the check here.",
            "remainingToday" in body || "MAX_PER_DAY" in body,
        )
    }

    /** And the send path must keep applying it, or the cap stops existing altogether. */
    @Test
    fun `the daily cap is still applied when sending`() {
        val text = source("service/BugReportSender.kt").readText()
        val at = text.indexOf("BugReportQueue.sendOrder(")
        assertTrue("flush no longer drains the queue in sendOrder; rewrite this check", at >= 0)
        val loop = text.substring(at, minOf(text.length, at + 600))
        assertTrue(
            "the send loop must stop once remainingToday is spent, or removing the check from " +
                "enqueue leaves no cap at all.",
            "remainingToday" in loop,
        )
    }

    // ---- invariant 44 ------------------------------------------------------------------------

    /**
     * **The watcher must close its own outage the moment Android binds it again.**
     *
     * `OutageLog.end` has one caller, `ProtectionWatchdog.checkAndNotify`, and `checkAndNotify`
     * had six — the boot receiver, the worker, the alarm, the notification listener, the tile and
     * the app's own resume. Not one of them was the watcher. So an episode stayed open until an
     * *outside* observer happened to run, and every one of those but the 25-minute alarm is a
     * WorkManager job, including the five-minute stalled repeat, on a phone reporting
     * `workerSilent: 140` and climbing.
     *
     * `outageMin` was therefore not how long blocking was down. It was how long until something
     * noticed it was back, and the difference went into the total the owner is shown: on
     * 4 Sep 2026 three episodes ran 15, 36 and 55 minutes against detection times of 2, 7 and 10.
     *
     * `onServiceConnected` running IS blocking coming back. Take that line out and the app is
     * back to guessing from the outside, silently, with every number still looking plausible.
     */
    @Test
    fun `the watcher closes its own outage when it is bound again`() {
        val text = source("service/BlockerAccessibilityService.kt").readText()
        val connect = text.substringAfter("override fun onServiceConnected() {", "")
        assertTrue("onServiceConnected is gone; this check is reading nothing", connect.isNotEmpty())
        val body = connect.substringBefore("override fun ")
        assertTrue(
            "onServiceConnected must tell the watchdog blocking is back " +
                "(ProtectionWatchdog.noteWatcherAlive with EndedBy.REBOUND). Without it the " +
                "episode stays open until some WorkManager job happens to look, and every " +
                "duration in the stoppage history is inflated by that wait.",
            "noteWatcherAlive" in body && "EndedBy.REBOUND" in body,
        )
    }

    /**
     * **And every such call says which honest ending it is.**
     *
     * The point of the entry point is to separate durations the app measured itself from ones it
     * inferred after the fact. A call that let the parameter fall to `unknown` would put an
     * accurate duration in the same bucket as the guesses, which is worse than not measuring:
     * the comparison the next release is judged on is exactly between those two buckets.
     */
    @Test
    fun `every watcher-alive call names how the outage ended`() {
        val calls = sourceTree()
            .filter { it.name != "ProtectionWatchdog.kt" }
            .flatMap { f ->
                f.readText().split("noteWatcherAlive(").drop(1)
                    .map { f.name to it.substringBefore(")").trim() }
            }
        assertTrue("nothing calls noteWatcherAlive; this check is reading nothing", calls.isNotEmpty())
        val offenders = calls
            .filterNot { (_, args) -> "EndedBy." in args && "EndedBy.UNKNOWN" !in args }
            .map { (name, args) -> "$name: noteWatcherAlive($args)" }
        assertEquals(
            "these close an outage without saying how it ended, so a duration the app measured " +
                "itself is filed with the ones it only inferred:" +
                offenders.joinToString(System.lineSeparator(), System.lineSeparator()),
            emptyList<String>(),
            offenders,
        )
    }

    // ---- invariant 42 ------------------------------------------------------------------------

    /**
     * **The revive verdict is only taken when an event could actually have arrived.**
     *
     * `revivesHelped` exists to answer whether the app's only self-repair does anything, and the
     * recovery plan is built on that answer. The nudge fires after three minutes of silence, and
     * the commonest cause of three minutes of silence is a phone nobody is holding — so judging
     * on a dark screen scores `futile` for a reason unrelated to the repair, systematically, and
     * would read as "the repair does nothing". Wrong, and wrong in the confident direction.
     *
     * Same rule as `probeScreen`: a state where the answer is not knowable is not evidence. Guard
     * removed, the number keeps being produced and quietly stops meaning anything, which is the
     * one failure a measurement must not have.
     */
    @Test
    fun `the revive verdict waits for a screen that could produce an event`() {
        val text = source("service/BlockerAccessibilityService.kt").readText()
        val call = text.indexOf("ServiceHealth.recordReviveOutcome(")
        assertTrue("the revive outcome is no longer recorded at all", call >= 0)
        val before = text.substring(0, call)
        assertTrue(
            "recordReviveOutcome must sit behind canObserveEvents(), or a sleeping phone is " +
                "counted as evidence that the self-repair failed.",
            before.substringAfterLast("if (").startsWith("canObserveEvents()") ||
                "if (canObserveEvents()) {" in before.takeLast(600),
        )
    }
    // ---- invariant 43 ------------------------------------------------------------------------

    /**
     * **Nothing outside `CoverGate` works out for itself when the dismiss grace ends.**
     *
     * Two places used to. One added to `DISMISS_GRACE_MS`, one subtracted from
     * `DISMISS_GRACE_STUCK_MS`, and each was correct only because of a property of the branch it
     * happened to sit in — one because its branch had set `viaBack = true` two lines earlier,
     * the other because its branch was reachable only from the HOME path. Neither said so.
     *
     * That is a rule copied into three places, which `CoverGate`'s own file header says is how
     * this logic got broken twice before it was extracted. If the grace rule moves, a hand-rolled
     * copy does not move with it: it keeps scheduling for a window that no longer exists, and the
     * app goes quiet again in exactly the gap this was written to close. `graceRemainingMs` is
     * the one answer; ask it.
     */
    @Test
    fun `only CoverGate decides when the dismiss grace ends`() {
        // Allow-listed, with the reason, the way this file's header requires.
        //
        // SilenceLog.isLate is not scheduling anything — it thresholds a decline to decide
        // whether it was the suspicious kind, and its boundary is deliberately the SHORT grace.
        // SilenceLogTest pins that: measured from the long one, the dial would read zero through
        // exactly the bug it exists to show. It has to name the constant.
        val allowed = listOf("fun isLate(")

        val offenders = sourceTree()
            .filter { it.name != "CoverGate.kt" }
            .flatMap { f -> f.readText().lines().withIndex().map { (i, l) -> Triple(f.name, i + 1, l) } }
            .filter { (_, _, line) ->
                val mentions = "DISMISS_GRACE_MS" in line || "DISMISS_GRACE_STUCK_MS" in line
                // A comment explaining why the constant is NOT used here is the opposite of the
                // problem, so it is not one.
                mentions && !line.trimStart().startsWith("//") &&
                    !line.trimStart().startsWith("*") && allowed.none { it in line }
            }
            .map { (name, n, line) -> "$name:$n ${line.trim()}" }

        assertEquals(
            "these work out the end of the dismiss grace themselves instead of asking " +
                "CoverGate.graceRemainingMs, so they silently stop agreeing with it:" +
                offenders.joinToString(System.lineSeparator(), System.lineSeparator()),
            emptyList<String>(),
            offenders,
        )
    }

    // ---- invariant 45 ------------------------------------------------------------------------

    /**
     * **The fallback the watcher enforces from must not be written only by the watcher.**
     *
     * Four snapshots defend the seconds between a bind and Room's first emission, and on
     * 5 Sep 2026 all four were written from one place: the service's own collector. The watcher on
     * the owner's phone dies around thirty times a day, so every rule changed while it was dead —
     * including an app auto-blocked by `NewAppWatcher`, which does not run in the service at all —
     * reached Room and nothing else. The next bind then enforced the previous state through the
     * exact window the snapshot exists to cover.
     *
     * Same family as invariant 11 and the sanitiser check: a value built correctly in one place and
     * silently absent one step later. The registration hangs off `BlockerDatabase.get` because that
     * is the one door every writer already goes through, present and future.
     */
    @Test
    fun `the pre-Room fallbacks are maintained from the database, not from the watcher`() {
        // Comments stripped first. Proving this check could fail was what found that it could
        // not: the registration was commented out and the check stayed green, because the words
        // were still in the file. A check satisfied by a comment describing the thing is the same
        // bug as the one it is guarding against.
        val live = source("data/BlockerDatabase.kt").readText().lines()
            .map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
        assertTrue(
            "BlockerDatabase.get must call Snapshots.start, or the snapshot is a side effect of " +
                "watching and goes stale exactly when the watcher is not there to write it.",
            live.any { "Snapshots.start(" in it },
        )
    }

    /**
     * **Every table a snapshot is derived from has to be a table the observer watches.**
     *
     * A fifth snapshot added without its table in `WATCHED` would be refreshed only while the
     * service happens to be alive, which is the whole bug back again — and it would look correct,
     * because the service still writes it. So the two lists are compared rather than trusted.
     */
    @Test
    fun `every table a snapshot is read from is watched for changes`() {
        val text = source("data/Snapshots.kt").readText()
        val quote = Char(34)
        val watched = text.substringAfter("WATCHED = arrayOf(").substringBefore(")")
            .split(quote).filterIndexed { i, _ -> i % 2 == 1 }.toSet()
        assertTrue("Snapshots.WATCHED could not be read", watched.isNotEmpty())

        val daos = text.split("db.").drop(1)
            .map { it.substringBefore("(") }
            .filter { it.endsWith("Dao") && it.all { c -> c.isLetter() } }
            .toSet()
        assertTrue("Snapshots reads no DAO at all, so this check proves nothing", daos.isNotEmpty())

        val missing = daos.mapNotNull { dao ->
            val file = "data/" + dao.replaceFirstChar { c -> c.uppercaseChar() } + ".kt"
            val table = source(file).readText().substringAfter("FROM ")
                .takeWhile { c -> c.isLetterOrDigit() || c == '_' }
            if (table.isNotEmpty() && table !in watched) "$dao -> $table" else null
        }.sorted()

        assertEquals(
            "these tables feed a snapshot but are not in Snapshots.WATCHED, so a change to them " +
                "would not refresh the fallback and the next restart would enforce stale rules: " +
                missing,
            emptyList<String>(),
            missing,
        )
    }

    /**
     * **The blind counter has to be the blind case, or it is just a second copy of the other one.**
     *
     * `unreadyDecisions` counts entering the window and is ordinary; `unreadyBlind` counts entering
     * it with an empty snapshot and is the only half that means blocking was lost. Recorded
     * unconditionally the pair says the same thing twice, and the report goes back to leading with
     * a fault on a healthy phone — which is the reading it was split up to end.
     */
    @Test
    fun `the blind counter is only recorded when the snapshot is actually empty`() {
        val text = source("service/BlockerAccessibilityService.kt").readText()
        val at = text.indexOf("SilenceLog.UNREADY_BLIND")
        assertTrue("UNREADY_BLIND is never recorded by the watcher", at > 0)
        assertTrue(
            "UNREADY_BLIND must sit behind blockedSnapshot.isEmpty(): recorded on every entry " +
                "into the unready window it is a duplicate of unreadyDecisions, and the one " +
                "number that says protection was actually lost stops meaning anything.",
            "blockedSnapshot.isEmpty()" in text.substring(maxOf(0, at - 300), at),
        )
    }

    // ---- invariant 46 ------------------------------------------------------------------------

    /**
     * **The rebound-wake instrument must count only rebinds that ended a real stoppage.**
     *
     * `reboundWake` is the one measurement that decides whether the fifteen-to-twenty minutes a
     * stoppage now genuinely lasts is fixable at all: warm says something woke our process and the
     * reconnection followed (a lever), cold says the reconnection created us (Android alone).
     * Recorded on every `onServiceConnected` it would be dominated by boots and updates, which are
     * not recoveries, and it would read "cold" for a reason that has nothing to do with the
     * question. Same rule as `recordReviveOutcome` sitting behind `canObserveEvents()`: a state
     * where the answer is not knowable is not evidence.
     */
    @Test
    fun `the rebound wake is only recorded when a rebind ended an outage`() {
        val text = source("service/ProtectionWatchdog.kt").readText()
        assertTrue(
            "recordReboundWake is never called, so reboundWake can only ever read 0/0",
            "ServiceHealth.recordReboundWake(" in text,
        )
        // Bounded by the two ends of the ?.let branch rather than by a character count: the first
        // version of this check measured 400 characters back from the call and went red because a
        // five-line comment had been written above it. A check that a comment can break is a check
        // that will be widened until it means nothing.
        val branch = text.substringAfter("OutageLog.end(").substringBefore("reportOutage(")
        assertTrue(
            "recordReboundWake must sit inside the OutageLog.end(...)?.let branch, behind an " +
                "EndedBy.REBOUND test — outside it, every boot and update counts as a recovery.",
            "recordReboundWake(" in branch && "EndedBy.REBOUND" in branch,
        )
    }

    /**
     * **A share of a total may not be written separately from the total.**
     *
     * `timed_ms` is the part of the unprotected total the watcher measured itself. In its own
     * `edit()` it could be committed while the total's was not — a share larger than the whole, or
     * a total that grew without it — and the report would print the contradiction as fact. Invariant
     * 37 is the same lesson from the other direction: the double close that overstated these very
     * numbers happened because one transition was recorded twice.
     */
    @Test
    fun `the timed share is written in the same edit as the total it is part of`() {
        val text = source("data/OutageLog.kt").readText()
        val edit = text.substringAfter("KEY_TOTAL_COUNT, p.getInt").substringBefore(".apply()")
        assertTrue(
            "KEY_TIMED_MS and KEY_TIMED_COUNT must be set in the same edit() as KEY_TOTAL_MS, or " +
                "the share and the total it is a share of can disagree.",
            "KEY_TIMED_MS" in edit && "KEY_TIMED_COUNT" in edit,
        )
    }

    // ---- invariant 47 ------------------------------------------------------------------------

    /**
     * **The two screen probes may not work out "is this state judgeable" for themselves.**
     *
     * They did, four lines apart, and both made the same mistake on the same half: a null
     * `KeyguardManager` was read as "not locked" while a null `PowerManager` correctly meant
     * "cannot tell". One rule, two copies, one of them wrong — the shape `CoverGate` and the
     * report's `profileRowIsBad` were both extracted for. `screenIsJudgeable` is the answer now,
     * and the nulls must reach it unflattened, because flattening them at the call site *was* the
     * bug.
     */
    @Test
    fun `nothing decides for itself whether the screen state is judgeable`() {
        val text = source("service/BlockerAccessibilityService.kt").readText()
        val offenders = text.lines().withIndex()
            .filter { (_, l) ->
                val bare = l.trim()
                val reads = "isKeyguardLocked" in bare || "isInteractive" in bare
                // The two one-line accessors that exist to hand the raw nullable straight over are
                // the intended readers; a comment about them is not a reader at all.
                reads && !bare.startsWith("//") && !bare.startsWith("*") &&
                    !bare.startsWith("private fun interactive()") &&
                    !bare.startsWith("private fun keyguardLocked()") &&
                    !bare.startsWith("(getSystemService(")
            }
            .map { (i, l) -> "${i + 1} ${l.trim()}" }

        assertEquals(
            "these read the screen services directly instead of passing the raw nullables to " +
                "screenIsJudgeable, which is how the two probes came to disagree about what a " +
                "missing service means: " + offenders,
            emptyList<String>(),
            offenders,
        )
    }

    // ---- invariant 48 ------------------------------------------------------------------------

    /**
     * **`BootAudit.heard` must be the first call in `BootReceiver`.**
     *
     * Everything else the receiver does reaches `BootAudit.noteRun`, which looks for the stamp
     * `heard` writes. Called later, the receiver records its own boot as one it missed — an
     * instrument reporting the exact opposite of what happened, and reporting it about the one
     * question it exists to answer. Compared by position among the call sites, not by character
     * distance: the last check written this way went red because somebody added a comment.
     */
    @Test
    fun `the boot receiver stamps before it does anything else`() {
        val body = source("service/BootReceiver.kt").readText()
            .substringAfter("override fun onReceive")
        val calls = listOf(
            "BootAudit.heard(",
            "UpdatePause.checkVersionChange(",
            "ProtectionScheduler.",
            "ProtectionWatchdog.checkAndNotify(",
        ).mapNotNull { c -> body.indexOf(c).takeIf { it >= 0 }?.let { c to it } }

        val stamp = calls.firstOrNull { it.first == "BootAudit.heard(" }
        assertTrue("BootReceiver never calls BootAudit.heard", stamp != null)
        val later = calls.filter { it.second < stamp!!.second }.map { it.first }
        assertEquals(
            "these run before BootAudit.heard, so the receiver records its own boot as missed: " +
                later,
            emptyList<String>(),
            later,
        )
    }

    /**
     * **One reader of "did the last send succeed".**
     *
     * There were two, forty lines apart, and only one was right: `queueFact` treated any recorded
     * result as a failure, so a working channel with a throttled backlog printed "could not be
     * delivered" in red directly under "the last report was delivered". Invariant 47's shape, and
     * the sibling reader is always where the disagreement lives.
     */
    @Test
    fun `only one rule decides whether the last send succeeded`() {
        val text = source("data/HealthFacts.kt").readText()
        val offenders = text.lines().withIndex()
            .filter { (_, l) ->
                val bare = l.trim()
                "lastSendResult" in bare && !bare.startsWith("//") && !bare.startsWith("*") &&
                    // the field itself, and the one call that asks the question
                    !bare.startsWith("val lastSendResult") &&
                    "lastSendSucceeded(" !in bare &&
                    "val result = r.lastSendResult" !in bare
            }
            .map { (i, l) -> "${i + 1} ${l.trim()}" }
        assertEquals(
            "these read lastSendResult directly instead of asking lastSendSucceeded, which is " +
                "how one report came to call the same channel working and broken at once: " +
                offenders,
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * **The cost of a stoppage has to be asked for where both ends of it are known.**
     *
     * `usedDuringMin` is minutes of real use during the outage, and it is the number that showed
     * every stoppage on record happened on an untouched phone. Read at report time instead it
     * spans the wrong window entirely — the 5 Sep six-hour episode reported `usedMinutes 0`
     * measured across the ONE minute since the phone woke, which is true and answers nothing. So
     * the probe is supplied by `OutageLog.end`'s caller, at the close.
     */
    @Test
    fun `the outage cost is measured at the close, from the episode's own window`() {
        val watchdog = source("service/ProtectionWatchdog.kt").readText()
        val call = watchdog.substringAfter("OutageLog.end(").substringBefore("?.let")
        assertTrue(
            "ProtectionWatchdog must pass a usedMinutes probe to OutageLog.end, or every episode " +
                "records UNKNOWN_USE and the one number worth having is never taken.",
            "usedMinutes" in call && "totalMinutesInRange" in call,
        )
        assertTrue(
            "the probe must check usage access first: without it a phone that cannot answer " +
                "records 0, which reads as \"this stoppage cost nothing\".",
            "hasUsageAccess" in call,
        )
    }

    // ---- invariant 49 ------------------------------------------------------------------------

    /**
     * **The blind fallback may only run while the watcher is already silent, and only on a lit
     * unlocked screen.**
     *
     * `coverBlindly` asks Android what is in front instead of waiting for an accessibility event.
     * That is the whole point of it — and it is a binder query on the heartbeat, so running it on
     * a healthy phone would be a real battery cost for an answer the event path already gave, and
     * the owner's standing constraint is "keep the battery as it is". Called unconditionally this
     * stops being a safety net and becomes a second, slower blocker running all day.
     *
     * Both gates live on the same line in the heartbeat's silence branch, so this checks the call
     * site rather than the method.
     */
    @Test
    fun `the blind fallback only runs during a silence spell on a lit screen`() {
        val text = source("service/BlockerAccessibilityService.kt").readText()
        val calls = text.lines().map { it.trim() }.filter {
            "coverBlindly()" in it && !it.startsWith("*") && !it.startsWith("//") &&
                // the declaration is not a call site
                !it.startsWith("private fun")
        }
        assertEquals("coverBlindly must have exactly one call site: $calls", 1, calls.size)
        assertTrue(
            "coverBlindly must be gated on canObserveEvents(), or it queries usage stats on a " +
                "dark screen every minute for an answer that cannot matter.",
            "canObserveEvents()" in calls.first(),
        )
        // The silence branch is the `else` of the "events are flowing again" test, so the call has
        // to sit after `silenceSpellOpen = true` and before the revive block.
        val spell = text.indexOf("silenceSpellOpen = true")
        val call = text.indexOf("coverBlindly()")
        assertTrue("coverBlindly is never called", call > 0)
        assertTrue(
            "coverBlindly must sit inside the silence branch (after silenceSpellOpen = true), " +
                "or it runs while events are arriving normally.",
            spell in 1 until call,
        )
    }

    /**
     * **The fallback decides WHAT is in front, never WHETHER to block it.**
     *
     * `handleAppBlock` holds the policy — Strict, Quick Block, the update pause, the rules, the
     * snapshots. A second path that reached its own verdict would be a second copy of every rule
     * in this app, and copies of one rule disagreeing is the shape that has produced a finding in
     * every release this week.
     */
    @Test
    fun `the blind fallback asks handleAppBlock rather than deciding for itself`() {
        // Bounded by the next method's KDoc rather than by the next `private fun`: the method
        // after this one is introduced by a doc comment, so the naive boundary swallowed it and
        // the check failed on somebody else's showBlockScreen. Third time this week a source
        // check has been wrong about where a method ends — bound on the doc boundary.
        val body = source("service/BlockerAccessibilityService.kt").readText()
            .substringAfter("private fun coverBlindly()")
            .substringBefore(Char(10) + "    /**")
        assertTrue("coverBlindly must route through handleAppBlock", "handleAppBlock(" in body)
        // ⚠️ **A remedy may not count its own attempts as its results.** `blindLooks` counts the
        // fallback running, which it does once a minute through any silence spell whether or not
        // there was anything to block — so alone it climbs on a phone this has never helped, and
        // it was written down as the acceptance test for the whole fix. `revives` reported 67
        // successes out of 67 while the fault it treated carried on for weeks; this is that same
        // mistake inside the counter built to judge the replacement for it. The outcome has to be
        // read from the overlay either side of the decision.
        assertTrue(
            "coverBlindly must record BLIND_COVERS, or the only number judging this fix counts " +
                "attempts rather than results.",
            "BLIND_COVERS" in body,
        )
        assertTrue(
            "BLIND_COVERS must be conditional on the overlay changing state across " +
                "handleAppBlock, or it is a second copy of blindLooks under a better name.",
            "coveredBefore" in body && "!coveredBefore && overlay.isShowing" in body,
        )
        // Both layers or neither. Page scanning is armed only by an event too, so a deaf watcher
        // stops looking at pages as completely as it stops looking at apps — and on this phone the
        // site layer is the one that catches most things. Covering only the app half would look
        // like a fix while leaving the bigger hole open.
        // All three, because the event path arms all three together (`scheduleUrlScan`,
        // `scheduleWebScan`, `scheduleShortsScan` on one line of onAccessibilityEvent). A subset
        // leaves a hole shaped like whichever was left out, and the first draft of this fix
        // restored only the middle one.
        // ⚠️ And the scans run once per screen per spell, not once a minute. A page cannot
        // change without an accessibility event and a silence spell is the absence of those, so a
        // repeat scan of the same screen can only find what the first one found — at the cost of a
        // full node walk, on a healthy phone, every minute the owner reads something static. The
        // package changing is the one thing that IS new, and the one a deaf watcher would
        // otherwise miss, so the scans follow the package rather than the clock.
        assertTrue(
            "the blind scans must be gated on the foreground package having changed, or a quiet " +
                "phone pays for a full page scan every minute for nothing.",
            "front != lastBlindScanPkg" in body,
        )
        // ⚠️ And the LOOK counter has to sit behind the same gate. blindLooks and blindCovers are
        // read as a pair; counting looks per tick and covers per screen makes the ratio describe
        // how long he read an article rather than whether the net helped, which is invariant 53
        // again in the other half of the same pair.
        // ⚠️ The first version of this check could not fail: it asked whether `firstLookHere`
        // appeared anywhere before the record, and the flag is DECLARED before it either way. A
        // check satisfied by a variable's declaration is the same class of hole as one satisfied
        // by a comment. Structural instead — the gate must open before the record and not close
        // in between, which no amount of moving the declaration can fake.
        val beforeLooks = body.substringBefore("SilenceLog.BLIND_LOOKS")
        val gate = beforeLooks.lastIndexOf("if (firstLookHere) {")
        assertTrue(
            "BLIND_LOOKS must be recorded INSIDE the firstLookHere gate, or it counts ticks " +
                "while BLIND_COVERS counts screens and the pair stops meaning anything.",
            gate >= 0 && "}" !in beforeLooks.substring(gate),
        )
        listOf("scheduleUrlScan()", "scheduleWebScan()", "scheduleShortsScan()").forEach {
            assertTrue(
                "coverBlindly must re-arm $it as well, or that layer stays blind for the whole " +
                    "silence spell while the others recover.",
                it in body,
            )
        }
        assertTrue(
            "the page scan must keep the event path's own gate (shouldScanPkg, and a cover " +
                "already up owns the screen), or this becomes a second scanning policy.",
            "shouldScanPkg(" in body && "overlay.isShowing" in body,
        )
        listOf("blockReason(", "showBlockScreen(", "overlay.remove()").forEach {
            assertFalse(
                "coverBlindly calls $it directly, which puts a second copy of the blocking " +
                    "decision beside the real one.",
                it in body,
            )
        }
    }

    // ---- invariant 51 ------------------------------------------------------------------------

    /**
     * **A rebind must repair the whole scheduler, not the alarm alone.**
     *
     * `onServiceConnected` called `ensureAlarmScheduled`, so the periodic check and the update
     * check could only be re-enqueued by `BootReceiver` or by the owner opening the app — the two
     * rarest events on his phone — while a rebind, by far the commonest (`unbindSeen: 41` in four
     * days), could not touch them. On a phone whose reports say `workerSilent: 185`, the scheduler
     * is exactly the thing that keeps not running.
     */
    @Test
    fun `a rebind re-arms the whole scheduler`() {
        val body = source("service/BlockerAccessibilityService.kt").readText()
            .substringAfter("override fun onServiceConnected()")
            .substringBefore(Char(10) + "    /**")
        val live = body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
        assertTrue(
            "onServiceConnected must call ProtectionScheduler.ensureScheduled. The alarm alone " +
                "leaves the periodic check and the update check repairable only by a boot or by " +
                "the app being opened, which are the two things that happen least.",
            live.any { "ProtectionScheduler.ensureScheduled(" in it },
        )
    }

    // ---- invariant 52 ------------------------------------------------------------------------

    /**
     * **A profile report has to carry the standing questions.**
     *
     * It is the report filed on every app open, including on a phone where nothing has gone
     * wrong — which is exactly the phone whose counters say whether the last fix worked. It used
     * to carry `PROFILE_CONTEXT_KEYS` alone, so `blindLooks`, `revivesHelped`, `bootHeard`,
     * `graceRecovers` and the outage totals were visible only alongside a stoppage. On 6 Sep 2026
     * the owner restarted his phone, it kept working, and nothing in any report could say so.
     *
     * `takeReading = false` is the other half: it stops `appContext` taking a usage walk of its
     * own. Both are checked, because dropping either turns this into the bug it replaced.
     *
     * ⚠️ **This check used to be named "and takes no reading", and that was not true.** It pinned
     * the flag and the flag was never the whole cost: `healthLines(context, watch = null)` on the
     * line below made `HealthReader.read` take its own `ProtectionWatchdog.read`, so every app
     * resume since v1.148 walked the usage-event stream and then threw the report away at the
     * queue's dedupe. **A check that verifies the flag while the thing it names happens by another
     * route is worse than no check** — it is the reason nobody looked. The third assertion is the
     * one that actually holds the promise: the key is asked for before the report is built.
     */
    @Test
    fun `a profile report carries the standing questions and is skipped before it is built`() {
        val body = source("service/BugReportSender.kt").readText()
            .substringAfter("fun reportDeviceProfile(")
            .substringBefore(Char(10) + "    /**")
        val live = body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .joinToString(" ")
        assertTrue(
            "reportDeviceProfile must include appContext, or every instrument built to answer a " +
                "standing question is invisible on a phone that is working.",
            "appContext(" in live,
        )
        assertTrue(
            "it must pass takeReading = false, or appContext takes the watchdog's usage walk.",
            "takeReading = false" in live,
        )
        val asked = live.indexOf("BugReportQueue.alreadyHave(")
        val built = live.indexOf("BugReport.fromProfile(")
        assertTrue(
            "reportDeviceProfile must ask BugReportQueue.alreadyHave before building anything. " +
                "enqueue can only refuse a report that already exists, and building one takes " +
                "the usage walk on every single app resume.",
            asked >= 0,
        )
        assertTrue(
            "the check must come BEFORE the report is constructed, or it saves nothing at all: " +
                "alreadyHave at $asked, fromProfile at $built",
            built < 0 || asked < built,
        )
    }

    // ---- invariant 58 ------------------------------------------------------------------------

    /**
     * The body of one function, comment lines removed.
     *
     * ⚠️ Two things learned the hard way. A shape check satisfied by a *commented-out* call has
     * happened twice in this repo, so comments come out before anything is looked for. And the
     * end of a function is its own four-space `}` — bounding on "the next `private fun`" swallows
     * whatever sits between, which here is an enum and three more methods, and the count-based
     * assertions below would then be counting somebody else's code.
     */
    private fun liveBody(path: String, after: String): String =
        source(path).readText()
            .substringAfter(after)
            .substringBefore(Char(10) + "    }")
            .lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .joinToString(" ")

    /**
     * **Only the debounced page scan may record a latency as SETTLED, and it must.**
     *
     * `BlockLatency` blends nothing now, and the whole value of that rests on one thing being true
     * at four call sites: the two paths that decide in the event's own turn say INSTANT, and the
     * one that deliberately waits 250-950ms before it starts work says SETTLED. Wrong in either
     * direction and the histogram silently goes back to being a mixture — a settled cover filed as
     * instant drags the verdict down with waiting time, an instant one filed as settled hides real
     * slowness. Nothing throws, nothing looks wrong, and the number simply resumes meaning what it
     * meant on 6 Sep 2026: it was read as blocking getting slower and reported to the owner as
     * such, and it was path mix.
     *
     * `BlockLatency.Start` exists so a duration cannot be recorded without naming its path. This is
     * the other half — that the names are the right way round.
     */
    @Test
    fun `only the debounced scan records a settled latency`() {
        val live = source("service/BlockerAccessibilityService.kt").readText()
            .lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
        val settled = live.filter { "BlockLatency.Path.SETTLED" in it }
        assertEquals(
            "exactly one place may claim a settled latency, and it is the web-scan runnable: " +
                settled,
            1,
            settled.size,
        )
        assertTrue(
            "the settled start must be the one handed to scanWebContent, the only path that " +
                "waits before it works: " + settled.first(),
            "scanWebContent(" in settled.first(),
        )
        val instant = live.filter { "BlockLatency.Path.INSTANT" in it }
        assertTrue(
            "the app-block and address-bar paths must record as INSTANT: $instant",
            instant.size >= 3 &&
                instant.any { "handleAppBlock(" in it } &&
                instant.any { "scanBrowserUrl(" in it },
        )
    }

    /**
     * **The keyword scanner and the uninstall guard agree on what an app-management screen is.**
     *
     * `KEYWORD_SCAN_EXCLUDED` exists so a blocked word that is also an *app name* cannot cover the
     * screen the owner manages his phone from — Settings → Apps lists every app installed. It was
     * `setOf("com.android.systemui", "com.android.settings")`, AOSP's two, while
     * `GuardPackages.GUARD` in the same companion object already knew that **Xiaomi routes app
     * management through `com.miui.securitycenter`** and said so in its own KDoc. Two lists, one
     * question, and the shorter one was the one on the hot path — so on the owner's own phone that
     * screen and all eight package installers were keyword scanned.
     *
     * The danger zone makes it sharper: `danger_words.txt` is 353 deliberately ordinary words
     * matched against every app for an hour, and a list of every app on the phone is the likeliest
     * place for one of them to appear.
     *
     * ⚠️ **This is the shape `docs/BLOCKING_INVARIANTS.md` opens by naming** — a rule written down
     * as a fact about one screen, with the correct sibling twenty lines away in the same file.
     */
    @Test
    fun `the keyword scanner excludes every screen the guard calls app management`() {
        val live = source("service/BlockerAccessibilityService.kt").readText()
            .lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
        val decl = live.firstOrNull { it.startsWith("private val KEYWORD_SCAN_EXCLUDED") }
        assertTrue("KEYWORD_SCAN_EXCLUDED must still exist", decl != null)
        assertTrue(
            "KEYWORD_SCAN_EXCLUDED must be derived from the guard's own set, not listed again — " +
                "a second list of management screens is how the Xiaomi one got left out: $decl",
            "GuardPackages.GUARD" in decl!! || "GUARD_PACKAGES" in decl,
        )
        assertFalse(
            "it must not spell package names of its own beyond the system-UI one: $decl",
            Regex("\"com\\.android\\.settings\"|\"com\\.miui\\.").containsMatchIn(decl),
        )
    }

    /**
     * **The profile dedupe key is spelled once.**
     *
     * `BugReportSender` asks the queue whether it already has this profile *before* building one,
     * because building one takes the usage-stream walk on every app resume (invariant 61). That
     * check is worth nothing if the key it asks about is not the key `enqueue` will compute, so
     * `dedupeKey` calls `BugReport.profileKey` rather than spelling `"profile:…"` again.
     *
     * ⚠️ **A unit test cannot hold this.** Asserting `dedupeKey() == profileKey(...)` passes by
     * construction while one calls the other — swapping `profileKey`'s format left that assertion
     * green, which is how this check came to exist. The thing that can actually regress is the
     * *shape*: someone restores the inline string, both spellings compile, and they drift apart in
     * silence. Third ornamental check caught this week by putting the bug back.
     */
    @Test
    fun `the profile dedupe key is not spelled twice`() {
        val text = source("data/BugReport.kt").readText()
        val live = text.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
        val spellings = live.filter { "\"profile:" in it }
        assertEquals(
            "the \"profile:\" format may appear exactly once, inside profileKey — a second " +
                "spelling is a rule with two copies, and the queue would go on refusing a report " +
                "the caller thought it had already checked for: $spellings",
            1,
            spellings.size,
        )
        assertTrue(
            "the one spelling must be profileKey's own body: " + spellings.first(),
            "fun profileKey(" in spellings.first(),
        )
        assertTrue(
            "dedupeKey must call profileKey rather than build the string itself",
            live.any { "isProfile ->" in it && "profileKey(" in it },
        )
    }

    /**
     * **The page scan reads the address before it reads the page, and asks for the rules once.**
     *
     * For a site cover — most of what this app raises — the verdict comes from the host alone, and
     * the 400-node text walk that used to run first was matched and thrown away; on a start page
     * both walks finished before `check` returned null on its own first line. The order *is* the
     * saving, so the order is what has to be held. `autoSocialKeywords()` rides in the same check
     * because it was called twice in this one function, each call walking every rule for the same
     * answer — the same shape, forty lines apart.
     */
    @Test
    fun `the page scan reads the address before the page and the rules once`() {
        val body = liveBody(
            "service/BlockerAccessibilityService.kt",
            "private suspend fun scanWebContent(",
        )
        val addressAt = body.indexOf("rememberedBrowserAddress(")
        val textAt = body.indexOf("extractVisibleText(")
        assertTrue("scanWebContent must still read an address", addressAt >= 0)
        assertTrue("scanWebContent must still be able to read the page", textAt >= 0)
        assertTrue(
            "the address must be read first, or every site cover pays a page walk it discards",
            addressAt < textAt,
        )
        assertTrue(
            "the page walk must be conditional on the address not having answered already",
            "urlHit != null" in body.substring(0, textAt),
        )
        assertEquals(
            "autoSocialKeywords() must be read once per scan, not once per verdict",
            1,
            Regex("autoSocialKeywords\\(\\)").findAll(body).count(),
        )
    }
}
