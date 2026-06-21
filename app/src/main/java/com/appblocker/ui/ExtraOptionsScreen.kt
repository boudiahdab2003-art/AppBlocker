package com.appblocker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GetApp
import androidx.compose.material.icons.filled.NoAdultContent
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material.icons.filled.Web
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import com.appblocker.data.SettingsStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtraOptionsScreen(locked: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    var adult by remember { mutableStateOf(SettingsStore.blockAdult(context)) }
    var newApps by remember { mutableStateOf(SettingsStore.addNewApps(context)) }
    var purchases by remember { mutableStateOf(SettingsStore.blockPurchases(context)) }
    var unsupported by remember { mutableStateOf(SettingsStore.blockUnsupportedBrowsers(context)) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Extra options", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            GradientButton(text = "Save", onClick = onBack, modifier = Modifier.padding(16.dp))
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            OptionCard(
                Icons.Filled.GetApp, "Add newly installed apps",
                "If on, newly installed apps are automatically blocked.",
                newApps, enabled = !locked,
            ) { newApps = it; SettingsStore.setAddNewApps(context, it) }
            Spacer(Modifier.padding(top = 12.dp))
            OptionCard(
                Icons.Filled.NoAdultContent, "Porn sites blocking",
                "Adult sites are automatically detected and blocked in all your browsers.",
                adult, enabled = !locked,
            ) { adult = it; SettingsStore.setBlockAdult(context, it) }
            Spacer(Modifier.padding(top = 12.dp))
            OptionCard(
                Icons.Filled.ShoppingBasket, "In-app purchases blocking",
                "Blocks the Play billing prompt to stop unwanted purchases.",
                purchases, enabled = !locked,
            ) { purchases = it; SettingsStore.setBlockPurchases(context, it) }
            Spacer(Modifier.padding(top = 12.dp))
            OptionCard(
                Icons.Filled.Web, "Block unsupported browsers",
                "If a browser can't be filtered, block it so the web filter can't be bypassed.",
                unsupported, enabled = !locked,
            ) { unsupported = it; SettingsStore.setBlockUnsupportedBrowsers(context, it) }
        }
    }
}

@Composable
private fun OptionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface).padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
        }
        Spacer(Modifier.padding(top = 8.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
