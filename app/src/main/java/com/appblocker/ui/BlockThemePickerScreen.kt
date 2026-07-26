package com.appblocker.ui

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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appblocker.data.BlockThemes
import com.appblocker.data.SettingsStore
import com.appblocker.data.backdropSolid
import com.appblocker.ui.theme.AppGradients
import com.appblocker.ui.theme.AppShapes

/**
 * Profile ▸ Block screen: choose how the block screen looks. Same screen and same behaviour in
 * every option — only the colours change, so nothing here can affect blocking.
 */
@Composable
fun BlockThemePickerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(BlockThemes.current(context).id) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        EditorTopBar(title = "Block screen", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            item {
                Text(
                    "Pick how the block screen looks. It shows the same things either way — " +
                        "your minutes reclaimed, a quote and the app you tried to open.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
            items(BlockThemes.OPTIONS.size) { i ->
                val option = BlockThemes.OPTIONS[i]
                ThemeRow(
                    option = option,
                    selected = option.id == selected,
                    onClick = {
                        SettingsStore.setBlockTheme(context, option.id)
                        selected = option.id
                    },
                )
            }
            item {
                Text(
                    "The next block screen you see will use it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun ThemeRow(
    option: BlockThemes.BlockTheme,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 14.dp)
            .clip(AppShapes.card)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) AppGradients.accent
                else SolidColor(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                AppShapes.card,
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThemePreview(option)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                option.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                option.blurb,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(24.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White,
                    modifier = Modifier.size(15.dp))
            }
        }
    }
}

/**
 * A miniature of the block screen, drawn from the theme's **own** colour values rather than from
 * a shipped screenshot. A picture would be a second copy of the design: retune a theme and the
 * preview would keep advertising the old one. This can only ever show what the theme actually is.
 */
@Composable
private fun ThemePreview(option: BlockThemes.BlockTheme) {
    val shape = RoundedCornerShape(10.dp)
    // Mirrors the real backdrops: Aurora is the accent gradient, the rest are flat fills close
    // enough at this size (the real Midnight/Paper glows are invisible in a 54dp thumbnail).
    val background = if (option.id == "aurora") AppGradients.accent
    else SolidColor(Color(option.backdropSolid()))
    Column(
        Modifier.size(width = 54.dp, height = 78.dp).clip(shape)
            .background(background)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), shape)
            .padding(7.dp),
    ) {
        // badge + kicker
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(Color(option.badge)))
            Spacer(Modifier.width(3.dp))
            Bar(width = 18.dp, height = 3.dp, color = Color(option.secondaryText))
        }
        Spacer(Modifier.height(6.dp))
        // the big "minutes reclaimed" numeral
        Bar(width = 26.dp, height = 13.dp, color = Color(option.primaryText))
        Spacer(Modifier.height(3.dp))
        Bar(width = 15.dp, height = 3.dp, color = Color(option.accent))
        Spacer(Modifier.height(7.dp))
        // the quote
        Bar(width = 40.dp, height = 3.dp, color = Color(option.primaryText).copy(alpha = 0.8f))
        Spacer(Modifier.height(2.dp))
        Bar(width = 30.dp, height = 3.dp, color = Color(option.primaryText).copy(alpha = 0.8f))
        Spacer(Modifier.weight(1f))
        // "Got it"
        Bar(width = 40.dp, height = 8.dp, color = Color(option.button), radius = 4.dp)
    }
}

@Composable
private fun Bar(width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp,
                color: Color, radius: androidx.compose.ui.unit.Dp = 2.dp) {
    Box(Modifier.width(width).height(height).clip(RoundedCornerShape(radius)).background(color))
}
