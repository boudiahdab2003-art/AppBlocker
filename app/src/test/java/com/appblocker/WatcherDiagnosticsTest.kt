package com.appblocker

import com.appblocker.data.WatcherDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one piece of the diagnostics screen that can be tested without a device: what it keeps of
 * an address.
 *
 * This matters more than a normal helper. The screen exists to answer *"could the address bar be
 * read, and what site did it say"*, and the honest answer needs only the host — but the value it
 * is handed is a full URL, and the path is the most private thing the watcher ever touches. It is
 * the part that says **which page**, not merely which site. Dropping it is a privacy promise the
 * screen makes in writing ("never the full address"), so it is pinned here rather than left to
 * whoever next edits the record.
 */
class WatcherDiagnosticsTest {

    @Test fun theHostSurvivesAndTheSchemeGoes() {
        assertEquals("instagram.com", WatcherDiagnostics.hostOf("https://instagram.com"))
        assertEquals("instagram.com", WatcherDiagnostics.hostOf("instagram.com"))
        assertEquals("m.instagram.com", WatcherDiagnostics.hostOf("HTTPS://M.Instagram.com"))
    }

    /** **The privacy promise.** The path is which page, and it never gets written down. */
    @Test fun thePathQueryAndFragmentAreDropped() {
        assertEquals("instagram.com", WatcherDiagnostics.hostOf("https://instagram.com/reels/xyz"))
        assertEquals("google.com", WatcherDiagnostics.hostOf("https://google.com/search?q=secret"))
        assertEquals("example.com", WatcherDiagnostics.hostOf("example.com?token=abc"))
    }

    /** Null means "the address bar could not be read", which is the finding the screen is for —
     *  so nothing blank may masquerade as an address that was successfully read. */
    @Test fun nothingReadableIsNull() {
        assertNull(WatcherDiagnostics.hostOf(null))
        assertNull(WatcherDiagnostics.hostOf(""))
        assertNull(WatcherDiagnostics.hostOf("   "))
        assertNull(WatcherDiagnostics.hostOf("https://"))
    }
}
