package com.appblocker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.appblocker.data.InstalledAppsRepository
import com.appblocker.data.OwnUi
import com.appblocker.data.SettingsStore
import com.appblocker.data.UpdatePause
import com.appblocker.service.BugReportSender
import com.appblocker.service.ProtectionNotifier
import com.appblocker.service.ProtectionScheduler
import com.appblocker.ui.AppRoot
import com.appblocker.ui.LockGate
import com.appblocker.ui.theme.AppBlockerTheme
import com.appblocker.ui.theme.LocalThemeController
import com.appblocker.ui.theme.ThemeController
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var openPermissions by mutableStateOf(false)
    private var openRepair by mutableStateOf(false)

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashReporter()
        ProtectionNotifier.createChannel(applicationContext)
        ProtectionScheduler.ensureScheduled(applicationContext)
        UpdatePause.checkVersionChange(applicationContext)
        requestNotificationPermissionIfNeeded()
        openPermissions = intent.getBooleanExtra(EXTRA_OPEN_PERMISSIONS, false)
        openRepair = intent.getBooleanExtra(EXTRA_OPEN_REPAIR, false)

        // Warm the installed-apps cache early so the first editor open is fast too.
        lifecycleScope.launch { InstalledAppsRepository.ensureLoaded(applicationContext) }
        setContent {
            var themeMode by remember { mutableStateOf(SettingsStore.themeMode(this)) }
            val dark = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            // Match the status-bar icon contrast to the theme (dark icons on light bg).
            LaunchedEffect(dark) {
                WindowCompat.getInsetsController(window, window.decorView)
                    .isAppearanceLightStatusBars = !dark
            }
            val controller = ThemeController(themeMode) { newMode ->
                themeMode = newMode
                SettingsStore.setThemeMode(this, newMode)
            }
            // If a PIN is set, the user must enter it before reaching the app.
            LockGate {
                CompositionLocalProvider(LocalThemeController provides controller) {
                    AppBlockerTheme(darkTheme = dark) {
                        AppRoot(
                            openPermissionsOnStart = openPermissions,
                            openRepairOnStart = openRepair,
                        )
                    }
                }
            }
        }
    }

    // MainActivity is singleTask, so tapping the notification while the app is already running
    // delivers here instead of a fresh onCreate — forward the extra the same way.
    // Tells the blocking watcher that what's in front is our own UI and not a block cover over
    // some app — the two are the same package, so it can't tell them apart on its own. See OwnUi.
    override fun onResume() {
        super.onResume()
        OwnUi.visible = true
        // What this phone says about the guesses the app makes about it — sent once per phone per
        // build, and sent **even when every answer is the one we hoped for**. A phone we got wrong
        // does not crash; it quietly stops protecting, so a healthy report is the only signal
        // there is. The queue dedupes on the report's own key, so calling this every resume costs
        // one lookup after the first send. Before the flush, or the first profile would sit in the
        // queue until the next launch.
        BugReportSender.reportDeviceProfile(applicationContext)
        // Anything recorded while offline (or while crashing) goes out now. Resume is the moment
        // a network is most likely, and the send is off the main thread and best-effort.
        BugReportSender.flush(applicationContext)
    }

    /**
     * Records an uncaught crash before the process dies, then hands straight on to whoever was
     * handling crashes before us — normally Android's, which is what actually shows the dialog
     * and kills the process.
     *
     * **Delegating is the whole point.** Replacing the default handler without calling it would
     * leave a crashed app sitting there frozen instead of dying, which is a worse bug than the one
     * being reported. The recording is wrapped as well: a reporter that throws inside a crash
     * handler would replace a legible stack trace with its own.
     */
    private fun installCrashReporter() {
        if (!BugReportSender.enabled()) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { BugReportSender.report(applicationContext, "crash", error) }
            previous?.uncaughtException(thread, error)
        }
    }

    override fun onPause() {
        super.onPause()
        OwnUi.visible = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_PERMISSIONS, false)) openPermissions = true
        if (intent.getBooleanExtra(EXTRA_OPEN_REPAIR, false)) openRepair = true
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_OPEN_PERMISSIONS = "open_permissions"

        /** Sends the user straight to [com.appblocker.ui.RepairScreen]. Its own extra rather than
         *  reusing the permissions one: a stalled service is granted every permission it needs,
         *  so the permissions page has nothing on it to fix and reads as "everything is fine". */
        const val EXTRA_OPEN_REPAIR = "open_repair"
    }
}
