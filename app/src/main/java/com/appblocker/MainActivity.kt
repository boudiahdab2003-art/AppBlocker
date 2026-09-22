package com.appblocker

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.appblocker.data.AppLocale
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
    /**
     * The chosen language, applied before anything is laid out.
     *
     * One line per Activity is the whole of it here; the half that needs remembering is everything
     * the accessibility service draws, which never passes through this and has to be wrapped by
     * hand. See [com.appblocker.data.AppLocale].
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

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
        // A rebind in the next few seconds followed our own screen, not Android alone — see
        // ProtectionWatchdog.reboundEnding. On 14 Sep 2026 a six-hour stoppage ended this way.
        OwnUi.resumedAtRt = SystemClock.elapsedRealtime()
        // What this phone says about the guesses the app makes about it — sent once per phone per
        // build, and sent **even when every answer is the one we hoped for**. A phone we got wrong
        // does not crash; it quietly stops protecting, so a healthy report is the only signal
        // there is. Calling this every resume costs one prefs lookup after the first send —
        // ⚠️ which became true only when the key started being checked BEFORE the report is
        // built. This sentence was written as a fact about the queue's dedupe, and dedupe happens
        // inside `enqueue`, by which point the report and its usage-stream walk have already been
        // paid for. Before the flush, or the first profile would sit in the queue until the next
        // launch.
        BugReportSender.reportDeviceProfile(applicationContext)
        // The one report filed when nothing is wrong. Rides here rather than on a schedule for
        // the reason spelled out in reportWeekly: every background job in this app runs on the
        // component that is itself a suspect for the outages, and a health report must not depend
        // on the thing whose health it reports.
        BugReportSender.reportWeekly(applicationContext)
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

    private val releaseHandler = Handler(Looper.getMainLooper())
    private val releaseUi = Runnable { releaseIfLeft() }

    override fun onStart() {
        super.onStart()
        releaseHandler.removeCallbacks(releaseUi)
    }

    /**
     * **The screens are let go of once he has left them for [UI_RELEASE_MS].**
     *
     * The watcher shares this process, so whatever the screens hold is held by the blocker too, for
     * as long as the process lives. Measured on his phone on 22 Sep 2026: the bare watcher holds about
     * 11 MB of its own; a copy whose screens were opened once and left behind held 58 MB plus 45 MB
     * swapped out, and the phone that kills the heaviest process first is the one it runs on. Android
     * would keep a stopped screen for days, so it is finished here instead.
     *
     * Ten minutes, so a trip to Settings to grant something, or a quick look at another app, comes
     * back to the screen exactly as he left it. And never while another app's screen sits on top of
     * ours in our own task — that is a flow we started, and Back must still lead to us.
     */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !isFinishing) {
            releaseHandler.removeCallbacks(releaseUi)
            releaseHandler.postDelayed(releaseUi, UI_RELEASE_MS)
        }
    }

    private fun releaseIfLeft() {
        if (isFinishing || lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if (anotherAppOnTopOfOurTask()) {
            releaseHandler.postDelayed(releaseUi, UI_RELEASE_MS)
            return
        }
        finish()
    }

    /** Whether our task's top screen belongs to another app — Settings opened from ours, say. Unknown
     *  answers no: the worst a wrong "no" does is make him open the app again. */
    private fun anotherAppOnTopOfOurTask(): Boolean = runCatching {
        val am = getSystemService(ActivityManager::class.java) ?: return@runCatching false
        val ours = am.appTasks.map { it.taskInfo }.firstOrNull { info ->
            @Suppress("DEPRECATION")
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.taskId else info.id) == taskId
        } ?: return@runCatching false
        val top = ours.topActivity?.packageName ?: return@runCatching false
        top != packageName
    }.getOrDefault(false)

    override fun onDestroy() {
        releaseHandler.removeCallbacks(releaseUi)
        // Only the screens really ending, never a rotation: the list and its icons exist for them.
        if (isFinishing) InstalledAppsRepository.release()
        super.onDestroy()
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
        /** How long after he leaves the screens they are let go of — see [onStop]. */
        const val UI_RELEASE_MS = 10 * 60_000L

        const val EXTRA_OPEN_PERMISSIONS = "open_permissions"

        /** Sends the user straight to [com.appblocker.ui.RepairScreen]. Its own extra rather than
         *  reusing the permissions one: a stalled service is granted every permission it needs,
         *  so the permissions page has nothing on it to fix and reads as "everything is fine". */
        const val EXTRA_OPEN_REPAIR = "open_repair"
    }
}
