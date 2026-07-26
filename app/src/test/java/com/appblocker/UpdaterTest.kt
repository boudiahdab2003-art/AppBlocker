package com.appblocker

import com.appblocker.data.Updater
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun newerMinor() = assertTrue(Updater.isNewer("1.10", "1.9"))   // 10 > 9, not string compare
    @Test fun newerMajor() = assertTrue(Updater.isNewer("2.0", "1.9.9"))
    @Test fun newerPatch() = assertTrue(Updater.isNewer("1.9.1", "1.9"))
    @Test fun sameIsNotNewer() = assertFalse(Updater.isNewer("1.9", "1.9"))
    @Test fun olderIsNotNewer() = assertFalse(Updater.isNewer("1.8", "1.9"))
    @Test fun shorterOlderPatch() = assertFalse(Updater.isNewer("1.9", "1.9.1"))
    @Test fun nonNumericTreatedAsZero() = assertTrue(Updater.isNewer("1.1", "1.x"))

    // The version numbering just crossed 1.99 -> 1.100 (the publish workflow increments the last
    // component, it does not carry). Every fix in this release reaches the phone through isNewer,
    // so if this compared as strings ("1.100" < "1.99") the updater would go permanently quiet
    // and no later version would ever be offered. Checked here rather than assumed.
    @Test fun hundredIsNewerThanNinetyNine() = assertTrue(Updater.isNewer("1.100", "1.99"))
    @Test fun ninetyNineIsNotNewerThanHundred() = assertFalse(Updater.isNewer("1.99", "1.100"))
    @Test fun hundredIsNotNewerThanItself() = assertFalse(Updater.isNewer("1.100", "1.100"))
    @Test fun hundredAndOneIsNewerThanHundred() = assertTrue(Updater.isNewer("1.101", "1.100"))

    // "The download finished" is not "the download is an app". The download path used to check
    // only the byte count, and only when the server declared one — so a captive-portal login page
    // (a complete, correctly-sized 200 response) went straight to the installer.
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun zipHeaderIsAnApk() =
        assertTrue(Updater.looksLikeApk(bytes(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)))

    @Test fun htmlIsNotAnApk() =
        assertFalse(Updater.looksLikeApk("<!DOCTYPE html>".toByteArray()))

    /** An empty response body is the shape a dropped connection leaves behind. */
    @Test fun emptyIsNotAnApk() = assertFalse(Updater.looksLikeApk(ByteArray(0)))

    /** Truncated at the first three bytes: the prefix matches, the file still isn't one. */
    @Test fun truncatedHeaderIsNotAnApk() =
        assertFalse(Updater.looksLikeApk(bytes(0x50, 0x4B, 0x03)))

    /** A plain ZIP that isn't the local-file-header variant — right first two bytes, wrong rest. */
    @Test fun wrongZipRecordIsNotAnApk() =
        assertFalse(Updater.looksLikeApk(bytes(0x50, 0x4B, 0x05, 0x06)))
}
