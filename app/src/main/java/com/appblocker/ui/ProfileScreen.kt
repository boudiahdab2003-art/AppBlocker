package com.appblocker.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.appblocker.data.AttemptCounter
import com.appblocker.data.PinStore
import com.appblocker.data.SettingsStore
import com.appblocker.service.AccessibilityUtil
import com.appblocker.ui.theme.AppGradients
import com.appblocker.ui.theme.softGlow

@Composable
fun ProfileScreen(
    strictActive: Boolean = false,
    onOpenPermissions: () -> Unit = {},
    updateVm: UpdateViewModel = viewModel(),
    vm: HomeViewModel = viewModel(),
    scheduleVm: ScheduleViewModel = viewModel(),
) {
    val context = LocalContext.current
    val updateState by updateVm.state.collectAsState()
    val appsBlocked by vm.appsBlocked.collectAsState()
    val schedules by scheduleVm.schedules.collectAsState()
    // Re-read on each resume so PIN / device-admin / permission changes elsewhere are reflected.
    val resumeTick = resumeTick()
    var pinSet by remember(resumeTick) { mutableStateOf(PinStore.isSet(context)) }
    val protectionOk = remember(resumeTick) { protectionOk(context) }
    var adminOn by remember(resumeTick) { mutableStateOf(isDeviceAdminActive(context)) }
    val blocksToday = remember(resumeTick) { AttemptCounter.summary(context).sumOf { it.today } }
    var showSetPin by remember { mutableStateOf(false) }
    var userName by remember(resumeTick) { mutableStateOf(SettingsStore.userName(context)) }
    var showRename by remember { mutableStateOf(false) }
    val locked = strictActive

    // Cap the content width on wide screens (tablets) so cards don't stretch edge-to-edge.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(
        Modifier.widthIn(max = 640.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        ProfileHeader(
            name = userName,
            version = appVersion(context),
            protectionOk = protectionOk,
            appsBlocked = appsBlocked,
            schedules = schedules.size,
            blocksToday = blocksToday,
            onEditName = { showRename = true },
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
                chevron = true,
                enabled = !locked,
                onClick = { showSetPin = true },
            )
            if (pinSet) {
                Divider()
                ProfileRow(
                    icon = Icons.Filled.Delete,
                    title = "Remove PIN",
                    subtitle = "Stop requiring a PIN to open settings.",
                    chevron = true,
                    destructive = true,
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
                // Same toggle as Setup & permissions: on -> system confirm screen, off -> remove.
                // removeActiveAdmin completes async, so flip the badge optimistically when
                // turning off; turning on is corrected on resume from the system screen.
                onClick = {
                    val wasOn = isDeviceAdminActive(context)
                    toggleDeviceAdmin(context)
                    adminOn = if (wasOn) false else isDeviceAdminActive(context)
                },
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
    }

    if (showSetPin) {
        SetPinDialog(
            onSet = { pin -> PinStore.set(context, pin); pinSet = true; showSetPin = false },
            onDismiss = { showSetPin = false },
        )
    }
    if (showRename) {
        RenameDialog(
            initial = userName,
            onSet = { newName -> SettingsStore.setUserName(context, newName); userName = newName; showRename = false },
            onDismiss = { showRename = false },
        )
    }
}

/** Gradient hero: the owner's identity (avatar + name) + live protection status + key numbers. */
@Composable
private fun ProfileHeader(
    name: String,
    version: String,
    protectionOk: Boolean,
    appsBlocked: Int,
    schedules: Int,
    blocksToday: Int,
    onEditName: () -> Unit,
    onFix: () -> Unit,
) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(AppGradients.accent)
            .padding(20.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(initials(name), style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold, color = Color.White)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(name.ifBlank { "Your name" }, style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                    Text("AppBlocker · v$version", style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f))
                }
                IconButton(onClick = onEditName) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit name", tint = Color.White)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.22f))
                    .clickable(enabled = !protectionOk, onClick = onFix)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Box(Modifier.size(9.dp).clip(CircleShape)
                    .background(if (protectionOk) Color(0xFF22C55E) else Color(0xFFFFB020)))
                Spacer(Modifier.width(7.dp))
                Text(if (protectionOk) "Protection active" else "Action needed — tap to fix",
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    color = Color.White)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroStat("$appsBlocked", "apps blocked", Modifier.weight(1f))
                HeroStat("$schedules", if (schedules == 1) "schedule" else "schedules", Modifier.weight(1f))
                HeroStat("$blocksToday", "blocks today", Modifier.weight(1f))
            }
        }
    }
}

/** One translucent number chip in the hero (e.g. "6 / apps blocked"). */
@Composable
private fun HeroStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.14f))
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            color = Color.White)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.85f), maxLines = 1)
    }
}

/** Up-to-two initials from a name, e.g. "Abdallah Ahdab" -> "AA". */
private fun initials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(1).uppercase()
        else -> (parts[0].take(1) + parts.last().take(1)).uppercase()
    }
}

/** Simple rename dialog for the profile name. */
@Composable
private fun RenameDialog(initial: String, onSet: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your name") },
        confirmButton = {
            TextButton(
                enabled = text.trim().isNotEmpty(),
                onClick = { onSet(text.trim()) },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(40) },
                label = { Text("Name") }, singleLine = true,
            )
        },
    )
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
    // Same card language as the Blocking tab: soft glow + faint outline, not a flat box.
    Box(
        Modifier.fillMaxWidth()
            .softGlow(RoundedCornerShape(20.dp), elevation = 4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                RoundedCornerShape(20.dp),
            )
    ) { Column { content() } }
}

/** An iconed settings row with an optional On/Off status badge and/or chevron.
 *  [destructive] renders it in the error color (e.g. Remove PIN). */
@Composable
private fun ProfileRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
    badge: Boolean? = null,
    chevron: Boolean = false,
    destructive: Boolean = false,
) {
    val accent = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = accent, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = if (destructive) accent else MaterialTheme.colorScheme.onSurface)
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
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
        modifier = Modifier.padding(start = 70.dp),
    )
}
