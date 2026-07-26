package com.appblocker.service

import android.content.Context
import android.view.Gravity
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.appblocker.R
import com.appblocker.data.BlockArrangement
import com.appblocker.data.BlockThemes

/**
 * Paints a chosen look and arrangement onto an inflated block-screen layout.
 *
 * Lifted out of [BlockOverlay] so the settings preview can render with **exactly** this code
 * rather than a Compose imitation of it. An imitation would be a second source of truth for what
 * the block screen looks like, and would eventually disagree with the screen it claims to preview
 * — the same drift that has caused most of this app's bugs. The preview inflates the real layout
 * and calls the real renderer; if they ever differ, it is because the screen changed.
 */
internal object BlockScreenRenderer {

    /**
     * Paints the chosen look (Profile ▸ Block screen) onto the single shared layout, and returns
     * it so the caller can use its accent for the quote line.
     *
     * There is one `overlay_block.xml`, not one per theme, on purpose: four copies of the layout
     * would be four things to keep in step every time the screen changes, which is the drift shape
     * behind most of this app's past bugs. Only the backdrop is a per-theme drawable, because those
     * differ in structure rather than just colour; the badge and button are tinted.
     */
    fun applyTheme(context: Context, v: View): BlockThemes.BlockTheme {
        val t = BlockThemes.current(context)
        v.setBackgroundResource(t.backgroundRes)
        v.findViewById<TextView>(R.id.overlay_title)?.setTextColor(t.secondaryText)
        v.findViewById<TextView>(R.id.overlay_stat_number)?.setTextColor(t.primaryText)
        v.findViewById<TextView>(R.id.overlay_stat_label)?.setTextColor(t.accent)
        v.findViewById<TextView>(R.id.overlay_quote)?.setTextColor(t.primaryText)
        v.findViewById<TextView>(R.id.overlay_quote_author)?.setTextColor(t.accent)
        v.findViewById<TextView>(R.id.overlay_subtitle)?.setTextColor(t.primaryText)
        // mutate() so tinting one theme's drawable can't bleed into the shared constant state
        // (Android caches drawables by resource id across every view that inflates them).
        v.findViewById<View>(R.id.overlay_badge_bg)?.background =
            ContextCompat.getDrawable(context, R.drawable.overlay_badge)?.mutate()?.apply {
                setTint(t.badge)
            }
        v.findViewById<Button>(R.id.overlay_close)?.apply {
            setTextColor(t.buttonText)
            background = ContextCompat.getDrawable(context, R.drawable.overlay_btn)?.mutate()
                ?.apply { if (!t.gradientButton) setTint(t.button) }
        }
        return t
    }

    /**
     * Applies the owner's element order / visibility / alignment (Profile ▸ Block screen) to
     * whichever layout is up.
     *
     * Runs on every show() for the same reason the theme does: the view is cached and reused
     * across blocks, so a change made in between would otherwise not appear until the service
     * restarted. That has now been the trap three times in this class.
     *
     * Deliberately touches only visibility, child order and gravity — never a size, colour or
     * margin. The four layouts are hand-tuned and must keep looking exactly as they shipped; this
     * rearranges them, it does not restyle them.
     */
    fun applyArrangement(context: Context, v: View): BlockArrangement.Arrangement {
        val arrangement = BlockArrangement.load(context)
        // Before the early returns below, so the quote's side is applied on every layout — the
        // ones without a quote simply find no view to align.
        alignQuote(v, arrangement.quoteAlign)
        // The elements this layout actually has, in the owner's order. A layout without a quote
        // simply contributes nothing here.
        val present = arrangement.order.mapNotNull { element ->
            v.findViewById<View>(element.id)?.let { element to it }
        }
        if (present.isEmpty()) return arrangement

        present.forEach { (element, child) ->
            child.visibility = if (arrangement.isVisible(element)) View.VISIBLE else View.GONE
            scaleText(child, arrangement.factorFor(element))
        }

        val stack = present.first().second.parent as? LinearLayout ?: return arrangement
        // Reorder in place. Each element is moved to sit at the index of the earliest slot any
        // element currently occupies, in turn — so the elements shuffle among the positions they
        // already hold and the layout's other children (spacers, the Got it button) never move.
        val slots = present.map { (_, child) -> stack.indexOfChild(child) }.sorted()
        present.forEachIndexed { i, (_, child) ->
            val target = slots[i]
            if (stack.indexOfChild(child) != target) {
                stack.removeView(child)
                stack.addView(child, target)
            }
        }

        when (arrangement.align) {
            BlockArrangement.Align.LEFT -> stack.gravity = Gravity.START
            BlockArrangement.Align.CENTRE -> stack.gravity = Gravity.CENTER_HORIZONTAL
            // Untouched: keep whatever the layout itself declares.
            BlockArrangement.Align.DEFAULT -> Unit
        }
        return arrangement
    }

    /**
     * Puts the quote's text on the chosen side.
     *
     * The stack gravity above cannot do this: the quote TextView is `match_parent` wide, so moving
     * its *parent's* gravity leaves it exactly where it was — only its own gravity moves the text
     * inside it. The author line is the opposite case (`wrap_content`), so it moves via its layout
     * gravity instead; setting text gravity there would do nothing, as the view is already as wide
     * as its text. Both are set on every show for the same reason as everything else here — the
     * view is reused across blocks, so an unset side would keep the previous choice.
     */
    private fun alignQuote(v: View, align: BlockArrangement.QuoteAlign) {
        val gravity = when (align) {
            BlockArrangement.QuoteAlign.LEFT -> Gravity.START
            BlockArrangement.QuoteAlign.CENTRE -> Gravity.CENTER_HORIZONTAL
            BlockArrangement.QuoteAlign.RIGHT -> Gravity.END
            // Untouched: keep whatever the layout itself declares, exactly as Align.DEFAULT does
            // above. This is what lets Focus centre its quote while Editorial keeps it left —
            // writing a gravity here on every show would make the XML's value unreachable.
            BlockArrangement.QuoteAlign.DEFAULT -> return
        }
        v.findViewById<TextView>(R.id.overlay_quote)?.gravity = gravity
        v.findViewById<TextView>(R.id.overlay_quote_author)?.let { author ->
            (author.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.gravity = gravity
                author.layoutParams = it
            }
        }
    }

    /**
     * Scales every text inside [root] to [factor] of the size its layout declared.
     *
     * The base size is captured on the first pass and reused forever after, because the cover's
     * view is **reused across blocks**: scaling by reading the current size would multiply again
     * on every single block until the text filled the screen. Always base × factor, never
     * current × factor.
     *
     * The quote itself is skipped — its size already belongs to the quote-length logic in
     * [BlockOverlay] (a short line goes huge, a long one stays readable), so the factor is applied
     * there instead. Scaling it here as well would apply the factor twice.
     */
    private fun scaleText(root: View, factor: Float) {
        when (root) {
            is TextView -> {
                if (root.id == R.id.overlay_quote) return
                val density = root.resources.displayMetrics.scaledDensity
                val base = root.getTag(R.id.base_text_size_sp) as? Float
                    ?: (root.textSize / density).also { root.setTag(R.id.base_text_size_sp, it) }
                root.setTextSize(TypedValue.COMPLEX_UNIT_SP, base * factor)
            }
            // Icons scale too. Without this the Size buttons looked broken on Focus, whose app
            // piece is a 96dp icon with a line of text under it: the text moved, the picture
            // beside it did not, and the piece as a whole barely changed. Same base-capture rule
            // as the text above, and for the same reason — the cover's view is reused across
            // blocks, so scaling from the CURRENT size would compound every time.
            is ImageView -> {
                val lp = root.layoutParams ?: return
                val base = root.getTag(R.id.base_icon_size_px) as? IntArray
                    ?: intArrayOf(lp.width, lp.height)
                        .also { root.setTag(R.id.base_icon_size_px, it) }
                // Only a fixed size scales. wrap_content / match_parent are the layout deciding
                // from its context, and overwriting them with a number would break that.
                if (base[0] > 0 && base[1] > 0) {
                    lp.width = (base[0] * factor).toInt()
                    lp.height = (base[1] * factor).toInt()
                    root.layoutParams = lp
                }
            }
            is ViewGroup -> for (i in 0 until root.childCount) scaleText(root.getChildAt(i), factor)
        }
    }

}
