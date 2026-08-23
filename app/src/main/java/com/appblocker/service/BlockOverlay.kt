package com.appblocker.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.appblocker.R
import com.appblocker.data.AppIcons
import com.appblocker.data.AttemptCounter
import com.appblocker.data.BlockArrangement
import com.appblocker.data.BlockLayouts
import com.appblocker.data.BlockThemes
import com.appblocker.data.Quotes

/**
 * The full-screen "blocked" cover: a window drawn straight over the offending app by
 * [BlockerAccessibilityService], which is instant, unlike starting an Activity (the Activity
 * in [com.appblocker.ui.BlockScreenActivity] is only the fallback when the overlay can't be
 * drawn).
 *
 * This class owns the window mechanics — inflating, attaching, filling in the content and
 * tearing down — so the service file can stay about deciding *when* to block. What happens
 * when the user presses "Got it" stays in the service (it has to reset the dismiss
 * bookkeeping and send the phone Home), handed in here as `onClose`.
 *
 * The detached view is kept after removal and reused for the next block: every field is
 * rewritten on show, and re-inflating cost a visible beat before the cover landed.
 */
internal class BlockOverlay(private val context: Context) {

    private val windowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
    private val handler = Handler(Looper.getMainLooper())

    // Written on the main thread (all window work is), but read from the service's background
    // scans too — scanShorts asks isShowing/isAppBlock/counterKey to decide whether a Shorts
    // cover is up. Volatile so those reads see the current value rather than a stale one.
    @Volatile private var view: View? = null
    private var preInflated: View? = null
    /** Which layout `view`/`preInflated` was inflated from, so a layout picked in the meantime
     *  isn't ignored in favour of the cached view. 0 = nothing held. */
    private var heldLayoutRes = 0

    /** True while a cover is attached. */
    val isShowing: Boolean get() = view != null

    /** True when the visible cover is a whole-app block (not a web/word/purchase/Shorts cover). */
    @Volatile
    var isAppBlock = false
        private set

    /** The attempt-counter key the visible cover was raised for, or null. */
    @Volatile
    var counterKey: String? = null
        private set

    /**
     * The app the visible cover was raised **over**, or null when nothing is up.
     *
     * Not the same thing as the package passed to [show]: that one is set only for whole-app
     * blocks (it is what puts the app's icon and name on the cover), so every page, word, purchase
     * and guard cover used to be owned by nobody. Something that answers a different question would
     * then take it down — see [CoverGate.ownedByScan], which is the rule this field exists for.
     *
     * Written here rather than mirrored in the service for the usual reason: a flag tracking what
     * the overlay already knows is a second source of truth, and every one of those in this file's
     * history has drifted (docs/BLOCKING_INVARIANTS.md, invariant 8).
     */
    @Volatile
    var ownerPkg: String? = null
        private set

    /**
     * Inflates the cover ahead of time (without attaching it) so the first block of a session
     * lands instantly instead of paying for inflation on the hot path.
     */
    fun warmUp(onClose: () -> Unit) {
        if (view == null && preInflated == null) preInflated = newView(onClose)
    }

    /**
     * Draws/updates the cover instantly. Returns false if it can't be drawn.
     *
     * [freshBlock] false means this is the *same* block being redrawn (the app came back
     * because Home never landed), so the quote stays put — re-rolling it would read as a
     * second, separate block.
     */
    fun show(
        packageName: String?,
        title: String,
        message: String,
        counterKey: String,
        isAppBlock: Boolean,
        ownerPkg: String?,
        onClose: () -> Unit,
        iconLoader: (String) -> Bitmap?,
        freshBlock: Boolean = true,
    ): Boolean = try {
        this.counterKey = counterKey
        this.isAppBlock = isAppBlock
        this.ownerPkg = ownerPkg
        // The layout can be changed between blocks, and both `view` and `preInflated` outlive a
        // single block — so a cached view built from the previous choice has to go, or the new
        // one would not appear until the service restarted.
        if (heldLayoutRes != BlockLayouts.current(context).layoutRes) {
            view?.let { runCatching { windowManager.removeView(it) } }
            view = null
            preInflated = null
            heldLayoutRes = 0
        }
        val v = view ?: (preInflated ?: newView(onClose)).also {
            preInflated = null
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )
            windowManager.addView(it, params)
            view = it
        }
        // Re-applied on every show, not just on inflate: the view is cached and reused across
        // blocks (and survives in preInflated), so a theme picked between two blocks would
        // otherwise not appear until the service restarted.
        val theme = BlockScreenRenderer.applyTheme(context, v)
        val arrangement = BlockScreenRenderer.applyArrangement(context, v)
        // Every lookup below is null-safe: a layout that leaves the number or the quote out is a
        // valid choice (see BlockLayouts), not a broken layout.
        v.findViewById<TextView>(R.id.overlay_title)?.text = title
        v.findViewById<TextView>(R.id.overlay_subtitle)?.text = message
        // Masthead: every dodged open counts as ~3 minutes of life back.
        v.findViewById<TextView>(R.id.overlay_stat_number)?.text =
            (AttemptCounter.totalToday(context) * MINUTES_PER_DODGE).toString()
        // Fresh motivation every time a NEW block appears (the view is reused across blocks).
        val quoteView = v.findViewById<TextView>(R.id.overlay_quote)
        if (quoteView != null && (freshBlock || quoteView.text.isNullOrBlank())) {
            val quote = Quotes.random(context)
            quoteView.apply {
                text = quote.text
                // Three things multiply, none of them replace: the quote's own size scales with
                // its length, the layout scales that to its own proportions (Focus wants a
                // quiet line, Editorial a hero), and the owner's Smaller/Larger choice scales
                // that again. So a long quote still shrinks to stay readable whatever the
                // layout and whichever size was picked.
                setTextSize(
                    TypedValue.COMPLEX_UNIT_SP,
                    Quotes.sizeSpFor(quote.text) *
                        BlockLayouts.current(context).quoteScale *
                        arrangement.factorFor(BlockArrangement.Element.QUOTE),
                )
            }
            v.findViewById<TextView>(R.id.overlay_quote_author)?.apply {
                text = "— ${quote.author}"
                // Brand sweep across the author line, but only where the theme asks for one —
                // on the gradient and light backdrops a second gradient fights the first.
                // The shader must be CLEARED otherwise: the view is reused, so a leftover
                // shader would keep painting the old theme's colours over the new one.
                val end = theme.accentGradientEnd
                paint.shader = if (end == null) null else LinearGradient(
                    0f, 0f, paint.measureText(text.toString()).coerceAtLeast(1f), 0f,
                    theme.accent, end, Shader.TileMode.CLAMP,
                )
            }
        }
        val iconView = v.findViewById<ImageView>(R.id.overlay_icon)
        // Our own mark must be the launcher icon the user actually picked (icon switcher),
        // not the hardcoded default that stops matching the moment they change it.
        iconView?.setImageResource(AppIcons.current(context).previewRes)
        if (iconView != null && packageName != null) {
            // The icon can need a PackageManager decode (cache miss) — keep it off the
            // first frame so the cover lands instantly; guard against a torn-down cover.
            handler.post {
                if (view == v) iconLoader(packageName)?.let(iconView::setImageBitmap)
            }
        }
        true
    } catch (e: Exception) {
        Log.w(TAG, "overlay failed, falling back to activity", e)
        false
    }

    /** Detaches the cover (keeping the view for reuse). Safe to call when nothing is up. */
    fun remove() {
        view?.let {
            runCatching { windowManager.removeView(it) }
            preInflated = it
        }
        view = null
        counterKey = null
        ownerPkg = null
        // Must be cleared too: the Strict-guard safety net checks isAppBlock on its own (no
        // isShowing beside it), so a value left over from the last app block made it a no-op
        // and could strand a "Locked during Strict Mode" cover.
        isAppBlock = false
    }

    /** Inflates the cover and wires its Close button (not yet attached). */
    private fun newView(onClose: () -> Unit): View =
        LayoutInflater.from(context).inflate(BlockLayouts.current(context).layoutRes, null).also {
            heldLayoutRes = BlockLayouts.current(context).layoutRes
            it.findViewById<Button>(R.id.overlay_close)?.setOnClickListener { onClose() }
            // Clip the app icon to a circle — the icon-art PNGs are full-bleed squares,
            // and this matches how the icon picker (and most launchers) present icons.
            it.findViewById<ImageView>(R.id.overlay_icon)?.apply {
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
            }
        }

    private companion object {
        const val TAG = "AppBlocker"

        // Average minutes of scrolling one dodged open would have cost — powers the
        // "minutes reclaimed today" masthead on the cover.
        const val MINUTES_PER_DODGE = 3
    }
}
