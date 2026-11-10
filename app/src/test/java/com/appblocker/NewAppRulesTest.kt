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
        isSafe: Boolean = false,
    ) = NewAppRules.shouldAutoBlock(addNewApps, allowlistMode, launchable, isProtector, isBrowser, isSafe)

    @Test
    fun `an app on the safe list is not auto-blocked, and a browser never counts as safe`() {
        assertFalse(decide(isSafe = true))
        assertTrue(decide(isSafe = true, isBrowser = true))
        assertTrue(com.appblocker.data.SafeApps.isSafe("com.google.android.apps.translate"))
        assertTrue(com.appblocker.data.SafeApps.isSafe("com.softissimo.reverso.context"))
        assertFalse(com.appblocker.data.SafeApps.isSafe("com.android.chrome"))
        assertFalse(com.appblocker.data.SafeApps.isSafe("com.instagram.android"))
    }

    /** An editable safe list would be an unblock button: the list is code, and only code. */
    @Test
    fun `nothing in the app can add to the safe list`() {
        val src = listOf("src/main/java/com/appblocker", "app/src/main/java/com/appblocker")
            .map { java.io.File(it) }.first { it.exists() }
        val writers = src.walkTopDown().filter { it.extension == "kt" && it.name != "SafeApps.kt" }
            .filter { "SafeApps.PACKAGES" in it.readText() }.map { it.name }.toList()
        assertTrue("SafeApps.PACKAGES is read outside its own file: $writers", writers.isEmpty())
    }

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
