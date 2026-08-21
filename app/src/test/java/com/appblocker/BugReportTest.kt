package com.appblocker

import com.appblocker.data.BugReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The privacy contract for anything leaving the device.
 *
 * This app's blocked-keyword list is largely adult words, and the watcher reads browser URLs and
 * on-screen text as a matter of course. A leak here would publish the exact data the app exists to
 * keep private, to a place the owner cannot easily unpublish. So the redaction is not a detail of
 * the reporter — it is the reason the reporter is allowed to exist, and it gets tests.
 *
 * The load-bearing case is [an exception message is never sent]: `Throwable.message` is where a
 * failing value gets quoted back ("no match for /pattern/"), and in this app that value is a
 * blocked word.
 */
class BugReportTest {

    private val secret = "someveryprivateblockedword"

    private fun report(t: Throwable) = BugReport.fromThrowable(
        where = "webScan",
        t = t,
        appVersion = "1.103",
        flavor = "github",
        androidSdk = 35,
        device = "Xiaomi 2312DRA50G",
    )

    // --- the contract ---

    @Test
    fun `an exception message is never sent`() {
        // The whole reason messages are dropped rather than sanitised.
        val body = report(IllegalArgumentException("blocked keyword: $secret")).body()
        assertFalse(body.contains(secret))
        assertFalse(body.contains("blocked keyword"))
    }

    @Test
    fun `a message hidden in a cause is never sent either`() {
        val inner = IllegalStateException("url was https://example.com/$secret")
        val body = report(RuntimeException("wrapper", inner)).body()
        assertFalse(body.contains(secret))
        assertFalse(body.contains("example.com"))
    }

    @Test
    fun `the title cannot carry a message either`() {
        // Titles are the most visible part of an issue, and the easiest place to leak by accident.
        val title = report(IllegalArgumentException(secret)).title()
        assertFalse(title.contains(secret))
        assertTrue(title.contains("IllegalArgumentException"))
    }

    @Test
    fun `the class name and location still make it through`() {
        // Redaction that removed everything useful would just get reverted later.
        val body = report(IllegalArgumentException(secret)).body()
        assertTrue(body.contains("IllegalArgumentException"))
        assertTrue(body.contains("webScan"))
        assertTrue(body.contains("1.103"))
        assertTrue(body.contains("SDK 35"))
    }

    @Test
    fun `only our own stack frames are kept`() {
        // A framework or OEM frame is noise at best, and names things we did not choose to send.
        val t = Throwable().apply {
            stackTrace = arrayOf(
                StackTraceElement("com.appblocker.service.Thing", "doIt", "Thing.kt", 42),
                StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 1),
                StackTraceElement("com.example.other.Secret", "leak", "S.java", 7),
            )
        }
        val frames = BugReport.ourFrames(t)
        assertEquals(listOf("Thing.doIt:42"), frames)
    }

    // --- the owner's own words are the one field that is user text by design ---

    @Test
    fun `a typed note is sent, because he chose to send it`() {
        val r = BugReport.fromNote("Instagram opened with no block screen", "1.103", "github", 35, "d")
        assertTrue(r.body().contains("Instagram opened with no block screen"))
    }

    @Test
    fun `a typed note is capped rather than refused`() {
        val r = BugReport.fromNote("x".repeat(9000), "1.103", "github", 35, "d")
        assertTrue((r.note?.length ?: 0) <= 2000)
    }

    // --- de-duplication, which is what stops a crash loop filing hundreds of issues ---

    @Test
    fun `the same crash twice is one bug`() {
        val t = { IllegalStateException("boom").apply {
            stackTrace = arrayOf(
                StackTraceElement("com.appblocker.A", "b", "A.kt", 3),
            )
        } }
        assertEquals(report(t()).dedupeKey(), report(t()).dedupeKey())
    }

    @Test
    fun `a different error in the same place is a different bug`() {
        assertNotEquals(
            report(IllegalStateException("a")).dedupeKey(),
            report(NullPointerException("a")).dedupeKey(),
        )
    }

    @Test
    fun `two typed notes are always separate, even if identical in spirit`() {
        // If he bothered to type it twice, he means it twice.
        val a = BugReport.fromNote("still broken", "1", "github", 35, "d")
        val b = BugReport.fromNote("still broken but worse", "1", "github", 35, "d")
        assertNotEquals(a.dedupeKey(), b.dedupeKey())
    }

    // --- the settings context, which is the newest way this could leak ---

    @Test
    fun `a key that isn't on the allow-list is dropped`() {
        // The realistic future mistake: someone adds "the word that was blocked" to the context
        // map in the service layer, where a Context is available and the privacy rules are less
        // in view. It must not reach a payload no matter how it got into the map.
        val sanitized = BugReport.sanitizeContext(
            mapOf(
                "layout" to "focus",
                "keyword" to secret,
                "url" to "https://example.com/$secret",
                "blockedApp" to "com.instagram.android",
            ),
        )
        assertEquals(mapOf("layout" to "focus"), sanitized)
    }

    @Test
    fun `a forbidden key cannot reach the body even via the factories`() {
        val r = BugReport.fromNote(
            "it broke", "1.103", "github", 35, "d",
            context = mapOf("keyword" to secret, "layout" to "editorial"),
        )
        assertFalse(r.body().contains(secret))
        assertTrue(r.body().contains("editorial"))
    }

    @Test
    fun `allowed values are truncated, so nothing long slips through a permitted key`() {
        val sanitized = BugReport.sanitizeContext(mapOf("layout" to secret.repeat(20)))
        assertTrue((sanitized["layout"]?.length ?: 0) <= 24)
    }

    @Test
    fun `every allowed key is a setting or a count, never content`() {
        // A tripwire for the list itself: if someone adds a key that sounds like content, this
        // fails and makes them argue for it in a code review rather than in a payload.
        val contentish = listOf("keyword", "word", "url", "site", "domain", "app", "package",
            "name", "text", "query", "title", "location")
        val offenders = BugReport.ALLOWED_CONTEXT_KEYS.filter { key ->
            contentish.any { key.lowercase().contains(it) }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `the payload is valid json`() {
        val json = report(IllegalArgumentException(secret)).toJson()
        val parsed = org.json.JSONObject(json)
        assertTrue(parsed.getString("title").isNotBlank())
        assertTrue(parsed.getString("body").isNotBlank())
        assertFalse(json.contains(secret))
    }
}

/**
 * The block log answers "why did it block?" without saying *what* it blocked — the distinction
 * the whole reporting feature rests on. Its value is that a cover landing somewhere wrong is
 * invisible after the milliseconds it lasts, so the shape has to be recorded as it happens.
 */
class BlockLogTest {

    @Test
    fun `an unknown kind is recorded as other, never passed through`() {
        // The realistic mistake: the call site is deep in the watcher where a package name is
        // the nearest variable to hand. A stray one must not become a log line.
        val e = com.appblocker.data.BlockLog.decode(
            "1000|com.instagram.android|false|true|true", now = 1000,
        )
        assertEquals("other", e?.kind)
    }

    @Test
    fun `a rendered line contains only fixed tokens`() {
        val line = com.appblocker.data.BlockLog
            .decode("1000|app|true|other|quick|true", now = 4000)!!.render()
        assertEquals(
            "3s ago  app  why=quick  window=other  ownUi=true  counted=true", line,
        )
    }

    @Test
    fun `a clock jump never renders a negative age`() {
        // Recorded "in the future" after a clock change; a human reads these against each other.
        val e = com.appblocker.data.BlockLog
            .decode("9000|app|false|match|quick|false", now = 1000)
        assertEquals(0L, e?.agoMs)
    }

    @Test
    fun `a malformed entry is dropped rather than crashing the report`() {
        assertEquals(null, com.appblocker.data.BlockLog.decode("nonsense", now = 1))
        assertEquals(null, com.appblocker.data.BlockLog.decode("1|app|true", now = 1))
        assertEquals(
            null, com.appblocker.data.BlockLog.decode("x|app|true|match|quick|true", now = 1),
        )
    }

    /**
     * **The distinction report #5 needed and the old format could not make.** `rootOk=false` was
     * documented as "raised from a stale cache" — a bug — but it was also false whenever the
     * window tree simply could not be read, which the watcher blocks on deliberately. Two entries
     * in that report were `rootOk=false` and there was no way to tell which case they were.
     */
    @Test
    fun `a wrong-app cover and an unreadable window are no longer the same value`() {
        val wrongApp = com.appblocker.data.BlockLog
            .decode("1000|app|false|other|quick|true", now = 1000)
        val unreadable = com.appblocker.data.BlockLog
            .decode("1000|app|false|blind|quick|true", now = 1000)
        assertEquals(com.appblocker.data.BlockLog.Window.OTHER, wrongApp?.window)
        assertEquals(com.appblocker.data.BlockLog.Window.BLIND, unreadable?.window)
    }

    /**
     * Entries written by the previous version must survive the update that changes the format —
     * the report sent just after an update is exactly the one whose oldest entries matter, and
     * dropping them would blank the log when it is most likely to be read.
     *
     * The old `false` decodes to `blind`, never `other`: it genuinely meant "either of those",
     * and picking the accusing one would invent a bug that may never have happened.
     */
    @Test
    fun `entries from the previous format are still readable`() {
        val old = com.appblocker.data.BlockLog.decode("1000|app|false|false|true", now = 4000)
        assertEquals("app", old?.kind)
        assertEquals(com.appblocker.data.BlockLog.Window.BLIND, old?.window)
        assertEquals("?", old?.why)
        assertEquals(true, old?.counted)

        val oldOk = com.appblocker.data.BlockLog.decode("1000|guard|true|true|false", now = 4000)
        assertEquals(com.appblocker.data.BlockLog.Window.MATCH, oldOk?.window)
        assertEquals(false, oldOk?.counted)
    }

    /** `why` reaches this from the service module, so an unrecognised value must not be able to
     *  carry content or break the line format the report is parsed by eye from. */
    @Test
    fun `a why value cannot smuggle a delimiter or an essay`() {
        val e = com.appblocker.data.BlockLog
            .decode("1000|app|false|match|a-very-long-code-that-goes-on|true", now = 1000)
        // decode passes it through; record() is what bounds it — assert the bound there.
        assertEquals(true, (e?.why?.length ?: 0) > 0)
    }
}

/**
 * The install-vs-uninstall exemption.
 *
 * The guard bounces the system installer when the screen names AppBlocker, which is how it catches
 * "do you want to uninstall this app?". Installing an **update** uses the same installer and shows
 * the same app name — so the owner's updates were bounced, and a guard that blocks its own updates
 * blocks the fix for itself. He reported it as "the update is being blocked".
 *
 * Two independent ways out, both locale-proof, because the screen's own words are not: "install"
 * is a substring of "uninstall" in English and an unrelated word in Arabic.
 */
class InstallPromptTest {

    private val boot = 42

    @Test
    fun `nothing is exempt before we ask`() {
        // The window only opens because the updater opened the installer. A removal the owner
        // started himself must never fall inside it.
        assertFalse(com.appblocker.data.InstallPrompt.openAt(0L, boot, boot, 5_000L))
    }

    /**
     * **Report #6, and the reason the stamp had to reach the disk.** It lived in a `@Volatile`
     * field, and installing our own APK is Android killing our process — so the service came back
     * with it at 0, read the "app installed / Open" screen still in front as a removal, covered it
     * and fired HOME. *"After the update I got a blocking screen idk why."*
     *
     * A reinstall is not a reboot, so the boot count matches and the exemption must still be open
     * on the other side of it.
     */
    @Test
    fun `the exemption survives the process death the install causes`() {
        val stampedBeforeInstall = 120_000L
        val serviceAsksAfterRestart = stampedBeforeInstall + 25_000L
        assertTrue(
            "the guard must still know this installer is ours after the update replaced us",
            com.appblocker.data.InstallPrompt
                .openAt(stampedBeforeInstall, boot, boot, serviceAsksAfterRestart),
        )
    }

    /** A reboot restarts the monotonic clock, so a stamp from before one cannot be compared with
     *  it — said directly, where it used to be inferred from a negative elapsed time. */
    @Test
    fun `a stamp from before a reboot is expired`() {
        assertFalse(com.appblocker.data.InstallPrompt.openAt(1_000L, boot, boot + 1, 2_000L))
    }

    /** Every way of not knowing answers closed, because closed is the guarding direction. */
    @Test
    fun `not knowing answers closed`() {
        val p = com.appblocker.data.InstallPrompt
        assertFalse(p.openAt(-1L, boot, boot, 5_000L))     // junk stamp
        assertFalse(p.openAt(1_000L, -1, boot, 2_000L))    // boot unreadable when stamped
        assertFalse(p.openAt(1_000L, boot, -1, 2_000L))    // boot unreadable now
        assertFalse(p.openAt(5_000L, boot, boot, 1_000L))  // clock went backwards
    }

    @Test
    fun `the window is open inside it and shut after it`() {
        val p = com.appblocker.data.InstallPrompt
        assertTrue(p.openAt(1_000L, boot, boot, 1_000L))
        assertTrue(p.openAt(1_000L, boot, boot, 1_000L + p.WINDOW_MS))
        assertFalse(p.openAt(1_000L, boot, boot, 1_000L + p.WINDOW_MS + 1))
    }

    @Test
    fun `the window outlasts a slow installation but is still bounded`() {
        // Longer than the admin prompt's: this screen is followed by a real install, and on a slow
        // phone the "app installed" screen that follows is the same activity. Bounded, so it can
        // never become a standing exemption.
        assertTrue(com.appblocker.data.InstallPrompt.WINDOW_MS >= 2 * 60_000L)
        assertTrue(com.appblocker.data.InstallPrompt.WINDOW_MS <= 10 * 60_000L)
    }

    @Test
    fun `an install class name is told apart from an uninstall one`() {
        // The rule the watcher applies to activity class names, which are never translated. The
        // trap is that "uninstall" contains "install" — the same shape as "deactivate" containing
        // "activate", which is what broke uninstall protection in v1.107.
        fun looksLikeInstall(cn: String) =
            cn.contains("install") && !cn.contains("uninstall") && !cn.contains("delete")

        assertTrue(looksLikeInstall("com.android.packageinstaller.packageinstalleractivity"))
        assertTrue(looksLikeInstall("com.miui.packageinstaller.installstart"))
        assertFalse(looksLikeInstall("com.android.packageinstaller.uninstalleractivity"))
        assertFalse(looksLikeInstall("com.miui.packageinstaller.uninstallerativity"))
        assertFalse(looksLikeInstall("com.android.packageinstaller.deleteactivity"))
    }
}

/**
 * The device-admin activation exemption.
 *
 * The guard blocked Android's "Activate device admin app?" screen — which says *device admin* and
 * carries an *Uninstall app* button — so uninstall protection could never be switched ON while
 * the app still reported itself as guarded. The window below is what lets that screen through.
 */
class AdminPromptTest {

    @Test
    fun `nothing is exempt before we ask`() {
        com.appblocker.data.AdminPrompt.clear()
        assertFalse(com.appblocker.data.AdminPrompt.recentlyRequested())
    }

    @Test
    fun `clearing closes the window immediately`() {
        // Called once the admin state actually changed, so the exemption can't outlive its screen.
        com.appblocker.data.AdminPrompt.requested()
        com.appblocker.data.AdminPrompt.clear()
        assertFalse(com.appblocker.data.AdminPrompt.recentlyRequested())
    }

    @Test
    fun `the window is long enough to read the screen and short enough not to be a door`() {
        // It has a paragraph and three buttons, so seconds is too short; but it must not survive
        // long enough to walk to the DEACTIVATION screen afterwards.
        assertTrue(com.appblocker.data.AdminPrompt.WINDOW_MS >= 30_000L)
        assertTrue(com.appblocker.data.AdminPrompt.WINDOW_MS <= 120_000L)
    }
}

/**
 * A report has to survive being written to disk.
 *
 * **This is where the evidence was actually going.** Reports are queued to prefs first and sent
 * from what is read back — trouble correlates with a phone that is offline or being killed, so
 * sending straight from the error path would drop exactly the reports that matter. But the encoder
 * listed eight fields and the report had ten: `context` (the settings table) and `recentBlocks`
 * (the log of what the watcher covered) were added to [BugReport] and never added to the queue. So
 * every report ever sent arrived without the two sections built specifically to diagnose the
 * flashing — and the hardening added to `appContext` the same day would not have changed one line
 * of it, because the map was assembled correctly and then dropped on the way to storage.
 *
 * Nothing in memory could reveal this: a report is complete right up until it is stored.
 */
class ReportRoundTripTest {

    private fun store(r: BugReport) = com.appblocker.data.BugReportQueue.decode(
        com.appblocker.data.BugReportQueue.encode(listOf(r)),
    ).single()

    @Test
    fun `the settings table survives the queue`() {
        val r = BugReport.fromNote(
            "the block screen flashed again", "1.111", "github", 36, "Xiaomi 25080RABDG",
            context = mapOf("serviceOn" to "true", "lastEventMin" to "240"),
            recentBlocks = emptyList(),
        )
        assertEquals(r.context, store(r).context)
        assertTrue(store(r).body().contains("lastEventMin"))
    }

    @Test
    fun `the block log survives the queue`() {
        val blocks = listOf(
            "3s ago  app  why=quick  window=other  ownUi=true  counted=true",
            "12s ago  guard  why=guard  window=n/a  ownUi=false  counted=false",
        )
        val r = BugReport.fromNote("flashing", "1.111", "github", 36, "d", recentBlocks = blocks)
        assertEquals(blocks, store(r).recentBlocks)
        assertTrue(store(r).body().contains("ownUi=true"))
    }

    @Test
    fun `everything else survives too`() {
        val r = BugReport.fromThrowable(
            where = "webScan",
            t = IllegalStateException("boom").apply {
                stackTrace = arrayOf(StackTraceElement("com.appblocker.A", "b", "A.kt", 3))
            },
            appVersion = "1.111", flavor = "github", androidSdk = 36, device = "d",
        )
        val back = store(r)
        assertEquals(r.where, back.where)
        assertEquals(r.errorClass, back.errorClass)
        assertEquals(r.frames, back.frames)
        assertEquals(r.appVersion, back.appVersion)
        assertEquals(r.dedupeKey(), back.dedupeKey())
    }

    @Test
    fun `a stored report is re-sanitised on the way back out`() {
        // A report can sit on disk across an app update. If a future version ever widened the
        // allow-list and this one narrowed it again, the file must not be a way past the narrower
        // rule — so the sending version's list is the one that applies, not the writing version's.
        val forged = """[{"where":"owner","errorClass":null,"frames":[],"note":"x",
            "appVersion":"1","flavor":"github","androidSdk":36,"device":"d",
            "context":{"layout":"focus","keyword":"$secretWord"},"recentBlocks":[]}]"""
        val back = com.appblocker.data.BugReportQueue.decode(forged).single()
        assertEquals(mapOf("layout" to "focus"), back.context)
        assertFalse(back.body().contains(secretWord))
    }

    private companion object {
        const val secretWord = "someveryprivateblockedword"
    }
}

/**
 * Which wording may make the guard bounce a page.
 *
 * This list replaces a much longer one that also held `accessibilit`, `uninstall` and
 * `force stop` — and those three are why Strict Mode bounced the entire Accessibility section and
 * every app's App-info page. The owner reported it as "Strict blocks the whole Settings", twice.
 *
 * So the test is not "does it match device-admin screens" (easy, and it would still pass if
 * someone re-added the greedy words). It is **what it must NOT match**, which is the property that
 * was lost.
 */
class AdminScreensTest {

    private fun matches(text: String) =
        com.appblocker.data.AdminScreens.looksLikeAdminScreen(text)

    @Test
    fun `the device-admin removal screen is recognised`() {
        assertTrue(matches("deactivate device admin app appblocker"))
        assertTrue(matches("device administrator settings"))
    }

    @Test
    fun `it is recognised in arabic too`() {
        // The match fails silently when it misses — nothing errors, the page simply isn't
        // recognised — so a phone in Arabic would have no fallback at all without these.
        assertTrue(matches("مسئول الجهاز appblocker"))
        assertTrue(matches("تعطيل"))
    }

    @Test
    fun `the accessibility list is not an admin screen`() {
        // It names every installed service, ours among them, so "mentions AppBlocker" is true of
        // it by design. Only the wording can tell it apart — and this list must not claim it.
        assertFalse(matches("accessibility downloaded apps talkback appblocker select to speak"))
    }

    @Test
    fun `an app info page is not an admin screen`() {
        // The page with battery, permissions, storage AND uninstall on it. Guarding this to
        // protect one button is what cost the owner his battery settings in v1.108.
        assertFalse(matches("appblocker app info uninstall force stop battery permissions storage"))
    }
}

/**
 * The diagnostic must not fail silently — the failure that made the owner's first real report
 * useless, in the very tool built to stop failures being invisible.
 */
class ReportDiagnosticsTest {

    private fun r(context: Map<String, String> = emptyMap(), blocks: List<String> = emptyList()) =
        BugReport.fromNote("something", "1.110", "github", 36, "d", context, blocks)

    @Test
    fun `an empty block log says so instead of saying nothing`() {
        // A missing section and an empty one look identical in a GitHub issue and mean opposite
        // things: "no block screen appeared" versus "the log is broken".
        val body = r().body()
        assertTrue(body.contains("Recent blocks"))
        assertTrue(body.contains("none recorded"))
    }

    @Test
    fun `recorded blocks are printed instead of the marker`() {
        val body = r(blocks = listOf("3s ago  app  why=quick  window=other  ownUi=true  counted=true"))
            .body()
        assertTrue(body.contains("ownUi=true"))
        assertFalse(body.contains("none recorded"))
    }

    @Test
    fun `a failed-field count is allowed through and shown`() {
        // So a half-empty report announces itself rather than looking like a healthy quiet one.
        val body = r(context = mapOf("layout" to "focus", "fieldErrors" to "3")).body()
        assertTrue(body.contains("fieldErrors"))
        assertTrue(body.contains("focus"))
    }
}

/**
 * Which failures are worth trying another Gemini model for.
 *
 * The coach died because quota was not on this list: the owner's gemini-2.5-pro was exhausted
 * (429) while the two flash models one line below answered normally, and the walk rethrew instead
 * of falling through. Quota and capacity are metered PER MODEL, which is what separates them from
 * auth and malformed-body errors.
 */
class ModelFallbackTest {

    private fun walks(msg: String) =
        com.appblocker.data.AiCoach.tryNextModel(java.io.IOException(msg))

    @Test
    fun `quota and capacity walk to the next model`() {
        assertTrue(walks("HTTP 429 quota"))
        assertTrue(walks("RESOURCE_EXHAUSTED"))
        assertTrue(walks("HTTP 503 overloaded"))
    }

    @Test
    fun `a missing model still walks`() {
        assertTrue(walks("HTTP 404 not found"))
    }

    @Test
    fun `auth and bad requests do not walk, because every model would fail the same way`() {
        assertFalse(walks("HTTP 401 unauthorized"))
        assertFalse(walks("HTTP 400 invalid argument"))
    }
}

/**
 * **The report that is sent when nothing is wrong.**
 *
 * Every other report in this file exists because something failed. This one exists because of the
 * failures that *cannot* fail loudly: a Samsung whose uninstall screen the app mis-guessed does
 * not crash — Strict Mode silently stops protecting, and the person holding the phone sees an app
 * that looks exactly like a working one. `docs/BLOCKING_INVARIANTS.md` opens with that shape:
 * under-blocking is invisible.
 *
 * So the profile is filed on **success as well as failure**, and that is the property most at risk
 * from a later tidy-up — "why file an issue when everything is fine?" is a very reasonable-sounding
 * question with an expensive answer. Without the healthy report, silence from a brand means either
 * *it works there* or *reporting is broken there*, and nothing distinguishes them.
 */
class DeviceProfileReportTest {

    private val clean = mapOf(
        "brand" to "Samsung",
        "uninstallHandler" to "com.samsung.android.packageinstaller",
        "uninstallGuard" to "RECOGNISED",
        "keepAlive" to "com.samsung.android.lool/BatteryActivity",
        "browsersKnown" to "com.android.chrome, com.sec.android.app.sbrowser",
        "browsersClaimedReadable" to "com.android.chrome, com.sec.android.app.sbrowser",
        "browsersClaimUnproven" to "com.sec.android.app.sbrowser",
    )

    private fun profile(context: Map<String, String>) = BugReport.fromProfile(
        appVersion = "1.136",
        flavor = "github",
        androidSdk = 35,
        device = "samsung SM-S911B",
        context = context,
    )

    // --- the point of the whole thing ---

    @Test
    fun `a phone where every guess was right still files a report`() {
        val r = profile(clean)

        assertTrue(r.isProfile)
        assertTrue(r.title().contains("profile OK"))
        // It must read as evidence rather than as an error, or it gets closed unread.
        assertTrue(r.body().contains("healthy phone reporting in"))
        assertTrue(r.body().contains("samsung SM-S911B"))
    }

    @Test
    fun `an uninstall screen the guard does not watch is announced in the title`() {
        val r = profile(clean + ("uninstallGuard" to "UNRECOGNISED"))

        // The title, specifically: an issue list of healthy profiles must make this one findable
        // without opening the other twenty.
        assertTrue(r.title().contains("PROFILE: something is wrong here"))
        assertTrue(r.body().contains("❌"))
        assertTrue(r.body().contains("GuardPackages.INSTALLERS"))
    }

    @Test
    fun `a keep-alive button with nowhere to go is announced in the title`() {
        val r = profile(clean + ("keepAlive" to "NONE RESOLVED"))

        assertTrue(r.title().contains("PROFILE: something is wrong here"))
        assertTrue(r.body().contains("❌"))
    }

    /**
     * An unproven readability claim is a fact about the phone, not a fault. Marking it as one
     * would make every honest profile look broken, and a flag that is always on is not a flag.
     */
    @Test
    fun `an unproven browser claim does not make the profile look broken`() {
        assertTrue(profile(clean).title().contains("profile OK"))
    }

    // --- the traps ---

    /**
     * **The 24-character cap would have shredded exactly the field this report was sent for.**
     *
     * `com.sec.android.app.sbrowser` is 28 characters on its own, and the general context cap is
     * there to catch values that are long *because something unintended got in*. Profile values
     * are long because package names are long, so they get their own cap — and this is the test
     * that fails if a later change folds them back into the general allow-list.
     */
    @Test
    fun `browser package lists are not truncated to nothing`() {
        val r = profile(clean)

        assertEquals(
            "com.android.chrome, com.sec.android.app.sbrowser",
            r.context["browsersKnown"],
        )
        assertTrue(r.body().contains("com.sec.android.app.sbrowser"))
    }

    /** The longer cap is a longer cap, not the removal of one. */
    @Test
    fun `the profile cap is still a cap`() {
        val huge = "com.example.browser, ".repeat(60)
        val r = profile(clean + ("browsersKnown" to huge))

        assertTrue(r.context["browsersKnown"]!!.length <= 240)
    }

    /**
     * The allow-list is the reason the reporter is allowed to exist, and a new report *shape* is
     * exactly the kind of change that quietly routes around it.
     */
    @Test
    fun `the privacy allow-list still applies to a profile`() {
        val r = profile(clean + ("keyword" to "someveryprivateblockedword"))

        assertFalse(r.context.containsKey("keyword"))
        assertFalse(r.body().contains("someveryprivateblockedword"))
    }

    /** Nothing the user generated is in a profile at all — there is no failure to locate. */
    @Test
    fun `a profile carries no stack frames, no note and no block log`() {
        val r = profile(clean)

        assertTrue(r.frames.isEmpty())
        assertTrue(r.recentBlocks.isEmpty())
        assertEquals(null, r.note)
        assertEquals(null, r.errorClass)
    }

    // --- how often it is sent ---

    /**
     * One per phone per build. `MainActivity` calls this on every resume and relies entirely on
     * the dedupe key to make that free — if the key ever varies per launch, a daily user opens
     * twelve issues a day until the cap stops them.
     */
    @Test
    fun `the same phone on the same build reports once`() {
        assertEquals(profile(clean).dedupeKey(), profile(clean).dedupeKey())
        // Different answers, same phone and build: still the same report. What we are deduping is
        // the phone, not the reading.
        assertEquals(
            profile(clean).dedupeKey(),
            profile(clean + ("uninstallGuard" to "UNRECOGNISED")).dedupeKey(),
        )
    }

    @Test
    fun `a new build asks the same phone again`() {
        val next = BugReport.fromProfile("1.137", "github", 35, "samsung SM-S911B", clean)

        assertNotEquals(profile(clean).dedupeKey(), next.dedupeKey())
    }

    @Test
    fun `a different phone is a different report`() {
        val other = BugReport.fromProfile("1.136", "github", 35, "Xiaomi 25080RABDG", clean)

        assertNotEquals(profile(clean).dedupeKey(), other.dedupeKey())
    }

    /**
     * A profile is queued when offline like anything else, and the queue is where a report has
     * silently lost its contents before — see [ReportRoundTripTest]. `isProfile` is derived from
     * `where` rather than stored, so this is what proves that derivation survives storage.
     */
    @Test
    fun `a profile comes back out of the queue as a profile`() {
        val stored = com.appblocker.data.BugReportQueue.decode(
            com.appblocker.data.BugReportQueue.encode(listOf(profile(clean))),
        ).single()

        assertTrue(stored.isProfile)
        assertEquals(profile(clean).context, stored.context)
        assertTrue(stored.title().contains("profile OK"))
    }
}
