package com.appblocker.data

import android.content.Context
import com.appblocker.R

/**
 * The look of the block screen (Profile ▸ Block screen). Same screen, same information, same
 * behaviour — only the colours and backdrop change.
 *
 * Deliberately **not** four copies of `overlay_block.xml`. Duplicating the layout would create
 * four sources of truth for one screen, and every drift bug in this app's history has that shape:
 * change the wording or add a line and three copies silently keep the old version. Instead there
 * is one layout, and [com.appblocker.service.BlockOverlay] paints these values onto it.
 *
 * Only the backdrop is a real drawable per theme, because those differ structurally (layered
 * radial glows vs a flat fill vs a linear gradient) rather than just in colour. Everything else
 * is a colour applied at show time, including the badge and button, which are tinted rather than
 * duplicated.
 */
object BlockThemes {

    data class BlockTheme(
        val id: String,
        val label: String,
        /** One-line description shown under the name in the picker. */
        val blurb: String,
        val backgroundRes: Int,
        val primaryText: Int,
        val secondaryText: Int,
        /** Quote author, and any small accent text. */
        val accent: Int,
        /** Fill behind the white shield. */
        val badge: Int,
        /** "Got it" — a flat fill; the default theme keeps its gradient drawable instead. */
        val button: Int,
        val buttonText: Int,
        /** True for the one theme that keeps the original gradient button drawable. */
        val gradientButton: Boolean = false,
        /** When set, the quote's author line sweeps [accent] → this instead of sitting flat.
         *  Only the original theme does; on a gradient or a light backdrop a second gradient
         *  fights the first. Data rather than a special case on [id]. */
        val accentGradientEnd: Int? = null,
    )

    private const val WHITE = 0xFFFFFFFF.toInt()

    val OPTIONS = listOf(
        BlockTheme(
            id = "midnight",
            label = "Midnight",
            blurb = "Deep navy with soft blue and violet glows. The original.",
            backgroundRes = R.drawable.overlay_bg,
            primaryText = WHITE,
            secondaryText = 0xFFB9C2D0.toInt(),
            accent = 0xFF7C5CFF.toInt(),
            badge = 0xFF2D7FF9.toInt(),
            button = 0xFF2E7BFF.toInt(),
            buttonText = WHITE,
            gradientButton = true,
            accentGradientEnd = 0xFF7C5CFF.toInt(),
        ),
        BlockTheme(
            id = "aurora",
            label = "Aurora",
            blurb = "The full brand gradient, edge to edge. The boldest of the four.",
            backgroundRes = R.drawable.overlay_bg_aurora,
            primaryText = WHITE,
            secondaryText = 0xFFE2E0FF.toInt(),
            accent = WHITE,
            badge = 0x33FFFFFF, // translucent white — a solid badge disappears on the gradient
            button = WHITE,
            buttonText = 0xFF2A1D6B.toInt(),
        ),
        BlockTheme(
            id = "paper",
            label = "Paper",
            blurb = "Light and quiet. Easiest on the eyes in daylight.",
            backgroundRes = R.drawable.overlay_bg_paper,
            primaryText = 0xFF11151D.toInt(),
            secondaryText = 0xFF515B6B.toInt(),
            accent = 0xFF2D7FF9.toInt(),
            badge = 0xFF2D7FF9.toInt(),
            button = 0xFF11151D.toInt(),
            buttonText = WHITE,
        ),
        BlockTheme(
            id = "ink",
            label = "Ink",
            blurb = "Pure black, no colour at all. Nothing to look at, which is the point.",
            backgroundRes = R.drawable.overlay_bg_ink,
            primaryText = WHITE,
            secondaryText = 0xFF8A8A8A.toInt(),
            accent = 0xFF8A8A8A.toInt(),
            badge = 0xFF2A2A2A.toInt(),
            button = 0xFFFFFFFF.toInt(),
            buttonText = 0xFF000000.toInt(),
        ),
    )

    /** The chosen theme, falling back to the first if the stored id is unknown (e.g. a theme
     *  removed in a later version — never leave the block screen unstyled). */
    fun current(context: Context): BlockTheme =
        OPTIONS.firstOrNull { it.id == SettingsStore.blockTheme(context) } ?: OPTIONS.first()
}

/** The flat backdrop colour for a theme, for the Compose fallback screen and the picker's
 *  thumbnails — the real overlay backdrops are XML drawables it cannot inflate. Midnight keeps
 *  its Compose gradient (see BlockScreenActivity), so only the flat ones need an answer here. */
internal fun BlockThemes.BlockTheme.backdropSolid(): Int = when (id) {
    "paper" -> 0xFFF4F7FC.toInt()
    "ink" -> 0xFF000000.toInt()
    "aurora" -> 0xFF4E6BFF.toInt() // mid-point of the gradient, for anywhere that needs one colour
    else -> 0xFF090B10.toInt()
}
