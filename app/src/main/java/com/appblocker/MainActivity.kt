package com.appblocker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.appblocker.data.InstalledAppsRepository
import com.appblocker.service.ProtectionNotifier
import com.appblocker.service.ProtectionScheduler
import com.appblocker.ui.AppRoot
import com.appblocker.ui.LockGate
import com.appblocker.ui.theme.AppBlockerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var openPermissions by mutableStateOf(false)

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProtectionNotifier.createChannel(applicationContext)
        ProtectionScheduler.ensureScheduled(applicationContext)
        requestNotificationPermissionIfNeeded()
        openPermissions = intent.getBooleanExtra(EXTRA_OPEN_PERMISSIONS, false)

        // Warm the installed-apps cache early so the first editor open is fast too.
        lifecycleScope.launch { InstalledAppsRepository.ensureLoaded(applicationContext) }
        setContent {
            // If a PIN is set, the user must enter it before reaching the app.
            LockGate {
                AppBlockerTheme {
                    AppRoot(openPermissionsOnStart = openPermissions)
                }
            }
        }
    }

    // MainActivity is singleTask, so tapping the notification while the app is already running
    // delivers here instead of a fresh onCreate — forward the extra the same way.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_PERMISSIONS, false)) openPermissions = true
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
    }
}
