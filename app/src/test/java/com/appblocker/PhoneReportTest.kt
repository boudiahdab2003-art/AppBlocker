package com.appblocker

import com.appblocker.data.UninstallGuardVerdict
import com.appblocker.data.uninstallGuardVerdict
import com.appblocker.service.GuardPackages
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The verdict behind Profile ▸ *What the blocker sees* ▸ "This phone".
 *
 * **Why a diagnostic gets a test.** Everything the app believes about non-Xiaomi phones was
 * reasoned rather than measured, and every one of those beliefs fails silently. This function is
 * the only place a wrong guess ever becomes visible, so the one thing it must never do is report
 * an unrecognised installer as fine — that would be a screen confidently telling the owner the
 * guard works on a phone where it cannot fire.
 */
class PhoneReportTest {

    private val known = GuardPackages.INSTALLERS

    /** The case the section exists for: a real installer we have never heard of. */
    @Test
    fun `an installer we do not know reads as a fault`() {
        assertEquals(
            UninstallGuardVerdict.UNRECOGNISED,
            uninstallGuardVerdict("com.brandnew.packageinstaller", known),
        )
    }

    @Test
    fun `the installers we do know read as recognised`() {
        for (pkg in listOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.miui.packageinstaller",
        )) {
            assertEquals(
                "$pkg is in GuardPackages.INSTALLERS and must read as recognised",
                UninstallGuardVerdict.RECOGNISED,
                uninstallGuardVerdict(pkg, known),
            )
        }
    }

    /**
     * "We could not check" must never be reported as "we checked and it is fine" — the same
     * can't-tell-is-not-a-no rule the watcher follows (docs/BLOCKING_INVARIANTS.md, invariant 4),
     * applied to a diagnostic. Package visibility and an OEM that resolves nothing both land here.
     */
    @Test
    fun `nothing resolved is unknown, not fine`() {
        assertEquals(UninstallGuardVerdict.UNKNOWN, uninstallGuardVerdict(null, known))
        assertEquals(UninstallGuardVerdict.UNKNOWN, uninstallGuardVerdict("", known))
        assertEquals(UninstallGuardVerdict.UNKNOWN, uninstallGuardVerdict("   ", known))
    }

    /** An empty known-set must not turn every phone into a fault report. It reads as unrecognised,
     *  which is correct — with no installers watched, the guard genuinely cannot fire. */
    @Test
    fun `an empty known set still answers honestly`() {
        assertEquals(
            UninstallGuardVerdict.UNRECOGNISED,
            uninstallGuardVerdict("com.android.packageinstaller", emptySet()),
        )
    }
}
