package com.appblocker.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblocker.admin.AppBlockerAdminReceiver
import com.appblocker.data.PinStore

@Composable
fun ProfileScreen(
    strictActive: Boolean = false,
    onOpenPermissions: () -> Unit = {},
    updateVm: UpdateViewModel = viewModel(),
) {
    val context = LocalContext.current
    val updateState by updateVm.state.collectAsState()
    // Re-read on each resume so a PIN set/cleared elsewhere is reflected when returning here.
    val resumeTick = resumeTick()
    var pinSet by remember(resumeTick) { mutableStateOf(PinStore.isSet(context)) }
    var showSetPin by remember { mutableStateOf(false) }
    val locked = strictActive

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Text(
            "Profile",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
        )
        if (locked) {
            Text(
                "🔒 Strict Mode is on — settings are locked until the timer ends.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
        }

        SectionTitle("Protection")
        SettingCard {
            SettingRow(
                title = if (pinSet) "Change PIN" else "Set a PIN",
                subtitle = if (pinSet) "A PIN is set. It's needed to change your blocks."
                else "Lock your settings so blocks can't be removed on a whim.",
                enabled = !locked,
                onClick = { showSetPin = true },
            )
            if (pinSet) {
                Divider()
                SettingRow(
                    title = "Remove PIN",
                    subtitle = "Stop requiring a PIN to open settings.",
                    enabled = !locked,
                    onClick = { PinStore.clear(context); pinSet = false },
                )
            }
            Divider()
            SettingRow(
                title = "Prevent uninstall",
                subtitle = "Turn on device admin so AppBlocker can't be uninstalled until you turn this off.",
                enabled = !locked,
                onClick = { requestDeviceAdmin(context) },
            )
        }

        SectionTitle("Permissions")
        SettingCard {
            SettingRow(
                title = "Setup & permissions",
                subtitle = "Accessibility, overlay, usage access, battery & auto-start — all in one place.",
                enabled = !locked,
                onClick = onOpenPermissions,
            )
        }

        SectionTitle("About")
        SettingCard {
            val sub = when (val s = updateState) {
                is UpdateState.Checking -> "Checking for updates…"
                is UpdateState.UpToDate -> "You're on the latest version."
                is UpdateState.Available -> "Update available: v${s.release.version} — tap to install"
                is UpdateState.Downloading -> "Downloading… ${s.percent}%"
                is UpdateState.Error -> s.message + " Tap to retry."
                else -> "Tap to check for updates"
            }
            SettingRow(
                title = "AppBlocker v${appVersion(context)}",
                subtitle = sub,
                enabled = updateState !is UpdateState.Downloading,
                onClick = {
                    when (val s = updateState) {
                        is UpdateState.Available -> updateVm.downloadAndInstall(s.release)
                        else -> updateVm.check()
                    }
                },
            )
        }
    }

    if (showSetPin) {
        SetPinDialog(
            onSet = { pin -> PinStore.set(context, pin); pinSet = true; showSetPin = false },
            onDismiss = { showSetPin = false },
        )
    }
}

private fun appVersion(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
}.getOrDefault("1.0")

private fun requestDeviceAdmin(context: Context) {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val admin = AppBlockerAdminReceiver.componentName(context)
    if (dpm.isAdminActive(admin)) {
        context.startActivity(
            Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return
    }
    // No FLAG_ACTIVITY_NEW_TASK — the system's ADD_DEVICE_ADMIN screen self-closes ("Cannot
    // start ADD_DEVICE_ADMIN as a new task") and must run in the caller's (Activity) task.
    context.startActivity(
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Enables AppBlocker to resist being uninstalled while your blocks are active."
            )
        }
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) { Column { content() } }
}

@Composable
private fun SettingRow(title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(16.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun Divider() {
    androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
}
