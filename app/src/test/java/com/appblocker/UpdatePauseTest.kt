package com.appblocker

import com.appblocker.data.strictSessionNeedsUpdateClear
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePauseTest {
    @Test fun oldVersionStrictSessionIsCleared() =
        assertTrue(strictSessionNeedsUpdateClear(sessionVersion = 90, currentVersion = 91))

    @Test fun currentVersionStrictSessionSurvives() =
        assertFalse(strictSessionNeedsUpdateClear(sessionVersion = 91, currentVersion = 91))
}
