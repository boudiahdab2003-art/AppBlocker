package com.appblocker.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appblocker.service.AccessibilityUtil

private const val ROOT = 0
private const val APPS = 1
private const val WEB = 2
private const val EXTRA = 3

/**
 * Quick Block editor — the always-on block set. A root list of categories
 * (Apps / Websites & words / Extra options); each opens its own sub-screen.
 * Read-only while Strict Mode is active.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockEditorScreen(
    strictActive: Boolean,
    onBack: () -> Unit,
    appsVm: AppListViewModel = viewModel(),
    webVm: WebFilterViewModel = viewModel(),
) {
    var section by remember { mutableIntStateOf(ROOT) }
    BackHandler(enabled = section != ROOT) { section = ROOT }

    val apps by appsVm.apps.collectAsState()
    val keywords by webVm.keywords.collectAsState()
    val appCount = apps.count { it.isBlocked }

    when (section) {
        APPS -> SubScreen("Apps", onBack = { section = ROOT }) {
            AppListSection(blockingLocked = strictActive)
        }
        WEB -> SubScreen("Websites & words", onBack = { section = ROOT }) {
            WebFilterSection(locked = strictActive, showAdult = false)
        }
        EXTRA -> ExtraOptionsScreen(locked = strictActive, onBack = { section = ROOT })
        else -> Root(
            strictActive = strictActive,
            appCount = appCount,
            keywordCount = keywords.size,
            onBack = onBack,
            onApps = { section = APPS },
            onWeb = { section = WEB },
            onExtra = { section = EXTRA },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Root(
    strictActive: Boolean,
    appCount: Int,
    keywordCount: Int,
    onBack: () -> Unit,
    onApps: () -> Unit,
    onWeb: () -> Unit,
    onExtra: () -> Unit,
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Quick Block", fontWeight = FontWeight.SemiBold) },
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
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        ) {
            Text(
                "These settings apply whenever you start a Quick Block or Strict Mode.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(top = 20.dp))

            val context = LocalContext.current
            if (!AccessibilityUtil.isEnabled(context)) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surface).padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, contentDescription = null,
                            tint = Color(0xFFFFB020), modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Protection is off", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.padding(top = 6.dp))
                    Text("Turn on the blocker so your choices actually block.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.padding(top = 12.dp))
                    GradientButton(text = "Turn on protection", onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    })
                }
                Spacer(Modifier.padding(top = 20.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Blocking", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Filled.Block, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Blocklist", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
            Text("Select apps or words you want to block.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.padding(top = 12.dp))

            // Grouped category card.
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                CategoryRow(Icons.Filled.Apps, "Apps", appCount, onApps)
                Divider()
                CategoryRow(Icons.Filled.Language, "Websites & words", keywordCount, onWeb)
            }

            Spacer(Modifier.padding(top = 16.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                CategoryRow(Icons.Filled.Tune, "Extra options", null, onExtra)
            }
        }
    }
}

@Composable
private fun CategoryRow(icon: ImageVector, label: String, count: Int?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        if (count != null) {
            Text("$count", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Divider() {
    androidx.compose.material3.HorizontalDivider(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
        modifier = Modifier.padding(start = 70.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubScreen(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
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
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) { content() }
    }
}
