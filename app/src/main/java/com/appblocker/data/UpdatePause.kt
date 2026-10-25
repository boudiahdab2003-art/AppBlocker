package com.appblocker.data

import android.content.Context

/**
 * **Blocking no longer pauses after an update** (invariant 92, his choice, 25 Oct 2026).
 *
 * Until v1.177 every version change switched ALL blocking off except the adult layer until
 * Reactivate was tapped on the Blocking tab — separately in each space — so that someone who had just
 * installed a build would confirm it still worked. In practice it was blocking off with the watcher
 * healthy and the switch reading ON: on 24 Oct 2026 Instagram went unblocked from after work until
 * 2 am, his main phone had been silent since the v1.176 install, and two of his other phones were
 * found sitting in the pause (`updatePaused true`). An update that really breaks the watcher is what
 * [com.appblocker.service.ProtectionWatchdog] and the silent repair exist for; the pause only added
 * a way for blocking to be off by design.
 *
 * So the pause never arms, and **any pause still standing is lifted** on every start — app open,
 * service connect, boot — which is how phones paused by an older build come back on their own, in
 * both spaces, with no tap. The flags themselves stay, and everything that reads them (the banner,
 * `ProtectionState.PAUSED`, [PauseLog], the `STILL PAUSED` report) stays with them: inert while the
 * flags are down, and the alarm if one ever survives. Cheaper than a second migration.
 *
 * Strict Mode has nothing to say here: removing a way for blocking to be off only makes it stronger.
 */
object UpdatePause {

    /**
     * The two flags as one value. They are only ever meaningful together, and treating them as one
     * is what stops half a decision surviving on its own — see [SettingsStore.writeUpdatePause].
     *
     * @param paused blocking is off, waiting for the Reactivate tap.
     * @param pending a version change has been noticed but not yet turned into a decision.
     */
    data class PauseState(val paused: Boolean, val pending: Boolean)

    /**
     * What a standing pause becomes: lifted, intent and all, in one value — or null when neither
     * flag is up and there is nothing to write. Pure, so the rule is tested.
     */
    internal fun lifted(state: PauseState): PauseState? =
        if (state.paused || state.pending) PauseState(paused = false, pending = false) else null

    /**
     * The Reactivate tap.
     *
     * ⚠️ **It must clear the pending intent too.** Clearing only `paused` left the intent behind,
     * and [checkVersionChange] re-reads it on every service connect — so the next boot, update,
     * space switch or revive quietly switched blocking off again, minutes or days after the owner
     * had turned it back on, with the accessibility switch still reading ON. Invariant 21 from the
     * other direction: not knowledge that died too young, but an instruction that lived too long.
     */
    internal fun reactivate(): PauseState = PauseState(paused = false, pending = false)

    /** Lifts the pause the owner is looking at, intent and all. */
    fun reactivate(context: Context) =
        SettingsStore.writeUpdatePause(context, reactivate())

    /** Call at every app/service start: notes a version change, and lifts any pause an older
     *  build left standing. It no longer arms one (invariant 92). */
    fun checkVersionChange(context: Context) {
        val current = AppVersion.code(context)
        if (current < 0L) return
        val last = SettingsStore.lastSeenVersionCode(context)
        if (last != current) {
            // Still consumed, so a stale "this one installed itself" mark cannot outlive the install
            // it describes. Nothing reads it to decide a pause any more (invariant 92).
            SettingsStore.setAutoInstalled(context, false)
            // Stamp the install for OutageLog. Installing our own APK IS Android killing this
            // process (invariant 21), and the owner installs releases several times a week —
            // which is the one thing that makes this app different from the other blockers he
            // compares it to. Whether that is what keeps stopping him is a question no amount of
            // reasoning settles; this is the field that settles it.
            OutageLog.noteVersionChange(context)
            // A version change is the only proof an install actually landed, so this is where the
            // "already tried this one" note is torn up — whoever installed it. Leaving it would
            // make the next release look like the one that was already declined.
            SettingsStore.setAutoUpdateAttempt(context, null)
            // A version change means the downloaded APK has served its purpose, whoever installed
            // it. It is tens of megabytes, and an auto-install leaves one behind on every release.
            if (last != -1L) Updater.discardDownload(context)
            // Recorded LAST, so a process killed part-way re-runs the whole thing on its next start;
            // re-running it is harmless.
            SettingsStore.setLastSeenVersionCode(context, current)
        }
        liftStandingPause(context)
    }

    /**
     * Lifts a pause an older build armed, intent and all, in one write ([SettingsStore.writeUpdatePause],
     * invariant 32). Runs on every start, so it is retried until it lands, and it costs one prefs
     * read when there is nothing to do.
     */
    private fun liftStandingPause(context: Context) {
        val lift = lifted(SettingsStore.updatePauseState(context)) ?: return
        SettingsStore.writeUpdatePause(context, lift)
    }
}
