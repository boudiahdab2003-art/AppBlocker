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
import com.appblocker.data.AdminPrompt
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
                // On only. Turning it OFF from this row goes through the same typed gate as
                // Profile's — see rememberGatedFix, which is what every checklist tap passes
                // through. A second, cheaper door here would make the gate on the first one
                // decorative.
            ) { enableDeviceAdmin(ctx) },
        ).filter { Dist.LOCATION_SCHEDULES || it.key != "location" }
    }
}

/**
 * Everything that has to stand between a checklist tap and the thing it does.
 *
 * Two cases, both of which must not be bypassable by tapping the row somewhere else:
 *  - **Accessibility, not yet granted** — Google Play's AccessibilityService policy requires a
 *    prominent disclosure and explicit consent BEFORE sending the user to accessibility settings.
 *  - **Prevent uninstall, currently on** — turning it off removes the only protection that
 *    actually refuses an uninstall, so it costs a typed paragraph. Same gate as Profile's row;
 *    this row was the second door to the same switch.
 *
 * Everything else passes straight through, so every Grant call site gets both gates by using this.
 */
@Composable
fun rememberGatedFix(perm: Perm): () -> Unit {
    val ctx = LocalContext.current
    // Both states are declared unconditionally: a `return` before a `remember` would change the
    // number of slots in the composition when `granted` flips, which is exactly what these rows do.
    var showConsent by remember { mutableStateOf(false) }
    var showAdminGate by remember { mutableStateOf(false) }
    if (showConsent) {
        AlertDialog(
            onDismissRequest = { showConsent = false },
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
                TextButton(onClick = { showConsent = false; perm.onFix() }) {
                    Text("Agree & continue")
                }
            },
            dismissButton = { TextButton(onClick = { showConsent = false }) { Text("Cancel") } },
        )
    }
    if (showAdminGate) {
        PreventUninstallGate(
            onDismiss = { showAdminGate = false },
            onConfirm = { showAdminGate = false; disableDeviceAdmin(ctx) },
        )
    }
    return when {
        perm.key == "accessibility" && !perm.granted -> ({ showConsent = true })
        perm.key == "deviceadmin" && perm.granted -> ({ showAdminGate = true })
        else -> perm.onFix
    }
}

/**
 * The typed-paragraph gate in front of turning **Prevent uninstall** off.
 *
 * One composable rather than one per screen, for the reason [FrictionGate] itself gives: the
 * friction *is* the feature, and a second copy is a second thing to keep honest. Both doors to
 * this switch — Profile and the Setup checklist — render this.
 *
 * Unlike the off-switch guard's gate, confirming here acts immediately rather than starting an
 * hours-long wait. Deliberate: device admin has no emergency escape of its own, and "you cannot
 * uninstall this app or hand this phone over for two hours" is a real harm rather than a
 * hypothetical one. The paragraph is what stops a bad moment; the wait would only punish a good one.
 */
@Composable
fun PreventUninstallGate(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    FrictionGate(
        title = "Allow uninstalling",
        blurb = "This is the switch that actually refuses an uninstall — the guard only makes the " +
            "Settings page hard to reach. With it off, AppBlocker can be removed in a few taps, " +
            "and every block goes with it.\n\nSo, to be sure it's really you and really " +
            "deliberate: type the paragraph below — you can't paste it — and wait for the timer. " +
            "Turning it back on is always one tap.",
        confirmLabel = "Turn protection off",
        dismissLabel = "Keep it on",
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

/** Whether AppBlocker is currently an active device admin (so it can't be uninstalled). */
fun isDeviceAdminActive(ctx: Context): Boolean {
    val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    return dpm.isAdminActive(AppBlockerAdminReceiver.componentName(ctx))
}

/**
 * Opens Android's "Activate device admin app?" screen. Does nothing if it is already on.
 *
 * **There is deliberately no `toggleDeviceAdmin`.** This used to be one function that flipped
 * whichever way the current state pointed, which meant the *off* direction — the one that hands
 * back the ability to uninstall AppBlocker, and the only protection that actually refuses one —
 * was reachable from any caller that only meant "let the owner set this up". Two separate names
 * make every call site say which direction it wants, and make the dangerous direction greppable.
 * Everything that turns it off must go through [FrictionGate] first; see ProfileScreen.
 *
 * @param explanation shown by the system on its own screen, so it can name the reason we asked.
 */
fun enableDeviceAdmin(
    ctx: Context,
    explanation: String = "Lets AppBlocker resist being uninstalled until you turn this off.",
) {
    val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val admin = AppBlockerAdminReceiver.componentName(ctx)
    if (dpm.isAdminActive(admin)) return
    // Tell the guard we opened this, so it stands down while Android's activation screen is up.
    // That screen says "device admin" and offers "Uninstall app", so it matches every removal
    // marker the guard has — and blocking it means uninstall protection can never be switched ON.
    // See AdminPrompt: knowing we opened it beats trying to read the wording, which differs by
    // OEM and is translated. Every path that opens this screen must stamp it; StrictModeScreen
    // had its own copy of the intent and did not, so starting a Strict session could not switch
    // uninstall protection on at all — which is why the intent lives in one place now.
    AdminPrompt.requested()
    // NOTE: no FLAG_ACTIVITY_NEW_TASK — the system's ADD_DEVICE_ADMIN screen refuses to
    // start as a new task ("Cannot start ADD_DEVICE_ADMIN as a new task") and must run in
    // the caller's (Activity) task, which ctx is here.
    runCatching {
        ctx.startActivity(
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, explanation)
            }
        )
    }
}

/**
 * Removes device admin, so AppBlocker can be uninstalled again.
 *
 * **Never call this straight from a tap.** It is the end of the chain the whole off-switch guard
 * exists to defend: the guard only makes the Settings page hard to reach, whereas this is what
 * actually refuses the uninstall. Both call sites put [FrictionGate] in front of it.
 */
fun disableDeviceAdmin(ctx: Context) {
    val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    dpm.removeActiveAdmin(AppBlockerAdminReceiver.componentName(ctx))
    // The exemption is for a screen we opened; the state has just changed, so close it now rather
    // than leaving a minute of standing-down behind us.
    AdminPrompt.clear()
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
