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
}
