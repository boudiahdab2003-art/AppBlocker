package com.appblocker.ui

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.appblocker.service.AccessibilityUtil

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
        )
    }
}

fun hasLocation(ctx: Context): Boolean =
    ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

/**
 * Location blocking runs in the background (the accessibility service), so on Android 10+ it
 * needs the "Allow all the time" (background) location grant — foreground location isn't enough.
 */
fun hasBackgroundLocation(ctx: Context): Boolean =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
        ctx.checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    else hasLocation(ctx)
