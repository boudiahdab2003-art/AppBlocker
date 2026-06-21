package com.appblocker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6BA6FF),
    onPrimary = Color(0xFF06121F),
    background = Color(0xFF0E1726),
    onBackground = Color(0xFFE6EDF5),
    surface = Color(0xFF15203A),
    onSurface = Color(0xFFE6EDF5),
)

@Composable
fun AppBlockerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
