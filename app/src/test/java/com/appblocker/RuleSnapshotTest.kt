package com.appblocker

import com.appblocker.data.AppRule
import com.appblocker.data.BlockMode
import com.appblocker.data.RuleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window between the accessibility service binding and Room answering, which used to be a
 * window with no blocking in it at all. Xiaomi's Second Space rebinds the service on every
 * switch, so the owner walked through this one repeatedly and reported it as slowness.
 */
class RuleSnapshotTest {

    private val social = "com.example.social"
    private val notes = "com.example.notes"

    private fun rule(pkg: String, blocked: Boolean) =
        AppRule(packageName = pkg, appLabel = pkg, isBlocked = blocked)

    @Test
    fun `a blocked app is still blocked before the database has answered`() {
        // The bug, stated as a test: the live map is empty because Room has not emitted yet.
        // Reading that as "not blocked" is what left every app open after a space switch.
        val r = RuleSnapshot.ruleFor(social, live = emptyMap(), loaded = false, snapshot = setOf(social))
        assertTrue(r != null && r.isBlocked)
        assertEquals(BlockMode.HARD, r!!.mode)
    }

    @Test
    fun `an app that was never blocked is not blocked by the snapshot`() {
        // The snapshot must not invent blocks; it only restores what was already there.
        assertNull(RuleSnapshot.ruleFor(notes, live = emptyMap(), loaded = false, snapshot = setOf(social)))
    }

    @Test
    fun `once the database has answered the live rules are the only truth`() {
        // Otherwise unblocking an app would not take effect until the next restart - the
        // snapshot would quietly outrank the user.
        assertNull(
            RuleSnapshot.ruleFor(social, live = emptyMap(), loaded = true, snapshot = setOf(social)),
        )
    }

    @Test
    fun `a live rule always wins over the snapshot`() {
        val live = mapOf(social to rule(social, blocked = false))
        val r = RuleSnapshot.ruleFor(social, live = live, loaded = false, snapshot = setOf(social))
        assertFalse(r!!.isBlocked)
    }

    @Test
    fun `only the blocked apps are worth remembering`() {
        val encoded = RuleSnapshot.encode(listOf(rule(social, true), rule(notes, false)))
        assertEquals(setOf(social), encoded)
    }
}
