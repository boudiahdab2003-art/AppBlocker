package com.appblocker.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.appblocker.Dist
import com.appblocker.data.AppIcons
import com.appblocker.data.AttemptCounter
import com.appblocker.data.BlockLayouts
import com.appblocker.data.BlockThemes
import com.appblocker.data.DeviceBoot
import com.appblocker.data.OffSwitchGuard
import com.appblocker.data.PinStore
import com.appblocker.data.ServiceHealth
import com.appblocker.data.SettingsStore
import com.appblocker.service.AccessibilityUtil
import com.appblocker.service.BugReportSender
import com.appblocker.service.ProtectionState
import com.appblocker.service.ProtectionWatchdog
import com.appblocker.ui.theme.AppCard
import com.appblocker.ui.theme.AppGradients
import com.appblocker.ui.theme.LocalThemeController
import kotlinx.coroutines.delay

@Composable
fun ProfileScreen(
    strictActive: Boolean = false,
    onOpenPermissions: () -> Unit = {},
    onOpenChangelog: () -> Unit = {},
    onOpenInstructions: () -> Unit = {},
    onOpenDetox: () -> Unit = {},
    onOpenScenarios: () -> Unit = {},
    onOpenIconPicker: () -> Unit = {},
    onOpenBlockThemePicker: () -> Unit = {},
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
    val protectionStatus = remember(resumeTick) { protectionStatus(context) }
    var adminOn by remember(resumeTick) { mutableStateOf(isDeviceAdminActive(context)) }
    val blocksToday = remember(resumeTick) { AttemptCounter.summary(context).sumOf { it.today } }
    // Swallowed-error report, re-read on resume like the rest of this screen's live state.
    var healthErrors by remember(resumeTick) { mutableStateOf(ServiceHealth.errorCount(context)) }
    val healthError = remember(resumeTick) { ServiceHealth.lastError(context) }
    var showSetPin by remember { mutableStateOf(false) }
    var userName by remember(resumeTick) { mutableStateOf(SettingsStore.userName(context)) }
    var showRename by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var currentIcon by remember { mutableStateOf(AppIcons.current(context)) }
    // resumeTick so the row updates after returning from the picker.
    val currentBlockTheme = remember(resumeTick) { BlockThemes.current(context) }
    val currentBlockLayout = remember(resumeTick) { BlockLayouts.current(context) }
    val themeController = LocalThemeController.current
    val locked = strictActive

    // The off-switch guard, and the slow way out of it. Same shape as the adult pack's
    // cooling-off in KeywordsScreen: the switch below only *requests* the off, and the request is
    // served by a clock-proof deadline. Re-read on resume because the wait can be served while
    // the app sits in the background.
    val boot = remember { DeviceBoot.count(context) }
    var guardOn by remember(resumeTick) { mutableStateOf(SettingsStore.guardOffSwitch(context)) }
    var guardRequest by remember(resumeTick) {
        mutableStateOf(SettingsStore.guardUnlockRequest(context))
    }
    var showGuardGate by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    // `tick` only drives redraws of the countdown; the deadline itself is what decides.
    var guardTick by remember { mutableStateOf(0) }
    val guardUntilUnlock = guardRequest?.remaining(boot) ?: 0L
    val guardUntilExpiry = guardRequest?.remaining(boot, extraMs = OffSwitchGuard.UNLOCK_WINDOW_MS)
        ?: 0L
    val guardPhase = OffSwitchGuard.phase(
        hasRequest = guardRequest != null,
        untilUnlock = guardUntilUnlock,
        untilExpiry = guardUntilExpiry,
    )
    LaunchedEffect(guardRequest, guardTick) {
        if (guardRequest == null) return@LaunchedEffect
        if (guardUntilExpiry <= 0L) {
            // The whole request lapsed — the gate starts over.
            guardRequest = null
            SettingsStore.clearGuardUnlockRequest(context)
            return@LaunchedEffect
        }
        // Fast enough that a 15-minute wait and a 5-minute window both read as live.
        delay(1000)
        guardTick++
    }

    // Cap the content width on wide screens (tablets) so cards don't stretch edge-to-edge.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(
        Modifier.widthIn(max = 640.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        ProfileHeader(
            name = userName,
            version = appVersion(context),
            protectionOk = protectionStatus.ok,
            statusText = protectionStatus.text,
            fixable = protectionStatus.fixable,
            appsBlocked = appsBlocked,
            schedules = schedules.size,
            blocksToday = blocksToday,
            onEditName = { showRename = true },
            onFix = { if (protectionStatus.fixable) onOpenPermissions() },
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
        // Only appears when the blocker has actually swallowed something. These errors were being
        // recorded and never read by anything, which is how a broken blocker could look perfectly
        // healthy — blocking carries on by design when one goes wrong, so without a row like this
        // there is nothing to notice.
        if (healthErrors > 0) {
            SettingCard {
                ProfileRow(
                    icon = Icons.Filled.Warning,
                    title = if (healthErrors == 1) "Blocking hit 1 error" else "Blocking hit $healthErrors errors",
                    subtitle = (healthError ?: "Unknown") + "\n" + (
                        if (BugReportSender.enabled()) {
                            "Blocking kept running. Reported automatically — tap to clear."
                        } else {
                            "Blocking kept running. Tap to clear once you've reported it."
                        }
                        ),
                    chevron = true,
                    enabled = !locked,
                    onClick = { ServiceHealth.clearErrors(context); healthErrors = 0 },
                )
            }
        }
        SettingCard {
            ProfileRow(
                icon = Icons.Filled.BugReport,
                title = "Report a problem",
                subtitle = if (BugReportSender.enabled()) {
                    "Describe what went wrong and it goes straight to the developer. " +
                        "Never includes your blocked words, sites or app names."
                } else {
                    "Reporting isn't set up in this build."
                },
                chevron = true,
                // Deliberately allowed during Strict: reporting a bug changes no protection, and
                // Strict Mode is exactly when a bug is most worth hearing about.
                enabled = BugReportSender.enabled(),
                onClick = { showReport = true },
            )
        }
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
            Divider()
            ProfileRow(
                icon = Icons.Filled.Key,
                title = "Guard the off-switch",
                subtitle = when {
                    !guardOn -> "Off. Blocking can be switched off in Settings at any moment."
                    guardPhase == OffSwitchGuard.Phase.WAITING ->
                        "Unlocking in ${fmtCountdown(guardUntilUnlock)}. The guard is still on."
                    guardPhase == OffSwitchGuard.Phase.OPEN ->
                        "Unlocked — tap to turn the guard off. Closes again in " +
                            "${fmtCountdown(guardUntilExpiry)}."
                    else -> "On. The Accessibility page is blocked, so blocking can't be " +
                        "switched off on a whim. Turning this off takes " +
                        "${OffSwitchGuard.DELAY_LABEL}."
                },
                badge = guardOn,
                // Deliberately usable during Strict: the guard can only be turned *on* or have a
                // wait started, and Strict guards these pages by itself regardless of this row.
                enabled = true,
                onClick = {
                    when {
                        // Turning protection on is always instant, and drops any pending request.
                        !guardOn -> {
                            guardOn = true
                            SettingsStore.setGuardOffSwitch(context, true)
                            guardRequest = null
                            SettingsStore.clearGuardUnlockRequest(context)
                        }
                        // The wait is served — the off finally happens.
                        guardPhase == OffSwitchGuard.Phase.OPEN -> {
                            guardOn = false
                            SettingsStore.setGuardOffSwitch(context, false)
                            guardRequest = null
                            SettingsStore.clearGuardUnlockRequest(context)
                        }
                        // Nothing pending: turning it off starts at the type-and-wait gate.
                        guardPhase == OffSwitchGuard.Phase.GUARDED -> showGuardGate = true
                        // else: waiting — the subtitle above shows the countdown.
                    }
                },
            )
            if (guardPhase == OffSwitchGuard.Phase.WAITING) {
                Divider()
                ProfileRow(
                    icon = Icons.Filled.Close,
                    title = "Cancel the unlock",
                    subtitle = "Change your mind — the guard stays on and the wait is dropped.",
                    chevron = true,
                    // Backing out of lowering your guard is always instant, exactly like the
                    // adult pack's cancel. Nothing protective is lost by allowing it.
                    enabled = true,
                    onClick = {
                        guardRequest = null
                        SettingsStore.clearGuardUnlockRequest(context)
                    },
                )
            }
        }

        SectionTitle("Appearance")
        SettingCard {
            ProfileRow(
                icon = Icons.Filled.DarkMode,
                title = "Appearance",
                subtitle = "Theme: ${themeModeLabel(themeController.mode)}.",
                chevron = true,
                enabled = true, // cosmetic — allowed even during Strict
                onClick = { showTheme = true },
            )
            Divider()
            ProfileRow(
                icon = Icons.Filled.Palette,
                title = "App icon",
                subtitle = "Current: ${currentIcon.label}. Choose from your icon collection.",
                chevron = true,
                enabled = true, // cosmetic — allowed even during Strict
                onClick = onOpenIconPicker,
            )
            Divider()
            ProfileRow(
                icon = Icons.Filled.Wallpaper,
                title = "Block screen",
                subtitle = "Current: ${currentBlockLayout.label} in ${currentBlockTheme.label}. Change what's on the block screen and its colour.",
                chevron = true,
                // No longer purely cosmetic: hiding the pieces that make the screen
                // persuasive belongs with everything else Strict Mode freezes.
                enabled = !locked,
                onClick = onOpenBlockThemePicker,
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
            val sub = when {
                !Dist.SELF_UPDATE -> "Updates arrive through Google Play."
                else -> when (val s = updateState) {
                    is UpdateState.Checking -> "Checking for updates…"
                    is UpdateState.UpToDate -> "You're on the latest version."
                    is UpdateState.Available -> "Update available: v${s.release.version} — tap to install"
                    is UpdateState.Downloading -> "Downloading… ${s.percent}%"
                    is UpdateState.Error -> s.message + " Tap to retry."
                    else -> "Tap to check for updates"
                }
            }
            ProfileRow(
                icon = Icons.Filled.Info,
                title = "AppBlocker v${appVersion(context)}",
                subtitle = sub,
                enabled = Dist.SELF_UPDATE && updateState !is UpdateState.Downloading,
                onClick = {
                    when (val s = updateState) {
                        is UpdateState.Available -> updateVm.downloadAndInstall(s.release)
                        else -> updateVm.check()
                    }
                },
            )
            Divider()
            ProfileRow(
                icon = Icons.AutoMirrored.Filled.MenuBook,
                title = "Instructions",
                subtitle = "How every feature works, explained in detail.",
                chevron = true,
                enabled = true,
                onClick = onOpenInstructions,
            )
            Divider()
            ProfileRow(
                icon = Icons.Filled.SelfImprovement,
                title = "Dopamine detox guide",
                subtitle = "Clear rules to reset your brain's reward system.",
                chevron = true,
                enabled = true,
                onClick = onOpenDetox,
            )
            Divider()
            ProfileRow(
                icon = Icons.Filled.Bolt,
                title = "Scenarios",
                subtitle = "Guides for the hard moments — relapse, focus, sleep, and more.",
                chevron = true,
                enabled = true,
                onClick = onOpenScenarios,
            )
            Divider()
            ProfileRow(
                icon = Icons.Filled.History,
                title = "What's new",
                subtitle = "Every version and what it changed.",
                chevron = true,
                enabled = true,
                onClick = onOpenChangelog,
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
    if (showReport) {
        ReportProblemSheet(
            onDismiss = { showReport = false },
            onSend = { note ->
                BugReportSender.reportNote(context, note)
                showReport = false
                Toast.makeText(context, "Sent — thank you", Toast.LENGTH_SHORT).show()
            },
        )
    }
    if (showGuardGate) {
        FrictionGate(
            title = "Turn off the guard",
            blurb = "This is the switch that stops you switching blocking off in a bad moment. " +
                "To be sure it's really you and really deliberate, type the paragraph below — " +
                "you can't paste it — and wait for the timer. Even then the guard stays on for " +
                "another ${OffSwitchGuard.DELAY_LABEL}; after that you have " +
                "${OffSwitchGuard.WINDOW_LABEL} to turn it off.",
            confirmLabel = "Start the ${OffSwitchGuard.DELAY_LABEL} wait",
            dismissLabel = "Keep it on",
            onDismiss = { showGuardGate = false },
            onConfirm = {
                // Passing the gate does NOT lower the guard — it starts the wait. The guard keeps
                // standing until that is served and the owner acts inside the window.
                SettingsStore.setGuardUnlockRequest(
                    context, OffSwitchGuard.UNLOCK_DELAY_MS, boot,
                )
                guardRequest = SettingsStore.guardUnlockRequest(context)
                showGuardGate = false
            },
        )
    }
    if (showTheme) {
        ThemeDialog(
            current = themeController.mode,
            onSelect = { mode -> themeController.onChange(mode); showTheme = false },
            onDismiss = { showTheme = false },
        )
    }
}

private fun themeModeLabel(mode: String): String = when (mode) {
    "light" -> "Light"
    "dark" -> "Dark"
    else -> "System default"
}

/** Three-way theme picker: follow the phone, or force Light / Dark. */
@Composable
private fun ThemeDialog(current: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Appearance") },
        text = {
            Column {
                listOf(
                    "system" to "System default",
                    "light" to "Light",
                    "dark" to "Dark",
                ).forEach { (mode, label) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(mode) }.padding(vertical = 14.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            label, Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (mode == current) {
                            Icon(
                                Icons.Filled.Check, contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        },
    )
}

/** Gradient hero: the owner's identity (avatar + name) + live protection status + key numbers. */
@Composable
private fun ProfileHeader(
    name: String,
    version: String,
    protectionOk: Boolean,
    statusText: String,
    fixable: Boolean,
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
                    .clickable(enabled = fixable, onClick = onFix)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Box(Modifier.size(9.dp).clip(CircleShape)
                    .background(if (protectionOk) Color(0xFF22C55E) else Color(0xFFFFB020)))
                Spacer(Modifier.width(7.dp))
                Text(statusText,
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

/** What the hero's status pill should say, and whether tapping it can help. */
private data class ProtectionStatus(val ok: Boolean, val text: String, val fixable: Boolean)

/**
 * Whether blocking is genuinely working: the core permissions (accessibility + overlay) are
 * granted, the watcher is still alive, AND blocking isn't paused. A service the phone killed hours
 * ago still reports as "enabled", so the health check is what makes this row honest — see
 * ProtectionWatchdog.state.
 *
 * The pause after an update mattered most and was missing: blocking was entirely off, and this row
 * said "Protection active" until the user happened to open the Blocking tab.
 */
private fun protectionStatus(context: Context): ProtectionStatus {
    if (!AccessibilityUtil.isEnabled(context) || !Settings.canDrawOverlays(context)) {
        return ProtectionStatus(false, "Action needed — tap to fix", fixable = true)
    }
    return when (ProtectionWatchdog.state(context)) {
        ProtectionState.OK -> ProtectionStatus(true, "Protection active", fixable = false)
        ProtectionState.OFF -> ProtectionStatus(false, "Action needed — tap to fix", fixable = true)
        ProtectionState.STALLED ->
            ProtectionStatus(false, "Blocking stalled — tap to fix", fixable = true)
        // Reactivating lives on the Blocking tab, which this screen can't navigate to, so the
        // text carries the instruction instead of pretending a tap here would help.
        ProtectionState.PAUSED ->
            ProtectionStatus(false, "Paused after update — see Blocking tab", fixable = false)
    }
}

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
    // The shared card (theme/Dimens.kt) IS this card language — soft glow, faint outline, 20dp.
    // Six screens had grown their own copy of it, which is how their corner radii drifted apart.
    // No padding: these cards hold full-bleed rows that draw their own.
    AppCard(elevation = 4.dp, contentPadding = PaddingValues(0.dp)) { content() }
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

/**
 * "Report a problem": a box to describe what went wrong, sent with the app version, Android
 * version and device model attached.
 *
 * The promise under the field is load-bearing and literally true — see [com.appblocker.data.BugReport],
 * which builds every report from a named allow-list and never reads an exception's message,
 * because that is where a blocked word would be quoted back. Saying so here is what makes the
 * feature usable by someone whose blocked list is the most private thing on his phone.
 *
 * A full screen rather than a Dialog, like [FrictionGate] and for the same device reason: dialog
 * windows report zero insets on the owner's phone, so the keyboard would sit on top of the field.
 */
@Composable
private fun ReportProblemSheet(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    BackHandler { onDismiss() }
    Column(
        Modifier.fillMaxSize().background(com.appblocker.ui.theme.appBackground())
            .safeDrawingPadding(),
    ) {
        EditorTopBar("Report a problem", onBack = onDismiss)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            Text(
                "What happened? Anything helps — what you were doing, which app, whether a " +
                    "block screen appeared when it shouldn't have, or didn't when it should.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Describe the problem") },
                singleLine = false,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Sent with: app version, Android version, phone model, and your current " +
                    "settings — which block screen you use, whether blocking is running, and " +
                    "how many blocks happened today.\n\n" +
                    "Never sent: your blocked words, the sites you visit, or which apps you " +
                    "block.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        GradientButton(
            text = "Send",
            enabled = text.isNotBlank(),
            onClick = { onSend(text) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp),
        )
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.CenterHorizontally)
                .padding(top = 4.dp, bottom = 12.dp),
        ) { Text("Cancel") }
    }
}
