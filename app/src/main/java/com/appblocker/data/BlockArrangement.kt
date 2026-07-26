package com.appblocker.data

import android.content.Context
import com.appblocker.R

/**
 * Which pieces of the block screen appear, and in what order — layered *on top of* the chosen
 * layout ([BlockLayouts]) rather than replacing it.
 *
 * The obvious design was to throw the four layouts away and compose the screen from scratch, so
 * that position, size and spacing were all free. That was tried and rejected: the four differ in
 * far more than arrangement (28dp vs 32dp padding, 72dp vs 96dp top inset, 36/40/48/96dp icons,
 * 12/13/15sp kickers, and Focus renders the app name as 32sp centred serif where the others use
 * 17sp sans). Reproducing all of that from a generic engine means a mass of conditional sizing,
 * and the bar was that the four presets keep looking *exactly* as they shipped. So the layouts
 * stay untouched and this only changes four things about them:
 *
 *  - whether an element is shown,
 *  - the order elements sit in,
 *  - whether the stack is left-aligned or centred,
 *  - each element's text size, as a *multiplier* on what its layout declares — so a layout keeps
 *    its own proportions and "Larger" means larger relative to that design, not one fixed size,
 *  - which side the quote's own text sits on.
 *
 * Every layout tags its logical elements with the same container ids (`el_kicker`, `el_number`,
 * `el_quote`, `el_app`), so one code path in [com.appblocker.service.BlockOverlay] applies this to
 * any of them, and simply skips an element the layout doesn't have.
 */
object BlockArrangement {

    /** A movable, hideable piece of the block screen. */
    enum class Element(val id: Int, val label: String, val blurb: String) {
        KICKER(R.id.el_kicker, "\"Blocked\" label", "The small caps line naming what happened."),
        NUMBER(R.id.el_number, "Minutes reclaimed", "Today's running total, and its caption."),
        QUOTE(R.id.el_quote, "Quote", "The motivational line and who said it."),
        APP(R.id.el_app, "App", "The icon and name of what you tried to open."),
    }

    enum class Align { DEFAULT, LEFT, CENTRE }

    /** Text size for one element, as a multiplier on whatever size its layout declares. Kept as
     *  a factor rather than an absolute size so each layout keeps its own proportions — "large"
     *  on Scoreboard's 150sp number and on Editorial's 120sp one should not become the same. */
    enum class Size(val label: String, val factor: Float) {
        SMALLER("Smaller", 0.8f),
        DEFAULT("Default", 1f),
        LARGER("Larger", 1.25f),
    }

    /** Horizontal alignment of the quote text itself (and its author line, which moves with it).
     *  Separate from [Align]: that one moves the *stack* of pieces and only has a visible effect
     *  on pieces narrower than the screen (the kicker, the number) — the quote already spans the
     *  full width, so its own alignment has to be its own setting. */
    enum class QuoteAlign(val label: String) {
        LEFT("Left"),
        CENTRE("Centre"),
        RIGHT("Right"),
    }

    /**
     * [order] is top to bottom and may omit elements the current layout lacks — it is matched by
     * id at apply time. [hidden] is stored rather than a "visible" flag per element so that a
     * *new* element added in a future version defaults to shown rather than silently missing.
     */
    data class Arrangement(
        val order: List<Element> = Element.entries.toList(),
        val hidden: Set<Element> = emptySet(),
        val align: Align = Align.DEFAULT,
        val sizes: Map<Element, Size> = emptyMap(),
        val quoteAlign: QuoteAlign = QuoteAlign.LEFT,
    ) {
        fun isVisible(e: Element) = e !in hidden
        fun sizeOf(e: Element) = sizes[e] ?: Size.DEFAULT
        fun factorFor(e: Element) = sizeOf(e).factor
    }

    /** The default: every element shown, in each layout's own order, alignment untouched. */
    val DEFAULT = Arrangement()

    // --- persistence: "order|hidden|align|sizes|quoteAlign", names not ordinals so reordering an
    // enum in a later version can't silently reinterpret someone's saved arrangement ---

    fun load(context: Context): Arrangement {
        val raw = SettingsStore.blockArrangement(context) ?: return DEFAULT
        return runCatching {
            val parts = raw.split('|')
            val orderPart = parts.getOrElse(0) { "" }
            val hiddenPart = parts.getOrElse(1) { "" }
            val alignPart = parts.getOrElse(2) { "" }
            // Absent in strings written before sizes existed — getOrElse, not [3].
            val sizePart = parts.getOrElse(3) { "" }
            // Absent in strings written before quote alignment existed — getOrElse, not [4].
            val quoteAlignPart = parts.getOrElse(4) { "" }
            val parsed = orderPart.split(',').mapNotNull { name ->
                Element.entries.firstOrNull { it.name == name }
            }
            // Any element missing from a stored order (an older version's string, or a new
            // element added since) is appended rather than dropped — losing one silently would
            // look like a bug in the block screen, not in the settings.
            val order = parsed + Element.entries.filter { it !in parsed }
            Arrangement(
                order = order,
                hidden = hiddenPart.split(',').mapNotNull { name ->
                    Element.entries.firstOrNull { it.name == name }
                }.toSet(),
                align = Align.entries.firstOrNull { it.name == alignPart } ?: Align.DEFAULT,
                sizes = sizePart.split(',').mapNotNull { pair ->
                    val (e, sz) = pair.split(':').let {
                        it.getOrNull(0) to it.getOrNull(1)
                    }
                    val element = Element.entries.firstOrNull { it.name == e } ?: return@mapNotNull null
                    val size = Size.entries.firstOrNull { it.name == sz } ?: return@mapNotNull null
                    element to size
                }.toMap(),
                quoteAlign = QuoteAlign.entries.firstOrNull { it.name == quoteAlignPart }
                    ?: QuoteAlign.LEFT,
            )
        }.getOrDefault(DEFAULT)
    }

    fun save(context: Context, a: Arrangement) = SettingsStore.setBlockArrangement(
        context,
        "${a.order.joinToString(",") { it.name }}|" +
            "${a.hidden.joinToString(",") { it.name }}|${a.align.name}|" +
            "${a.sizes.entries.joinToString(",") { (e, s) -> "${e.name}:${s.name}" }}|" +
            a.quoteAlign.name,
    )

    fun reset(context: Context) = SettingsStore.setBlockArrangement(context, null)
}
