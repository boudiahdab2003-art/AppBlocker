package com.appblocker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF34D399),        // emerald accent (matches the leaf)
    onPrimary = Color(0xFF06231A),
    primaryContainer = Color(0xFF0F5132),
    onPrimaryContainer = Color(0xFFB7F7D8),
    background = Color(0xFF0E1726),      // deep navy
    onBackground = Color(0xFFE6EDF5),
    surface = Color(0xFF15203A),
    onSurface = Color(0xFFE6EDF5),
    surfaceVariant = Color(0xFF1C2A47),
    onSurfaceVariant = Color(0xFFB7C2D4),
    outline = Color(0xFF2C3A57),
)

@Composable
fun AppBlockerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
