package com.appblocker

import com.appblocker.data.BugReport
import java.io.File
import org.junit.Assert.assertEquals
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
}
