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
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
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

    /**
     * What is currently painted on the held view, so a repeat block does not repaint what is
     * already there. The view survives between blocks (that is the point of `preInflated`), and
     * so does everything drawn onto it — but every show used to re-run the full paint anyway:
     * six colour lookups, two drawable copies, a text-scale pass, and a synchronous decode of
     * the launcher-icon art, all of it between deciding to block and the first frame.
     *
     * Compared by identity against the view, so a rebuilt view repaints from scratch. The
     * choices themselves are still *read* on every show — a look picked between two blocks has
     * to appear on the next one; reading is cheap, painting is not.
     */
    /** How many more times the pre-draw fit may shrink the quote for the cover now up. Refilled on
     *  every show; a budget rather than a loop so a layout that can never fit cannot spin. */
    private var fitBudget = 0

    private var styledView: View? = null
    private var styledThemeId: String? = null
    private var styledArrangement: BlockArrangement.Arrangement? = null

    /** The view the app-blocker mark is known to be on, and which art it is. Null view means
     *  "something else was drawn over it" — a blocked app's own icon lands there
     *  asynchronously, so after an app block the contents are no longer ours to assume. */
    private var markedView: View? = null
    private var heldMarkRes = 0

    /**
     * Until when (uptime) the visible cover is owed its time on screen, or 0. See [CoverHold].
     * Main thread only, like all window work here.
     */
    private var holdUntil = 0L

    /** The removal a hold postponed. One at most; every show and every real removal cancels it. */
    private val heldRemoval = Runnable { remove(now = true) }

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
        /** How long this cover must stay up once shown — see [CoverHold]. 0 for every ordinary
         *  block, which stays until something takes it down. */
        minVisibleMs: Long = 0L,
    ): Boolean = try {
        // A removal still waiting belongs to the cover this one replaces.
        handler.removeCallbacks(heldRemoval)
        holdUntil = if (minVisibleMs > 0L) SystemClock.uptimeMillis() + minVisibleMs else 0L
        this.counterKey = counterKey
        this.isAppBlock = isAppBlock
        this.ownerPkg = ownerPkg
        // The layout can be changed between blocks, and both `view` and `preInflated` outlive a
        // single block — so a cached view built from the previous choice has to go, or the new
        // one would not appear until the service restarted.
        val layout = BlockLayouts.current(context)
        if (heldLayoutRes != layout.layoutRes) {
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
            // Faded in and out by the window manager itself. A cover that is cut in and cut out
            // again a moment later is a flash, whatever the reason it went up for.
            params.windowAnimations = R.style.BlockCoverWindow
            windowManager.addView(it, params)
            view = it
            // Fits the quote before it is drawn: a frame that would overflow is skipped, the
            // quote steps down, and the next layout is judged again. Registered on every attach,
            // not once at inflation: a listener lives in the window's observer, and the window is
            // new each time this reused view goes up.
            val root = it
            it.viewTreeObserver.addOnPreDrawListener {
                if (fitBudget > 0 && BlockScreenRenderer.shrinkQuoteOnce(root)) {
                    fitBudget--
                    false
                } else {
                    true
                }
            }
            playEntrance(it)
        }
        // Re-applied whenever it could have changed, not just on inflate: the view is cached and
        // reused across blocks (and survives in preInflated), so a theme picked between two blocks
        // would otherwise not appear until the service restarted. But an unchanged look repainted
        // identically is pure delay in front of the cover — see [styledView].
        val theme = BlockThemes.current(context)
        val arrangement = BlockArrangement.load(context)
        if (v !== styledView || theme.id != styledThemeId || arrangement != styledArrangement) {
            BlockScreenRenderer.applyTheme(context, v, theme)
            BlockScreenRenderer.applyArrangement(v, arrangement)
            styledView = v
            styledThemeId = theme.id
            styledArrangement = arrangement
        }
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
                        layout.quoteScale *
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
        // The sizes above are multiplied together and by the phone's font size; whether the result
        // fits is only known once it is measured. See BlockScreenRenderer.shrinkQuoteOnce.
        fitBudget = MAX_FIT_STEPS
        val iconView = v.findViewById<ImageView>(R.id.overlay_icon)
        // Our own mark must be the launcher icon the user actually picked (icon switcher),
        // not the hardcoded default that stops matching the moment they change it.
        //
        // setImageResource decodes the art synchronously, on the way to the first frame. Skipped
        // only when this exact view is known to be showing this exact mark already — which a page
        // or word block re-raised in the same browser always is, and a repeat app block never is,
        // because that app's own icon was drawn over the mark a moment after the last cover.
        val markRes = AppIcons.current(context).previewRes
        val markOnly = packageName == null
        if (iconView != null && !(markOnly && markedView === v && heldMarkRes == markRes)) {
            iconView.setImageResource(markRes)
            heldMarkRes = markRes
            markedView = if (markOnly) v else null
        }
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

    /**
     * Detaches the cover (keeping the view for reuse). Safe to call when nothing is up.
     *
     * A cover still inside its [CoverHold] is taken down when the hold ends instead — unless
     * [now], which is for a person's own tap and for the service going away.
     */
    fun remove(now: Boolean = false) {
        if (view != null) {
            val wait = CoverHold.waitMs(holdUntil, SystemClock.uptimeMillis(), now)
            if (wait > 0L) {
                handler.removeCallbacks(heldRemoval)
                handler.postDelayed(heldRemoval, wait)
                return
            }
        }
        handler.removeCallbacks(heldRemoval)
        holdUntil = 0L
        view?.let {
            settle(it)
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

    /** The layout's content column — the root's one child; the backdrop is the root itself. */
    private fun contentOf(root: View): View? = (root as? ViewGroup)?.getChildAt(0)

    /**
     * The words rise into place over the backdrop. The backdrop itself is not moved or delayed:
     * what is being covered is covered from the first frame.
     */
    private fun playEntrance(root: View) {
        val content = contentOf(root) ?: return
        content.animate().cancel()
        content.alpha = 0f
        content.translationY = ENTRANCE_RISE_DP * root.resources.displayMetrics.density
        content.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(40L)
            .setDuration(260L)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    /** Ends the entrance where it was going, so a reused view never starts half-faded. */
    private fun settle(root: View) {
        val content = contentOf(root) ?: return
        content.animate().cancel()
        content.alpha = 1f
        content.translationY = 0f
    }

    /** Inflates the cover and wires its Close button (not yet attached). */
    private fun newView(onClose: () -> Unit): View =
        LayoutInflater.from(context).inflate(BlockLayouts.current(context).layoutRes, null).also {
            heldLayoutRes = BlockLayouts.current(context).layoutRes
            it.findViewById<Button>(R.id.overlay_close)?.setOnClickListener { onClose() }
            // Lines of even length instead of one long line and an orphaned word.
            it.findViewById<TextView>(R.id.overlay_quote)?.apply {
                breakStrategy = android.text.Layout.BREAK_STRATEGY_BALANCED
                hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            }

            // Clip the app icon to a circle — the icon-art PNGs are full-bleed squares,
            // and this matches how the icon picker (and most launchers) present icons.
            it.findViewById<ImageView>(R.id.overlay_icon)?.apply {
                clipToOutline = true
                foreground = context.getDrawable(R.drawable.overlay_icon_ring)
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

        const val ENTRANCE_RISE_DP = 14f

        /** 0.92^24 is about a seventh: enough to bring any quote down to its floor. */
        const val MAX_FIT_STEPS = 24
    }
}

/**
 * **A notice gets its moment on screen.**
 *
 * Some covers are not a wall but a notice: the off-switch guard, Google's news feed, the Ultra
 * battery saver's ways in. They go up, the phone is sent HOME, and the launcher's own window event
 * took the cover down again a fraction of a second later — too short to read, and cut rather than
 * faded. His words, 9 Nov 2026: "it blocks a real thing … but then it flash in an unprofessional
 * way". So a notice stays [NOTICE_MS] before anything but a tap may remove it, and the window
 * fades. The longest it can stand is unchanged: each of those covers already had a safety net at
 * 1.5 s, which is past the hold and therefore still removes at once.
 */
internal object CoverHold {

    /** Long enough to read a title; shorter than every notice's own safety net. */
    const val NOTICE_MS = 900L

    /**
     * How long a removal asked for at [nowMs] must wait; 0 means remove now. Never longer than
     * [NOTICE_MS], whatever the clocks say: a hold may delay a removal, never prevent one.
     */
    fun waitMs(holdUntilMs: Long, nowMs: Long, force: Boolean): Long =
        if (force || holdUntilMs <= 0L) 0L else (holdUntilMs - nowMs).coerceIn(0L, NOTICE_MS)
}
