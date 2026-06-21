package com.appblocker.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblocker.data.SettingsStore
import com.appblocker.service.AccessibilityUtil

@Composable
fun HomeScreen(vm: HomeViewModel = viewModel(), onStartFocus: () -> Unit) {
    val context = LocalContext.current
    val appsBlocked by vm.appsBlocked.collectAsState()
    val keywords by vm.keywordCount.collectAsState()
    val protectionOn = AccessibilityUtil.isEnabled(context)
    val adultOn = SettingsStore.blockAdult(context)

    Column(
        Modifier.fillMaxSize().padding(20.dp)
    ) {
        Text(
            "AppBlocker",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            "Stay focused. You've got this. 🌿",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(20.dp))

        // Protection status
        Card(
            Modifier.fillMaxWidth().clickable(enabled = !protectionOn) {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            colors = CardDefaults.cardColors(
                containerColor = if (protectionOn) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            ),
        ) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (protectionOn) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = if (protectionOn) MaterialTheme.colorScheme.primary else Color(0xFFFFB020),
                    modifier = Modifier.height(36.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        if (protectionOn) "Protection is on" else "Protection is off",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        if (protectionOn) "Your blocks and filters are active."
                        else "Tap to turn on the blocker (Accessibility).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Stats
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(Modifier.weight(1f), "$appsBlocked", "apps blocked")
            StatCard(Modifier.weight(1f), "$keywords", "keywords")
            StatCard(Modifier.weight(1f), if (adultOn) "On" else "Off", "adult filter")
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onStartFocus,
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            Icon(Icons.Filled.SelfImprovement, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Start a focus session", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StatCard(modifier: Modifier, value: String, label: String) {
    Card(
        modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}
