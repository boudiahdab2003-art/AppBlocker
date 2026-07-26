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
 * [elements] is what the layout's XML actually holds. The Compose fallback
 * ([com.appblocker.ui.BlockScreenActivity]) cannot inflate these layouts, so it reads this to
 * approximate them; the editor reads it to avoid offering a switch for a piece the current layout
 * does not have.
 */
object BlockLayouts {

    data class BlockLayout(
        val id: String,
        val label: String,
        val blurb: String,
        val layoutRes: Int,
        /** The pieces this layout's XML actually contains. One declaration, used both to filter
         *  the editor's Pieces list and by the Compose fallback — previously two booleans that
         *  described the same thing and could disagree with the XML. */
        val elements: Set<BlockArrangement.Element>,
        /** App icon large and centred near the top, rather than in a small row. */
        val appCentred: Boolean = false,
        /**
         * Multiplier on the quote's length-based size ([Quotes.sizeSpFor]), for layouts where the
         * hero sizing would be wrong.
         *
         * A multiplier rather than an absolute size, for the same reason [BlockArrangement.Size]
         * is: a long quote must still shrink itself to stay readable, so this scales that
         * decision instead of overriding it. Needed because the quote is the one piece whose size
         * is computed in code and therefore ignores what the XML declares — every other piece
         * gets its size straight from the layout.
         */
        val quoteScale: Float = 1f,
    )

    val OPTIONS = listOf(
        BlockLayout(
            id = "editorial",
            label = "Editorial",
            blurb = "Minutes reclaimed up top, the quote as the hero, the app in a footer.",
            layoutRes = R.layout.overlay_block,
            elements = setOf(
                BlockArrangement.Element.KICKER, BlockArrangement.Element.NUMBER,
                BlockArrangement.Element.QUOTE, BlockArrangement.Element.APP,
            ),
        ),
        BlockLayout(
            id = "focus",
            label = "Focus",
            blurb = "Just the app, large and centred. No number, and the quote kept small.",
            layoutRes = R.layout.overlay_block_focus,
            elements = setOf(
                BlockArrangement.Element.KICKER, BlockArrangement.Element.QUOTE,
                BlockArrangement.Element.APP,
            ),
            appCentred = true,
            // Well under the others: this layout stacks a 96dp icon, a 32sp app name and the
            // kicker with no scroll view, so a hero-sized quote both fights the design and is the
            // thing most likely to push "Got it" off a short screen. Switch the quote off in
            // Pieces for the bare Focus this layout originally shipped as.
            quoteScale = 0.6f,
        ),
        BlockLayout(
            id = "scoreboard",
            label = "Scoreboard",
            blurb = "Today's reclaimed minutes, huge and centred. No quote.",
            layoutRes = R.layout.overlay_block_scoreboard,
            elements = setOf(
                BlockArrangement.Element.KICKER, BlockArrangement.Element.NUMBER,
                BlockArrangement.Element.APP,
            ),
        ),
        BlockLayout(
            id = "quote",
            label = "Quote",
            blurb = "The line fills the screen; the app shrinks to a line at the top. No number.",
            layoutRes = R.layout.overlay_block_quote,
            elements = setOf(BlockArrangement.Element.QUOTE, BlockArrangement.Element.APP),
        ),
    )

    /** The layout with this id, falling back to the first if the id is unknown. */
    fun byId(id: String?): BlockLayout = OPTIONS.firstOrNull { it.id == id } ?: OPTIONS.first()

    /** The chosen layout, falling back to the first if the stored id is unknown. */
    fun current(context: Context): BlockLayout = byId(SettingsStore.blockLayout(context))
}
