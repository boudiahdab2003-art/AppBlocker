package com.appblocker.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

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

    /** Luminous cyan→blue→violet fill for chart bars. */
    val chartBar: Brush = Brush.verticalGradient(
        listOf(Color(0xFF5EE7FF), Color(0xFF3B82F6), Color(0xFF7C5CFF)),
    )

    /** Soft accent halo for glows behind logos / orbs. */
    fun glow(alpha: Float = 0.30f): Brush = Brush.radialGradient(
        listOf(AccentStart.copy(alpha = alpha), Color.Transparent),
    )
}
