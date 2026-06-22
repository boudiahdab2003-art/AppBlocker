package com.appblocker.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblocker.admin.AppBlockerAdminReceiver
import com.appblocker.ui.theme.AppGradients

@Composable
fun StrictModeScreen(vm: FocusViewModel = viewModel()) {
    val active by vm.isActive.collectAsState()
    val remaining by vm.remainingMillis.collectAsState()
    val context = LocalContext.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Text("Strict Mode", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(28.dp))

        LockOrb(locked = active)
        Spacer(Modifier.height(24.dp))

        Text(
            if (active) "Your blocks are locked" else "Your blocks are unlocked",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))

        if (active) {
            Text(
                "%02d:%02d".format(remaining / 60000, (remaining / 1000) % 60),
                fontSize = 56.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text("No stopping early — that was the point.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            LockedList()
        } else {
            Text("What's locked when active:", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            LockedList()
            Spacer(Modifier.height(28.dp))
            UnlockMethod(onActivate = { minutes ->
                ensureDeviceAdmin(context)
                vm.start(minutes)
            })
        }
    }
}

@Composable
private fun LockOrb(locked: Boolean) {
    // Outer halo
    Box(
        Modifier.size(190.dp)
            .background(AppGradients.glow(if (locked) 0.45f else 0.12f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        // Gradient ring
        Box(
            Modifier.size(140.dp).clip(CircleShape)
                .then(
                    if (locked) Modifier.border(3.dp, AppGradients.accent, CircleShape)
                    else Modifier.border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                ),
            contentAlignment = Alignment.Center,
        ) {
            // Inner disc
            Box(
                Modifier.size(124.dp).clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.colorScheme.surface,
                            )
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = null,
                    tint = if (locked) AppGradients.AccentStart else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(58.dp),
                )
            }
        }
    }
}

@Composable
private fun LockedList() {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LockedRow("Changing or removing your blocks")
        LockedRow("Turning off the blocker")
        LockedRow("Uninstalling the app")
    }
}

@Composable
private fun LockedRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Lock, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UnlockMethod(onActivate: (Int) -> Unit) {
    var minutes by remember { mutableIntStateOf(60) }
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(30, 60, 120, 240, 420)

    Text("UNLOCK METHOD", style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(10.dp))

    // Method (Timer) + duration selector row.
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Schedule, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text("Timer", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.weight(1f))
        Box {
            Row(
                Modifier.clip(RoundedCornerShape(10.dp))
                    .clickable { expanded = true }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(durationLabel(minutes), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { opt ->
                    DropdownMenuItem(
                        text = { Text(durationLabel(opt)) },
                        onClick = { minutes = opt; expanded = false },
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(24.dp))
    GradientButton(text = "Activate lock", onClick = { onActivate(minutes) })
}

private fun durationLabel(minutes: Int): String =
    if (minutes >= 60) "${minutes / 60}h" else "${minutes}m"

private fun ensureDeviceAdmin(context: Context) {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val admin = AppBlockerAdminReceiver.componentName(context)
    if (dpm.isAdminActive(admin)) return
    context.startActivity(
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Strict Mode needs this so AppBlocker can't be uninstalled until the timer ends."
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )
}
