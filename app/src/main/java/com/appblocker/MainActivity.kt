package com.appblocker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.appblocker.data.InstalledAppsRepository
import com.appblocker.ui.AppRoot
import com.appblocker.ui.LockGate
import com.appblocker.ui.theme.AppBlockerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Warm the installed-apps cache early so the first editor open is fast too.
        lifecycleScope.launch { InstalledAppsRepository.ensureLoaded(applicationContext) }
        setContent {
            // If a PIN is set, the user must enter it before reaching the app.
            LockGate {
                AppBlockerTheme {
                    AppRoot()
                }
            }
        }
    }
}
