package com.appblocker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.GetApp
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.appblocker.ui.theme.AppGradients

@Composable
internal fun SetupBanner(missing: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF3A2A12)).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFFFB020),
            modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Finish setup", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text("$missing required ${if (missing == 1) "step" else "steps"} left — tap to fix",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * **"It's switched on and it isn't running."** Shown on the Blocking tab whenever the watchdog
 * reports [com.appblocker.service.ProtectionState.STALLED].
 *
 * Distinct from [SetupBanner], which is about a permission that was never granted. This one is
 * about a permission that *is* granted over a watcher the phone has killed — the Second Space
 * failure — and the two need opposite advice, so they must never share a banner. Red rather than
 * the setup amber: nothing is being blocked while this is up.
 */
@Composable
internal fun StoppedBanner(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF3A1520)).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFFF5A6E),
            modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Blocking has stopped", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text("Your phone shut it down — tap to switch it back on",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun UpdateBanner(version: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(AppGradients.accent).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.GetApp, contentDescription = null, tint = Color.White,
            modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Update available — v$version", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = Color.White)
            Text("Tap to download & install the latest version",
                style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color.White)
    }
}

/** After an app update, all blocking waits for this tap. Amber = needs attention. */
@Composable
internal fun UpdatePausedBanner(onReactivate: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF3A2A12)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFFFB020),
                modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Blocking is paused after the update", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Text("Nothing is blocked right now. Tap below when you're ready.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.padding(top = 12.dp))
        GradientButton(text = "Reactivate blocking", onClick = onReactivate)
    }
}

/**
 * The Blocking tab's header: the mark, the name, and the running countdown when there is one
 * ([remainingMillis] null when nothing is running).
 *
 * **It lives here, as one composable, so the rendering test can drive the real thing.** The row is
 * where the countdown bug actually showed itself — the pill's own formatter was only half of it.
 *
 * The arrangement is the fix and it is worth stating: a `Row` measures its **unweighted** children
 * first, so the title used to take its full intrinsic width and the pill was measured with
 * whatever was left. On a long session that squeeze wrapped the countdown onto two lines and
 * pushed it out of its own rounded shape. Now the title carries the weight and the pill does not,
 * which inverts who gives way — the volatile element keeps its intrinsic width, and the fixed
 * label truncates instead, which at ordinary font sizes it never has to.
 */
@Composable
internal fun HomeHeader(remainingMillis: Long?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ShieldMark()
        Spacer(Modifier.width(10.dp))
        Text(
            "AppBlocker",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (remainingMillis != null) {
            Spacer(Modifier.width(8.dp))
            TimerPill(remainingMillis)
        }
    }
}

@Composable
internal fun ShieldMark() {
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(AppGradients.accent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Shield, contentDescription = null, tint = Color.White,
            modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun TimerPill(remainingMillis: Long) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        Spacer(Modifier.width(8.dp))
        // Through the shared formatter, like every other countdown in the app. This one had its
        // own `"%02d:%02d"` and so never learnt about days: a 24-day session put 35507 minutes in
        // a pill the width of a word. maxLines is the belt to that braces — a countdown that
        // wraps does not just look wrong, it grows the pill and shoves the header out of shape.
        Text(
            fmtCountdown(remainingMillis, padMinutes = true),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
        )
    }
}
