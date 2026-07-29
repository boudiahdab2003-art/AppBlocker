package com.appblocker.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.appblocker.BuildConfig
import com.appblocker.Dist
import com.appblocker.data.SettingsStore
import com.appblocker.data.SilentInstaller
import com.appblocker.data.Updater

/**
 * Checks for a release and installs it, with no tap.
 *
 * **Why a worker and not the app's own resume.** The app is killed the instant its replacement
 * lands, so installing while the owner is looking at a screen would make the app vanish under his
 * thumb. A background worker runs when he is not in it, which is exactly when an app may be
 * replaced without anyone noticing — which is the entire point of the feature.
 *
 * **Nothing here is allowed to matter.** Same rule as the reporter: an update is a convenience and
 * the blocker is not. Every step is wrapped, a failure simply means the next run tries again, and
 * no failure is ever surfaced — a notification saying "couldn't update" on a phone that is blocking
 * perfectly well is noise at best.
 *
 * The `play` flavour has no updater at all (Play Store rules), so this never runs there.
 */
class AutoUpdateWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        runCatching { attempt() }
        // Always success: a retry storm over an update would cost battery for something that can
        // simply wait for the next scheduled run.
        return Result.success()
    }

    private suspend fun attempt() {
        val ctx = applicationContext
        // The play flavour ships no updater at all (Play Store rules) — checking the flag rather
        // than assuming, so this file cannot become the thing that smuggles one in.
        if (!Dist.SELF_UPDATE) return
        if (!SettingsStore.autoUpdate(ctx)) return
        // Without this the download happens, the install is refused, and the phone has spent tens
        // of megabytes to arrive at the screen the owner would have had to tap anyway.
        if (!SilentInstaller.possible() || !Updater.canInstall(ctx)) return

        val release = Updater.latest() ?: return
        if (!Updater.isNewer(release.version, BuildConfig.VERSION_NAME)) return
        // Already handed to the system once and still not installed, so the system said no (or
        // asked for a tap, and the notification is sitting there waiting for one). Trying again
        // would mean re-downloading tens of megabytes every six hours to reach the same answer.
        if (SettingsStore.autoUpdateAttempt(ctx) == release.version) return

        val apk = runCatching { Updater.download(ctx, release.apkUrl) {} }.getOrNull() ?: return
        // Written before the attempt, and never cleared here: this process is killed the instant a
        // successful install lands, so a note written afterwards would not be written at all. Only
        // a real version change tears it up (UpdatePause), because only that proves an install
        // happened. One download per release, whatever the answer turns out to be.
        SettingsStore.setAutoUpdateAttempt(ctx, release.version)

        if (!SilentInstaller.install(ctx, apk)) {
            // The session never got off the ground — no permission on this build, no space, an
            // unreadable file. Nothing else in this worker will tell him, and an update he never
            // hears about is the thing this whole feature exists to prevent, so offer the ordinary
            // installer in a notification: one tap, and the APK on disk is already downloaded.
            InstallResultReceiver.notifyReady(ctx, Updater.installIntent(ctx, apk))
        }
    }
}
