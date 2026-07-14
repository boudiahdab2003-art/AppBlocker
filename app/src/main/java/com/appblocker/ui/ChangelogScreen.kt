package com.appblocker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appblocker.data.Updater
import com.appblocker.data.VersionLog
import com.appblocker.data.changelog
import com.appblocker.ui.theme.AppGradients
import com.appblocker.ui.theme.softGlow

/** Profile ▸ What's new: the app's full version history, newest first, with the installed
 *  version highlighted. Written to show how far the project has come. */
@Composable
fun ChangelogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val current = remember { Updater.current(context) }

    // No Scaffold here, so pad the system bars ourselves (edge-to-edge is forced on Android 15+).
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        EditorTopBar(title = "What's new", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            item {
                Text(
                    "${changelog.size} versions and counting",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "Everything this app has become, release by release.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
                )
            }
            items(changelog.size) { i ->
                VersionCard(changelog[i], isCurrent = changelog[i].version == current)
                Spacer(Modifier.padding(top = 14.dp))
            }
        }
    }
}

@Composable
private fun VersionCard(log: VersionLog, isCurrent: Boolean) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth()
            .then(if (isCurrent) Modifier.softGlow(shape, elevation = 10.dp) else Modifier)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .then(
                if (isCurrent) Modifier.border(1.5.dp, AppGradients.accent, shape)
                else Modifier.border(
                    1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), shape)
            )
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(AppGradients.accent)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text("v${log.version}", style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold, color = Color.White)
            }
            Spacer(Modifier.width(8.dp))
            Text(log.date, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            if (isCurrent) {
                Box(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    Text("You are here", style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Text(
            log.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
        )
        log.points.forEach { point ->
            Row(Modifier.padding(top = 6.dp)) {
                Box(
                    Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape)
                        .background(AppGradients.accent)
                )
                Spacer(Modifier.width(10.dp))
                Text(point, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
