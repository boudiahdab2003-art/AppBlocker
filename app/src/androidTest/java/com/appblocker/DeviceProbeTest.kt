package com.appblocker

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblocker.data.DeviceProfile
import com.appblocker.data.DeviceVendor
import com.appblocker.data.SettingsStore
import com.appblocker.data.UninstallGuardVerdict
import com.appblocker.data.uninstallGuardVerdict
import com.appblocker.service.GuardPackages
import com.appblocker.service.KNOWN_BROWSERS
import com.appblocker.service.KNOWN_READABLE_BROWSERS
import com.appblocker.service.findRealBrowserPackages
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * **The five phones that could not be bought.**
 *
 * `PhoneReport.kt` opens by admitting it: everything the app believes about a non-Xiaomi phone was
 * reasoned rather than measured. That Samsung routes an uninstall through
 * `com.samsung.android.packageinstaller`. That Device Care sits at a particular activity. That
 * Samsung Internet, Huawei's browser and Vivo's keep Chromium's toolbar id. Each guess is
 * defensible, and each one **fails silently** — a guard that never fires and a button that opens
 * the wrong page both look exactly like a working app.
 *
 * `DiagnosticsScreen` already asks the phone those questions, and it answers them in sentences a
 * human reads off a screenshot. That is the right tool when the owner is holding the phone. It is
 * the wrong one for hardware nobody in this project owns: a screen has to be looked at, and nobody
 * is looking at a Galaxy in a rented cloud rack at 2am.
 *
 * **So this asks the same questions with assertions instead of sentences.** Point it at any
 * device — a cloud Samsung, a friend's Oppo, the emulator that gates every release — and the guesses
 * that are wrong *for that device* come back as failures naming the exact one-line fix. The three
 * that can fail are the three that break blocking:
 *
 * 1. an uninstall screen the Strict-Mode guard does not watch,
 * 2. a keep-alive button whose destination does not exist here,
 * 3. an OEM package installed on this phone that our `<queries>` block cannot see.
 *
 * Everything else is **reported, never asserted** ([theDeviceProfileIsRecorded]). Which browsers
 * are installed and which we merely claim to be able to read is a fact about the device, not a
 * fault in the code, and a probe that reddened the release gate for running on a Pixel would be
 * turned off within a week.
 *
 * **On the release-gate emulator most of this skips**, by design: an unrecognised brand gets the
 * generic advice, which deep-links nowhere and therefore has nothing to check. `assumeTrue` reports
 * that as skipped rather than passed, so a green run never claims to have measured a phone it
 * never saw. The manifest check (3) is the exception — it is a real regression test everywhere, and
 * it closes a gap `DeviceVendorTest` names and cannot cover: *"It cannot pin the manifest — a JVM
 * test cannot read it."* This one can.
 */
@RunWith(AndroidJUnit4::class)
class DeviceProbeTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    // ---- the three that are allowed to fail -------------------------------------------------

    /**
     * **Strict Mode's uninstall guard, on this actual phone.**
     *
     * `uninstallConfirmation` only treats a screen as a threat when it comes from a package in
     * `GuardPackages.INSTALLERS`. Get that list wrong for a brand and the guard answers "not a
     * threat" to a real uninstall dialog: the app comes off mid-session with nothing on screen to
     * say the guard was skipped. Six of the eight entries in that list were written from
     * reasoning, and this is the first thing that can contradict them.
     *
     * `UNKNOWN` passes deliberately. Package visibility or an OEM that resolves nothing both land
     * there, and "we could not check" must never be reported as "we checked and it is fine" —
     * so it is printed loudly and left to [theDeviceProfileIsRecorded], not turned into a failure
     * that would train someone to ignore this test.
     */
    @Test
    fun theUninstallScreenIsRecognised() {
        val handler = uninstallHandler()

        when (val verdict = uninstallGuardVerdict(handler, GuardPackages.INSTALLERS)) {
            UninstallGuardVerdict.RECOGNISED -> report("uninstall handler $handler — recognised")
            UninstallGuardVerdict.UNKNOWN ->
                report("uninstall handler could not be resolved here — guard unverified")
            UninstallGuardVerdict.UNRECOGNISED -> fail(
                "This phone (${deviceName()}) shows the \"uninstall AppBlocker?\" dialog from " +
                    "\"$handler\", which GuardPackages.INSTALLERS does not watch — so Strict Mode " +
                    "cannot stop an uninstall mid-session here. Fix: add \"$handler\" to " +
                    "GuardPackages.INSTALLERS. Verdict was $verdict.",
            )
        }
    }

    /**
     * **The keep-alive button, which is the one instruction that stops an OEM battery manager
     * switching blocking off days later.**
     *
     * `VendorAdvice` is explicit that the written steps are the deliverable and the deep link is a
     * bonus — a dead link falls back to the app's own details page rather than crashing. That is
     * the right behaviour and it is also why this has never been noticed: the button always opens
     * *something*. It just isn't the page its own label names.
     *
     * Skipped on a phone whose advice has no deep link, because the generic entry deliberately
     * points nowhere and there is nothing to be wrong about.
     */
    @Test
    fun theKeepAliveButtonHasSomewhereToGo() {
        val advice = DeviceVendor.advice()
        assumeTrue(
            "${deviceName()} gets the generic advice, which deep-links nowhere by design",
            advice.deepLinks.isNotEmpty(),
        )

        val landed = DeviceProfile.keepAliveTarget(context, advice)

        if (landed == null) {
            fail(
                "On ${deviceName()} the \"${advice.keepAliveLabel}\" button opens the app's own " +
                    "settings page instead of ${advice.brand}'s. None of these exist here: " +
                    advice.deepLinks.joinToString { "${it.first}/${it.second}" } +
                    ". Fix: add this phone's real activity to DeviceVendor's ${advice.brand} " +
                    "entry (and its package to <queries> in AndroidManifest.xml if it is new).",
            )
        }
        report("keep-alive deep link resolves: ${landed?.first}/${landed?.second}")
    }

    /**
     * **The manifest gap that was true of the MIUI link for this app's entire life.**
     *
     * A deep link needs two edits: the activity in `DeviceVendor`, and the package in `<queries>`
     * in AndroidManifest.xml. Miss the second and Android 11+ filters the component away —
     * `resolveActivity` returns null and the button silently falls back, on every phone of that
     * brand, forever. `DeviceVendor` calls its own list "a checklist with a test attached, not a
     * proof", because a JVM test cannot read a manifest.
     *
     * **This compares two lists the device itself produces.** The shell user sees every installed
     * package; the app sees only what its `<queries>` permit. A package in the first list and not
     * the second is a visibility gap, named exactly. That distinction is the whole point — without
     * the shell side, "not installed" and "installed but invisible" are the same
     * `NameNotFoundException` and the bug is untestable.
     *
     * Skipped, not failed, where the shell is unavailable: a diagnostic that cannot run is not
     * evidence of a fault, and this one gates releases.
     */
    @Test
    fun everyOemPackageInstalledHereIsVisibleToUs() {
        val installed = shellInstalledPackages()
        assumeTrue(
            "the shell package list is unavailable on ${deviceName()}, so visibility " +
                "cannot be told apart from absence",
            installed != null,
        )
        if (installed == null) return

        val invisible = DeviceVendor.DECLARED_KEEP_ALIVE_PACKAGES
            .filter { it in installed && !canSee(it) }

        if (invisible.isNotEmpty()) {
            fail(
                "${deviceName()} has ${invisible.joinToString()} installed, but AppBlocker cannot " +
                    "see " + (if (invisible.size == 1) "it" else "them") + " — so the keep-alive " +
                    "button falls back to the app's settings page here no matter what " +
                    "DeviceVendor says. Fix: add <package android:name=\"…\"/> to the <queries> " +
                    "block in AndroidManifest.xml for each of them.",
            )
        }
        report("all ${DeviceVendor.DECLARED_KEEP_ALIVE_PACKAGES.size} keep-alive packages accounted for")
    }

    // ---- the one that only ever reports ------------------------------------------------------

    /**
     * **The device profile — the thing worth having even when nothing is broken.**
     *
     * Everything above answers a yes/no we already knew to ask. This prints what we would want to
     * know from a phone nobody here owns, in one block that survives into the CI artifact: what
     * this phone is, which browsers it has, and — the interesting one — which of those browsers we
     * *claim* we can read the address bar in versus which have actually demonstrated it.
     *
     * That gap is where the Mi Browser bug lived for months. `KNOWN_READABLE_BROWSERS` claims the
     * OEM browsers keep Chromium's `url_bar`, and its own comment concedes that if one of them
     * renamed that id "the claim is wrong in the silent direction" — not blocked, not filtered
     * either. This cannot settle that (proving it needs the browser open on a blocked site, which
     * is a driven test, not a probe). What it can do is say which claims this phone is actually
     * relying on, so the next person knows which one to go and check.
     *
     * Never fails. A device's browser list is a fact about the device.
     */
    @Test
    fun theDeviceProfileIsRecorded() {
        val advice = DeviceVendor.advice()
        val installedBrowsers = runCatching { findRealBrowserPackages(context) }
            .getOrDefault(emptySet())
        val confirmed = runCatching { SettingsStore.readableBrowsers(context) }
            .getOrDefault(emptySet())

        val lines = buildList {
            add("device      : ${deviceName()}")
            add("android     : ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            add("fingerprint : ${Build.FINGERPRINT}")
            add("cloned apps : " + (advice.clonedAppsFeature ?: "(none warned about)"))
            add("")
            // Printed straight from the reporter's own map rather than re-derived, so this block
            // is also a preview of the issue this phone would file by itself — and a probe run
            // that looks right cannot be hiding a report that is empty.
            add("-- as sent by BugReportSender.reportDeviceProfile --")
            DeviceProfile.reportContext(context).toSortedMap()
                .forEach { (k, v) -> add("  $k = $v") }
            add("")
            // Not sent home: `confirmed` is per-install state and says more about how this phone
            // has been used than about the phone. It is worth seeing on a probe run, where the
            // device is a test rig rather than somebody's phone.
            add("browsers seen here : " +
                installedBrowsers.sorted().joinToString().ifBlank { "(none found)" })
            add("  confirmed read   : " +
                installedBrowsers.filter { it in confirmed }.sorted()
                    .joinToString().ifBlank { "(none yet on this device)" })
            add("  not claimed      : " +
                installedBrowsers.filter { it !in KNOWN_READABLE_BROWSERS }.sorted()
                    .joinToString().ifBlank { "(none)" })
            add("known-browser list covers : " +
                installedBrowsers.count { it in KNOWN_BROWSERS } + "/" + installedBrowsers.size)
        }

        val block = lines.joinToString("\n", prefix = "$BANNER\n", postfix = "\n$BANNER")
        report(block)
        // Best-effort, so the report survives as a file a CI job or a device farm can collect.
        runCatching {
            File(context.getExternalFilesDir(null), "device-probe.txt").writeText(block)
        }
    }

    // ---- helpers -----------------------------------------------------------------------------

    /**
     * Which package would actually show "uninstall this app?" here, or null if nothing did.
     *
     * Deliberately the same call the diagnostics screen and the auto-reporter make. A probe with
     * its own copy of the lookup would be testing its own copy.
     */
    private fun uninstallHandler(): String? = DeviceProfile.uninstallHandler(context)

    /** Whether the app's own `<queries>` let it see [pkg] at all. */
    private fun canSee(pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /**
     * Every package the *shell* can see, which is every package installed. Null when the shell is
     * not available to us — the caller must treat that as "could not check", never as "empty".
     */
    private fun shellInstalledPackages(): Set<String>? = runCatching {
        shell("pm list packages")
            .lineSequence()
            .mapNotNull { it.removePrefix("package:").trim().takeIf(String::isNotEmpty) }
            .toSet()
            .takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText() }

    private fun deviceName() = "${Build.MANUFACTURER} ${Build.MODEL}"

    /** Logcat and stdout both: the first survives on a device farm, the second in Gradle's report. */
    private fun report(message: String) {
        Log.i(TAG, message)
        println("$TAG $message")
    }

    private companion object {
        const val TAG = "DeviceProbe"
        const val BANNER = "---------------- AppBlocker device probe ----------------"
    }
}
