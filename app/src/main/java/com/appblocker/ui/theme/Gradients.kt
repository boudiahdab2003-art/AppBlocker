package com.appblocker.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared gradients/brushes that give the app a richer, more premium feel than flat
 * solid colors: a deep indigo→black screen backdrop, a blue→violet accent for CTAs and
 * logos, and a luminous chart fill.
 */
object AppGradients {
    val AccentStart = Color(0xFF2E7BFF)
    val AccentEnd = Color(0xFF7C5CFF)

    /** Blue→violet accent for buttons, logos, selected states. */
    val accent: Brush = Brush.linearGradient(listOf(AccentStart, AccentEnd))

    /** Vertical variant (for tiles / vertical fills). */
    val accentVertical: Brush = Brush.verticalGradient(listOf(AccentStart, AccentEnd))

    /** Full-screen backdrop: a faint indigo glow at the top fading to near-black. */
    val background: Brush = Brush.verticalGradient(
        0.0f to Color(0xFF141B3D),
        0.35f to Color(0xFF0B1020),
        1.0f to Color(0xFF06080F),
    )

    /** Light-mode backdrop: a soft blue-grey wash, mirroring the dark gradient's subtlety. */
    val backgroundLight: Brush = Brush.verticalGradient(
        0.0f to Color(0xFFF4F7FC),
        0.35f to Color(0xFFEDF1F8),
        1.0f to Color(0xFFE6ECF5),
    )

    /** Luminous cyan→blue→violet fill for chart bars. */
    val chartBar: Brush = Brush.verticalGradient(
        listOf(Color(0xFF5EE7FF), Color(0xFF3B82F6), Color(0xFF7C5CFF)),
    )

    /** Soft accent halo for glows behind logos / orbs. */
    fun glow(alpha: Float = 0.30f): Brush = Brush.radialGradient(
        listOf(AccentStart.copy(alpha = alpha), Color.Transparent),
    )
}

/** The full-screen backdrop for the current theme (dark indigo→black, or the light wash). */
@Composable
fun appBackground(): Brush =
    if (LocalAppDark.current) AppGradients.background else AppGradients.backgroundLight

/** Lifts a card/button off the flat backdrop with a soft colored shadow (API 28+). */
fun Modifier.softGlow(
    shape: Shape,
    glow: Color = AppGradients.AccentStart,
    elevation: Dp = 10.dp,
): Modifier = this.shadow(
    elevation = elevation,
    shape = shape,
    clip = false,
    ambientColor = glow,
    spotColor = glow,
)
