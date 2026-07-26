package com.appblocker.data

import android.content.Context
import com.appblocker.R

/**
 * The *shape* of the block screen — what is on it and where. Chosen separately from
 * [BlockThemes], which decides the colours, so any layout works in any colour.
 *
 * Unlike the colour themes, these genuinely are separate layout files: "app centred with no
 * number" is not a recolouring of "giant number with a quote", and no amount of tinting turns
 * one into the other. What keeps them from drifting is that they all use **the same view ids**,
 * so [com.appblocker.service.BlockOverlay] fills any of them with one code path and simply finds
 * nothing where a layout leaves something out. Every lookup there is null-safe for that reason —
 * a layout omitting the quote is normal, not an error.
 *
 * [showsNumber] and [showsQuote] exist for the Compose fallback screen
 * ([com.appblocker.ui.BlockScreenActivity]), which cannot inflate these XML layouts and so
 * approximates them by hiding the same pieces.
 */
object BlockLayouts {

    data class BlockLayout(
        val id: String,
        val label: String,
        val blurb: String,
        val layoutRes: Int,
        val showsNumber: Boolean,
        val showsQuote: Boolean,
        /** App icon large and centred near the top, rather than in a small row. */
        val appCentred: Boolean = false,
    )

    val OPTIONS = listOf(
        BlockLayout(
            id = "editorial",
            label = "Editorial",
            blurb = "Minutes reclaimed up top, the quote as the hero, the app in a footer.",
            layoutRes = R.layout.overlay_block,
            showsNumber = true,
            showsQuote = true,
        ),
        BlockLayout(
            id = "focus",
            label = "Focus",
            blurb = "Just the app, large and centred. No number, no quote — nothing to linger on.",
            layoutRes = R.layout.overlay_block_focus,
            showsNumber = false,
            showsQuote = false,
            appCentred = true,
        ),
        BlockLayout(
            id = "scoreboard",
            label = "Scoreboard",
            blurb = "Today's reclaimed minutes, huge and centred. No quote.",
            layoutRes = R.layout.overlay_block_scoreboard,
            showsNumber = true,
            showsQuote = false,
        ),
        BlockLayout(
            id = "quote",
            label = "Quote",
            blurb = "The line fills the screen; the app shrinks to a line at the top. No number.",
            layoutRes = R.layout.overlay_block_quote,
            showsNumber = false,
            showsQuote = true,
        ),
    )

    /** The chosen layout, falling back to the first if the stored id is unknown. */
    fun current(context: Context): BlockLayout =
        OPTIONS.firstOrNull { it.id == SettingsStore.blockLayout(context) } ?: OPTIONS.first()
}
