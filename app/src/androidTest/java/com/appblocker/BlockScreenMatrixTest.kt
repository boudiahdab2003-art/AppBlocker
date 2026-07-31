package com.appblocker

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appblocker.data.BlockArrangement
import com.appblocker.data.BlockLayouts
import com.appblocker.data.BlockThemes
import com.appblocker.data.Quotes
import com.appblocker.data.SettingsStore
import com.appblocker.service.BlockScreenRenderer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every block-screen look, drawn and measured.
 *
 * The block screen is the one screen in this app the owner cannot escape and cannot report from —
 * it appears at the moment he is being told *no*, over the app he wanted. It is also, since v1.100,
 * the most configurable surface here: four layouts × four colour themes × per-piece visibility,
 * order, alignment and five size steps. That is a large space verified until now only by looking at
 * a few of its points.
 *
 * The specific fear is written down in `BlockLayouts` itself, about the Focus layout: a hero-sized
 * quote is "the thing most likely to push 'Got it' off a short screen". None of these layouts has a
 * ScrollView, so a piece that grows takes its room from whatever is below it — and what is below is
 * the only control on the screen. A block screen with no way out is not a strict app, it is a
 * bricked phone.
 *
 * So the assertion is the same for every combination: **the way out is on screen.** Visible, a real
 * size, and its bottom edge inside the display.
 */
@RunWith(AndroidJUnit4::class)
class BlockScreenMatrixTest {

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    // The owner's own settings live in these prefs on the test device; put them back afterwards.
    private var savedTheme: String? = null
    private var savedArrangement: String? = null

    @Before
    fun rememberOwnersChoices() {
        savedTheme = SettingsStore.blockTheme(context)
        savedArrangement = SettingsStore.blockArrangement(context)
        missingWayOut.clear()
    }

    @After
    fun restoreOwnersChoices() {
        savedTheme?.let { SettingsStore.setBlockTheme(context, it) }
        SettingsStore.setBlockArrangement(context, savedArrangement)
    }

    /**
     * A tall phone and a short one, in pixels rather than dp so the case does not quietly change
     * with the density of whatever device runs the suite. The short one is the case the layouts
     * were never designed against — a small phone, or a large one in a split-screen window.
     */
    private val screens = listOf(
        "tall 1080x2340" to (1080 to 2340),
        "short 1080x1600" to (1080 to 1600),
    )

    /**
     * Inflates a layout, paints the current theme and arrangement onto it exactly as the real
     * cover does, and lays it out at a fixed size.
     *
     * Uses the real [BlockScreenRenderer] rather than a copy for the same reason the in-app preview
     * does: a second implementation of "what the block screen looks like" would drift from the
     * first, which is the shape behind most of this app's past bugs.
     */
    private fun render(layout: BlockLayouts.BlockLayout, widthPx: Int, heightPx: Int): View {
        lateinit var root: View
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val v = LayoutInflater.from(context).inflate(layout.layoutRes, null)
            fillWithRealContent(v, layout)
            BlockScreenRenderer.applyTheme(context, v)
            val arrangement = BlockScreenRenderer.applyArrangement(context, v)
            sizeTheQuoteAsTheCoverDoes(v, layout, arrangement)
            v.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
            )
            v.layout(0, 0, v.measuredWidth, v.measuredHeight)
            root = v
        }
        return root
    }

    /**
     * Puts the app's real content into the layout.
     *
     * The first version of this test inflated the layouts and measured them **empty**, which made
     * every pass meaningless: a TextView with no text is one blank line however large its font, so
     * "every piece at Huge fits" was really "nothing was there to not fit". It still caught a
     * defect, which says how much room there is to lose. This file's own warning about diagnostics
     * that fail silently applies to its tests too.
     *
     * The quote is the longest the app actually ships ([Quotes.longest]) because that is the piece
     * that wraps, and wrapping is what pushes the button off the bottom.
     */
    private fun fillWithRealContent(v: View, layout: BlockLayouts.BlockLayout) {
        val quote = Quotes.longest()
        v.findViewById<TextView>(R.id.overlay_title)?.text = "Blocked"
        // A real app name in the real sentence the cover shows.
        v.findViewById<TextView>(R.id.overlay_subtitle)?.text = "YouTube is blocked"
        v.findViewById<TextView>(R.id.overlay_stat_number)?.text = "126"
        v.findViewById<TextView>(R.id.overlay_quote)?.text = quote.text
        v.findViewById<TextView>(R.id.overlay_quote_author)?.text = quote.author
        // The icon is a fixed 96dp in the XML and BlockScreenRenderer scales it; nothing to fill.
    }

    /**
     * The quote's size, worked out the way [com.appblocker.service.BlockOverlay] works it out:
     * its own length-based size, times the layout's proportion, times the owner's size choice.
     *
     * This is the one thing the test computes rather than calls, because that multiplication lives
     * inline in `BlockOverlay.show()` — [BlockScreenRenderer.applyArrangement] deliberately skips
     * the quote to avoid applying the factor twice. **That is a second source of truth for the
     * quote's size, and it is exactly the drift this codebase keeps being bitten by**; the right
     * fix is to extract that expression so the cover and this test call one function. Until then,
     * this mirror is marked as what it is.
     */
    private fun sizeTheQuoteAsTheCoverDoes(
        v: View,
        layout: BlockLayouts.BlockLayout,
        arrangement: BlockArrangement.Arrangement,
    ) {
        val quoteView = v.findViewById<TextView>(R.id.overlay_quote) ?: return
        quoteView.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            Quotes.sizeSpFor(quoteView.text.toString()) *
                layout.quoteScale *
                arrangement.factorFor(BlockArrangement.Element.QUOTE),
        )
    }

    /** Where a child's bottom edge sits inside [root], which is what "on screen" actually means. */
    private fun bottomWithin(root: View, child: View): Int {
        var y = 0
        var v: View? = child
        while (v != null && v !== root) {
            y += v.top
            v = v.parent as? View
        }
        return y + child.height
    }

    /**
     * Every combination whose way out is missing, collected rather than thrown at.
     *
     * `docs/BLOCKING_INVARIANTS.md` standing guidance: *"When one instance is found, enumerate all
     * of them before fixing"* — the file records three releases spent on one mistake because the
     * first sweep fixed the instance in front of it. A test that stops at the first bad combination
     * would hand back exactly that: one example, no idea whether it is one case or thirty, and no
     * way to tell a fix that worked from a fix that moved the failure one iteration along.
     */
    private val missingWayOut = mutableListOf<String>()

    /** Records — never throws — if the only control on the block screen isn't usable. */
    private fun checkWayOutIsOnScreen(root: View, description: String) {
        val close = root.findViewById<View>(R.id.overlay_close)
        if (close == null) {
            missingWayOut += "$description: no Got it button in the layout at all"
            return
        }
        if (close.visibility != View.VISIBLE) {
            missingWayOut += "$description: Got it button is not visible"
        }
        if (close.height <= 0 || close.width <= 0) {
            missingWayOut += "$description: Got it button measured to nothing"
        }
        val bottom = bottomWithin(root, close)
        if (bottom > root.height) {
            missingWayOut += "$description: Got it button ends ${bottom - root.height}px below " +
                "the bottom of a ${root.height}px screen — there is no way out"
        }
    }

    /** Fails once, listing everything found, so the whole shape of the defect is in one message. */
    private fun assertEveryCombinationHasAWayOut() {
        if (missingWayOut.isEmpty()) return
        throw AssertionError(
            "${missingWayOut.size} block-screen combination(s) leave no way out:\n" +
                missingWayOut.joinToString("\n") { "  - $it" },
        )
    }

    /**
     * The shipping configuration: every layout, every colour, default arrangement. This is what
     * the owner sees, and it must hold on a short screen as well as a tall one.
     */
    @Test
    fun everyLayoutAndThemeKeepsTheWayOutOnScreen() {
        SettingsStore.setBlockArrangement(context, null) // defaults

        BlockLayouts.OPTIONS.forEach { layout ->
            BlockThemes.OPTIONS.forEach { theme ->
                SettingsStore.setBlockTheme(context, theme.id)
                screens.forEach { (screenName, size) ->
                    val root = render(layout, size.first, size.second)
                    checkWayOutIsOnScreen(root, "${layout.id} + ${theme.id} on $screenName")
                }
            }
        }
        assertEveryCombinationHasAWayOut()
    }

    /**
     * The worst arrangement the editor can actually produce: every piece the layout has, all at
     * "Huge". The size steps go to 1.8×, the layouts do not scroll, and nothing in the editor stops
     * this combination being chosen — so either it fits or there is a real defect to fix, and this
     * is how we find out on purpose instead of from a phone that cannot be unblocked.
     */
    @Test
    fun theLargestArrangementStillLeavesTheWayOut() {
        BlockLayouts.OPTIONS.forEach { layout ->
            val everythingHuge = BlockArrangement.Arrangement(
                sizes = layout.elements.associateWith { BlockArrangement.Size.HUGE },
            )
            BlockArrangement.save(context, everythingHuge)

            screens.forEach { (screenName, size) ->
                val root = render(layout, size.first, size.second)
                checkWayOutIsOnScreen(root, "${layout.id} with every piece Huge on $screenName")
            }
        }
        assertEveryCombinationHasAWayOut()
    }

    /**
     * The fix must not have changed how the block screen *looks*.
     *
     * `BlockScreenRenderer` states the constraint the scroller had to respect: "The four layouts
     * are hand-tuned and must keep looking exactly as they shipped." A scroll container is the
     * kind of change that can quietly break that — content pinned to the top instead of centred,
     * a hero that no longer fills the space.
     *
     * `fillViewport` is what preserves it: when the content is shorter than the viewport the
     * scroller stretches its child to exactly the viewport height, so every weight inside still
     * divides the same space it divided before and nothing moves. So the check is precise —
     * **at the default arrangement the content is exactly the viewport height**: not shorter
     * (fillViewport failed, and the layout would have collapsed upward) and not taller (it is
     * scrolling, which is a different screen from the one that shipped).
     */
    @Test
    fun theDefaultLookIsUnchangedByTheScroller() {
        SettingsStore.setBlockArrangement(context, null)
        SettingsStore.setBlockTheme(context, BlockThemes.OPTIONS.first().id)
        val wrong = mutableListOf<String>()

        BlockLayouts.OPTIONS.forEach { layout ->
            val root = render(layout, 1080, 2340)
            val scroller = root.findViewById<ViewGroup>(R.id.overlay_scroll)
            if (scroller == null) {
                wrong += "${layout.id}: no scroller — the way out is not pinned"
                return@forEach
            }
            val content = scroller.getChildAt(0)
            if (content == null) {
                wrong += "${layout.id}: the scroller has nothing in it"
                return@forEach
            }
            if (content.height != scroller.height) {
                wrong += "${layout.id}: content is ${content.height}px in a ${scroller.height}px " +
                    "viewport — " + if (content.height < scroller.height) {
                        "fillViewport did not stretch it, so the pieces have collapsed upward"
                    } else {
                        "it needs scrolling at the DEFAULT size, which is not how it shipped"
                    }
            }
        }

        if (wrong.isNotEmpty()) {
            throw AssertionError(
                "The scroller changed the default look:\n" + wrong.joinToString("\n") { "  - $it" },
            )
        }
    }

    /**
     * Hiding pieces is the other direction, and the one that once returned nothing at all:
     * `applyArrangement`'s early exit used to fall off the end of the function. A layout with
     * everything switched off is legal — it is how the owner gets the bare Focus screen — and it
     * must still be a screen you can leave.
     */
    @Test
    fun hidingEveryPieceStillLeavesTheWayOut() {
        BlockLayouts.OPTIONS.forEach { layout ->
            BlockArrangement.save(
                context,
                BlockArrangement.Arrangement(hidden = layout.elements),
            )

            screens.forEach { (screenName, size) ->
                val root = render(layout, size.first, size.second)
                checkWayOutIsOnScreen(root, "${layout.id} with every piece hidden on $screenName")
            }
        }
        assertEveryCombinationHasAWayOut()
    }
}
