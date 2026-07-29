package com.appblocker.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.appblocker.service.InstallResultReceiver
import java.io.File

/**
 * Installs an update **without the installer screen**, where Android allows it.
 *
 * ## Why this exists
 *
 * The owner asked for auto-update after a day in which the guard blocked its own update screen and
 * two rounds of fixes sat unread on his phone for hours. The thing that has actually cost him is
 * updates *not arriving*, so the aim is for a release to reach the phone with no tap at all.
 *
 * ## What Android actually permits
 *
 * A normal app cannot install packages silently. The exception, from API 31, is an app updating
 * **itself**: with [android.Manifest.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION] and
 * `setRequireUserAction(USER_ACTION_NOT_REQUIRED)`, the system skips the confirmation — but only
 * when the caller is the *installer of record* for the package it is updating, and the signature
 * matches.
 *
 * That has a consequence worth stating plainly rather than discovering: **the first update through
 * this path may still show the installer screen.** AppBlocker only becomes the installer of record
 * by installing itself once. After that, updates land silently. When the system refuses — an OEM
 * policy, a signature it dislikes, the record pointing elsewhere — the session answers
 * `STATUS_PENDING_USER_ACTION`, and that answer arrives at
 * [com.appblocker.service.InstallResultReceiver], which turns it into a notification carrying the
 * system's own confirmation screen. Not handling it is the one failure that would matter: the
 * update would not happen and nothing would say why.
 *
 * ## The trap this file has to avoid
 *
 * Every version change arms [UpdatePause], which switches **all blocking off** until the owner taps
 * Reactivate. That is right for an update he just asked for and standing in front of; it is
 * dangerous for one that installs by itself, because blocking would be off silently until he next
 * opened the app. So a silent install marks itself first ([SettingsStore.setAutoInstalled]) and
 * `UpdatePause` skips the pause for it. Blocking simply continues on the new version — and if the
 * update did break the watcher, `ProtectionWatchdog` is the thing that notices and says so, which
 * is its whole job.
 */
object SilentInstaller {

    /** Whether a no-tap install is even possible on this phone. API 31+ only; below that the
     *  system has no way to skip the confirmation, whoever is asking. */
    fun possible(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * Streams [apk] into a session and commits it without user action.
     *
     * Returns false if the session could not even be created or written — the caller then falls
     * back to the ordinary installer. A `true` here means the commit was *handed to the system*,
     * not that the install succeeded: the outcome arrives later, and the only outcome that matters
     * (the app is replaced) is one this process does not survive to observe.
     */
    fun install(context: Context, apk: File): Boolean {
        if (!possible()) return false
        val installer = context.packageManager.packageInstaller
        // Held outside the try so the failure path can abandon it. A session that is created and
        // then never committed or abandoned stays on the system's books: PackageInstaller caps how
        // many an app may hold open, so a step that keeps throwing after createSession succeeds —
        // no space to stage, an unreadable APK — would quietly fill that cap over successive
        // releases until creating one became impossible, and auto-update would stop for good with
        // nothing to see.
        var sessionId = -1
        return try {
            // Marked BEFORE the commit, not after: this process is killed the moment the
            // replacement lands, so anything written afterwards would never be written at all.
            // The flag is what stops the new version pausing blocking on the owner's behalf —
            // see the class KDoc, and SettingsStore.setAutoInstalled on why it is a commit().
            SettingsStore.setAutoInstalled(context, true)
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("appblocker", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                session.commit(resultSender(context, sessionId))
            }
            true
        } catch (e: Exception) {
            // The mark must not outlive a failed attempt, or the NEXT update — a manual one he
            // taps through himself — would skip the pause on the strength of this one.
            runCatching { SettingsStore.setAutoInstalled(context, false) }
            if (sessionId != -1) runCatching { installer.abandonSession(sessionId) }
            false
        }
    }

    /** Where the system reports what happened — [com.appblocker.service.InstallResultReceiver],
     *  addressed by class rather than by action so no implicit-broadcast rule can stand between
     *  the installer and the one answer that needs acting on. Required by `commit`, even though
     *  the interesting outcome (success) kills this process before anything is delivered. */
    private fun resultSender(context: Context, sessionId: Int): android.content.IntentSender =
        PendingIntent.getBroadcast(
            context,
            sessionId,
            Intent(context, InstallResultReceiver::class.java)
                .setAction(InstallResultReceiver.ACTION_RESULT),
            // MUTABLE because the system fills the status (and the confirmation intent) in.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        ).intentSender
}
