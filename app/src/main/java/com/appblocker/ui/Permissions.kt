package com.appblocker.ui

import android.Manifest
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.appblocker.Dist
import com.appblocker.admin.AppBlockerAdminReceiver
import com.appblocker.service.AccessibilityUtil
import com.appblocker.service.NotificationCountListener

/** One setup step the user may need to grant. */
data class Perm(
    val key: String,
    val label: String,
    val desc: String,
    val granted: Boolean,
    val essential: Boolean,
    val onFix: () -> Unit,
)

/** Bumps every time the app is resumed, so permission reads re-evaluate after Settings. */
@Composable
fun resumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}

fun hasUsageAccess(ctx: Context): Boolean {
    val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    @Suppress("DEPRECATION")
    val mode = appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}

fun isIgnoringBattery(ctx: Context): Boolean {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(ctx.packageName)
}

/** Whether our NotificationListenerService is enabled (Notification access granted). */
fun isNotificationListenerEnabled(ctx: Context): Boolean {
    val expected = ComponentName(ctx, NotificationCountListener::class.java).flattenToString()
    val enabled = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
        ?: return false
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}

private fun open(ctx: Context, action: String, withPackage: Boolean = false) {
    val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (withPackage) intent.data = Uri.parse("package:${ctx.packageName}")
    runCatching { ctx.startActivity(intent) }
}

/** Opens MIUI auto-start manager, falling back to the app's details page. */
private fun openAutostart(ctx: Context) {
    val miui = Intent().apply {
        component = ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
        )
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (runCatching { ctx.startActivity(miui) }.isFailure) {
        open(ctx, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPackage = true)
    }
}

/** All setup steps with live granted-state; re-checked on resume. */
@Composable
fun rememberPermissions(): List<Perm> {
    val ctx = LocalContext.current
    val tick = resumeTick()
    return remember(tick) {
        listOf(
            Perm(
                "accessibility", "Accessibility (the blocker)",
                "Required. Lets AppBlocker see which app is open and block it.",
                AccessibilityUtil.isEnabled(ctx), essential = true,
            ) { open(ctx, Settings.ACTION_ACCESSIBILITY_SETTINGS) },
            Perm(
                "overlay", "Display over other apps",
                "Required. Lets the block screen appear over a blocked app.",
                Settings.canDrawOverlays(ctx), essential = true,
            ) { open(ctx, Settings.ACTION_MANAGE_OVERLAY_PERMISSION, withPackage = true) },
            Perm(
                "usage", "Usage access",
                "Needed for daily limits and Insights.",
                hasUsageAccess(ctx), essential = false,
            ) { open(ctx, Settings.ACTION_USAGE_ACCESS_SETTINGS) },
            Perm(
                "notifications", "Notification access",
                "Optional. Lets AppBlocker count your daily notifications for Insights.",
                isNotificationListenerEnabled(ctx), essential = false,
            ) { open(ctx, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
            Perm(
                "battery", "Disable battery optimization",
                "Keeps the blocker running so it can't be killed in the background.",
                isIgnoringBattery(ctx), essential = false,
            ) {
                if (runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:${ctx.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.isFailure) open(ctx, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            },
            Perm(
                "autostart", "Auto-start",
                "On Xiaomi/MIUI, allow auto-start so blocking survives a reboot or cleanup.",
                granted = false, essential = false,
            ) { openAutostart(ctx) },
            Perm(
                "location", "Location",
                "Only for Wi-Fi and Location schedules. Not needed otherwise.",
                hasLocation(ctx), essential = false,
            ) { open(ctx, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPackage = true) },
            Perm(
                "deviceadmin", "Prevent uninstall (Device admin)",
                "Stops AppBlocker being uninstalled until you turn this off — extra friction against bypassing your blocks.",
                isDeviceAdminActive(ctx), essential = false,
            ) { toggleDeviceAdmin(ctx) },
        ).filter { Dist.LOCATION_SCHEDULES || it.key != "location" }
    }
}

/**
 * Google Play's AccessibilityService policy requires a prominent disclosure and explicit
 * consent BEFORE sending the user to accessibility settings. Returns a click handler that
 * shows the consent dialog first for the (ungranted) accessibility perm and passes straight
 * through for everything else — so every Grant call site gets the gate by using this.
 */
@Composable
fun rememberGatedFix(perm: Perm): () -> Unit {
    if (perm.key != "accessibility" || perm.granted) return perm.onFix
    var show by remember { mutableStateOf(false) }
    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text("How blocking works") },
            text = {
                Text(
                    "AppBlocker uses Android's Accessibility service to know which app is on " +
                        "screen, and — inside web browsers only — to read the page address and " +
                        "visible text so it can block your chosen sites and keywords.\n\n" +
                        "All of this is checked on your device only. Screen content is never " +
                        "stored and never sent anywhere.\n\n" +
                        "By continuing you agree to AppBlocker using the Accessibility service " +
                        "for blocking.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = { show = false; perm.onFix() }) { Text("Agree & continue") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } },
        )
    }
    return { show = true }
}

/** Whether AppBlocker is currently an active device admin (so it can't be uninstalled). */
fun isDeviceAdminActive(ctx: Context): Boolean {
    val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    return dpm.isAdminActive(AppBlockerAdminReceiver.componentName(ctx))
}

/** Turns device admin on (system confirm screen) or off (removes it, allowing uninstall). */
fun toggleDeviceAdmin(ctx: Context) {
    val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val admin = AppBlockerAdminReceiver.componentName(ctx)
    if (dpm.isAdminActive(admin)) {
        dpm.removeActiveAdmin(admin) // turn protection off so the app can be uninstalled
    } else {
        // NOTE: no FLAG_ACTIVITY_NEW_TASK — the system's ADD_DEVICE_ADMIN screen refuses to
        // start as a new task ("Cannot start ADD_DEVICE_ADMIN as a new task") and must run in
        // the caller's (Activity) task, which ctx is here.
        runCatching {
            ctx.startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Lets AppBlocker resist being uninstalled until you turn this off.",
                    )
                }
            )
        }
    }
}

fun hasLocation(ctx: Context): Boolean =
    ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Location blocking runs in the background (the accessibility service), so on Android 10+ it
 * needs the "Allow all the time" (background) location grant — foreground location isn't enough.
 */
fun hasBackgroundLocation(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    else hasLocation(ctx)
