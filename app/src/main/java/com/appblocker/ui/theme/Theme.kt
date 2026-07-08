package com.appblocker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DarkColors = darkColorScheme(
    primary = Color(0xFF2D7FF9),        // bright blue accent (AppBlock-style)
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF15233F),
    onPrimaryContainer = Color(0xFFCFE0FF),
    background = Color(0xFF0A0E18),      // near-black navy
    onBackground = Color(0xFFECEFF4),
    surface = Color(0xFF181B22),
    onSurface = Color(0xFFECEFF4),
    surfaceVariant = Color(0xFF20242E),
    onSurfaceVariant = Color(0xFFB9C2D0),  // brighter secondary text for clarity
    outline = Color(0xFF2A2F3A),
)

// Same brand blue, light surfaces. Keeps the accent gradient (Gradients.kt) identical, so
// white-on-accent content stays correct; only the plain surfaces/text flip.
private val LightColors = lightColorScheme(
    primary = Color(0xFF2D7FF9),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF0A2A5E),
    background = Color(0xFFF5F7FB),
    onBackground = Color(0xFF11151D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF11151D),
    surfaceVariant = Color(0xFFEBEFF6),
    onSurfaceVariant = Color(0xFF515B6B),
    outline = Color(0xFFD4DBE6),
)

/** True when the app is showing its dark theme — read by [appBackground] and any other
 *  code that must pick a light/dark variant of a hardcoded (non-colorScheme) value. */
val LocalAppDark = staticCompositionLocalOf { true }

/** Carries the current theme mode ("system"/"light"/"dark") + a setter down the tree so the
 *  Appearance picker in Profile can change it live. Provided in MainActivity. */
class ThemeController(val mode: String, val onChange: (String) -> Unit)

val LocalThemeController = staticCompositionLocalOf<ThemeController> {
    error("No ThemeController provided")
}

/**
 * Bigger, bolder, clearer type than the Material defaults — headings are Bold, body text
 * is larger and Medium-weight, labels are SemiBold. Every screen reads from these styles.
 */
private val AppTypography = Typography(
    headlineLarge = TextStyle(fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.ExtraBold),
    headlineMedium = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.ExtraBold),
    headlineSmall = TextStyle(fontSize = 27.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium),
    bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun AppBlockerTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppDark provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            content = content,
        )
    }
}
