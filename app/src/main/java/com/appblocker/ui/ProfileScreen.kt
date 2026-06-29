package com.appblocker.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblocker.admin.AppBlockerAdminReceiver
import com.appblocker.data.PinStore
import com.appblocker.service.AccessibilityUtil
import com.appblocker.ui.theme.AppGradients

@Composable
fun ProfileScreen(
    strictActive: Boolean = false,
    onOpenPermissions: () -> Unit = {},
    updateVm: UpdateViewModel = viewModel(),
) {
    val context = LocalContext.current
    val updateState by updateVm.state.collectAsState()
    // Re-read on each resume so PIN / device-admin / permission changes elsewhere are reflected.
    val resumeTick = resumeTick()
    var pinSet by remember(resumeTick) { mutableStateOf(PinStore.isSet(context)) }
    val protectionOk = remember(resumeTick) { protectionOk(context) }
    val adminOn = remember(resumeTick) { isDeviceAdminActive(context) }
    var showSetPin by remember { mutableStateOf(false) }
    val locked = strictActive

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        ProfileHeader(
            version = appVersion(context),
            protectionOk = protectionOk,
            onFix = { if (!protectionOk) onOpenPermissions() },
        )

        if (locked) {
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)).padding(14.dp)
            ) {
                Text(
                    "🔒 Strict Mode is on — settings are locked until the timer ends.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        SectionTitle("Protection")
        SettingCard {
            ProfileRow(
                icon = Icons.Filled.Lock,
                title = if (pinSet) "Change PIN" else "Set a PIN",
                subtitle = if (pinSet) "A PIN is set. It's needed to change your blocks."
                else "Lock your settings so blocks can't be removed on a whim.",
                badge = pinSet,
                enabled = !locked,
                onClick = { showSetPin = true },
            )
            if (pinSet) {
                Divider()
                ProfileRow(
                    icon = Icons.Filled.Delete,
                    title = "Remove PIN",
                    subtitle = "Stop requiring a PIN to open settings.",
                    enabled = !locked,
                    onClick = { PinStore.clear(context); pinSet = false },
                )
            }
            Divider()
            ProfileRow(
                icon = Icons.Filled.Shield,
                title = "Prevent uninstall",
                subtitle = "Device admin stops AppBlocker being uninstalled until you turn this off.",
                badge = adminOn,
                enabled = !locked,
                onClick = { requestDeviceAdmin(context) },
            )
        }

        SectionTitle("Permissions")
        SettingCard {
            ProfileRow(
                icon = Icons.Filled.Tune,
                title = "Setup & permissions",
                subtitle = "Accessibility, overlay, usage access, battery & auto-start — all in one place.",
                chevron = true,
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
            ProfileRow(
                icon = Icons.Filled.Info,
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
            Divider()
            ProfileRow(
                icon = Icons.Filled.Share,
                title = "Share AppBlocker",
                subtitle = "Send the install link to a friend.",
                chevron = true,
                enabled = true,
                onClick = { shareApp(context) },
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "AppBlocker · v${appVersion(context)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            textAlign = TextAlign.Center,
        )
    }

    if (showSetPin) {
        SetPinDialog(
            onSet = { pin -> PinStore.set(context, pin); pinSet = true; showSetPin = false },
            onDismiss = { showSetPin = false },
        )
    }
}

/** Gradient hero with the app identity + live protection status. */
@Composable
private fun ProfileHeader(version: String, protectionOk: Boolean, onFix: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(AppGradients.accent)
            .clickable(enabled = !protectionOk, onClick = onFix).padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Shield, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("AppBlocker", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, color = Color.White)
                Text("Version $version", style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f))
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.22f)).padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Box(Modifier.size(9.dp).clip(CircleShape)
                        .background(if (protectionOk) Color(0xFF22C55E) else Color(0xFFFFB020)))
                    Spacer(Modifier.width(7.dp))
                    Text(if (protectionOk) "Protection active" else "Action needed — tap to fix",
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                        color = Color.White)
                }
            }
        }
    }
}

/** Whether the core blocking permissions (accessibility + overlay) are both granted. */
private fun protectionOk(context: Context): Boolean =
    AccessibilityUtil.isEnabled(context) && Settings.canDrawOverlays(context)

private fun shareApp(context: Context) {
    val text = "Block distracting apps & websites with AppBlocker:\n" +
        "https://github.com/boudiahdab2003-art/AppBlocker/releases/latest"
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "AppBlocker")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching {
        context.startActivity(Intent.createChooser(send, "Share AppBlocker")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 8.dp, top = 22.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) { Column { content() } }
}

/** An iconed settings row with an optional On/Off status badge and/or chevron. */
@Composable
private fun ProfileRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
    badge: Boolean? = null,
    chevron: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (badge != null) {
            Spacer(Modifier.width(10.dp))
            StatusPill(badge)
        }
        if (chevron) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun StatusPill(on: Boolean) {
    val color = if (on) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(if (on) "On" else "Off", style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun Divider() {
    androidx.compose.material3.HorizontalDivider(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
        modifier = Modifier.padding(start = 70.dp),
    )
}
