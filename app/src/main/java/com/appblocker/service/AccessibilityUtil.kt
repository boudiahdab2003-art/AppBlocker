package com.appblocker.service

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * Whether AppBlocker's accessibility watcher is currently enabled by the user.
 *
 * This is the app's single answer to "is protection on": the Profile status row, the setup
 * checklist, the watchdog that warns when blocking has stopped, and the `serviceOn` line in every
 * bug report all read it. Nothing *blocks* differently because of it — but a wrong answer sends
 * the owner to fix something that isn't broken, or makes a bug report lie about the one fact I'd
 * reason from first.
 */
object AccessibilityUtil {

    fun isEnabled(context: Context): Boolean = isListed(
        setting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ),
        expected = ComponentName(context, BlockerAccessibilityService::class.java)
            .flattenToString(),
    )

    /**
     * Whether [expected] appears in Android's colon-separated list of enabled services.
     *
     * **The list is not written in one form.** A component can be spelt out in full
     * (`com.appblocker/com.appblocker.service.BlockerAccessibilityService`) or relative to its own
     * package (`com.appblocker/.service.BlockerAccessibilityService`), and which one lands in the
     * setting depends on what wrote it — the framework's own Settings UI, an OEM's replacement for
     * it, or a restore from backup. `flattenToString()` always produces the long form, so a plain
     * string comparison against it reads a perfectly healthy service as **off** on any build that
     * stored the short one.
     *
     * That failure is quiet and self-consistent: the checklist would keep asking for a permission
     * already granted, the watchdog would keep announcing that blocking had stopped while it ran
     * normally, and reports would carry `serviceOn=false` for a service that was demonstrably
     * working. No evidence it is happening on the owner's phone — this is the form Android writes
     * there today. It is fixed because the cost of being wrong is measured in wild goose chases,
     * and the fix is to compare identities rather than spellings.
     *
     * Pure string work rather than `ComponentName.unflattenFromString`, which returns null under
     * `isReturnDefaultValues` and would leave this untestable — and this is exactly the kind of
     * parsing that needs a test.
     */
    internal fun isListed(setting: String?, expected: String): Boolean {
        val want = expand(expected).lowercase()
        if (want.isEmpty()) return false
        return setting.orEmpty().split(':')
            .any { expand(it.trim()).lowercase() == want }
    }

    /** `pkg/.Class` → `pkg/pkg.Class`; anything already absolute is returned unchanged. */
    private fun expand(entry: String): String {
        val slash = entry.indexOf('/')
        if (slash <= 0 || slash == entry.length - 1) return entry
        val pkg = entry.substring(0, slash)
        val cls = entry.substring(slash + 1)
        return if (cls.startsWith(".")) "$pkg/$pkg$cls" else entry
    }
}
