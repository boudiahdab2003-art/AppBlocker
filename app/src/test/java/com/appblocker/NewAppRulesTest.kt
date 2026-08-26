package com.appblocker

import com.appblocker.data.NewAppRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What happens to an app the moment it is installed, without anyone being asked. */
class NewAppRulesTest {

    private fun decide(
        addNewApps: Boolean = true,
        allowlistMode: Boolean = false,
        launchable: Boolean = true,
        isProtector: Boolean = false,
        isBrowser: Boolean = false,
    ) = NewAppRules.shouldAutoBlock(addNewApps, allowlistMode, launchable, isProtector, isBrowser)

    @Test
    fun `an ordinary new app is auto-blocked when the setting is on`() {
        assertTrue(decide())
    }

    @Test
    fun `another blocker is never auto-blocked`() {
        // The whole point. The owner runs several blockers deliberately, so covering one of them
        // with our own block screen would disarm the backup he installed it to be.
        assertFalse(decide(isProtector = true))
    }

    @Test
    fun `a newly installed browser is still auto-blocked`() {
        // The opposite case, and the reason this is not simply "exempt anything protective":
        // installing a fresh browser is the cheapest way around a block list. Where the two
        // signals disagree, browser wins.
        assertTrue(decide(isProtector = true, isBrowser = true))
    }

    @Test
    fun `nothing is auto-blocked while the setting is off`() {
        assertFalse(decide(addNewApps = false))
        assertFalse(decide(addNewApps = false, isBrowser = true))
    }

    @Test
    fun `allowlist mode has nothing to add`() {
        // A new app is already blocked there by not being on the list.
        assertFalse(decide(allowlistMode = true))
    }

    @Test
    fun `a background package is not something the user opens`() {
        assertFalse(decide(launchable = false))
    }
}
