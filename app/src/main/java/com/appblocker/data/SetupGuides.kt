package com.appblocker.data

import androidx.annotation.DrawableRes
import com.appblocker.R

/**
 * The pictures the setup wizard shows of Android's own Settings screens.
 *
 * **Why pictures at all.** The hardest part of setting this app up does not happen inside the app.
 * "Grant" throws a first-time user into Android's Settings, into a list of services they have never
 * heard of, and until they come back the app has no way to say anything. The words were already
 * there; what was missing was any way to recognise the screen they had landed on. So each step now
 * shows a real screenshot of the next screen with the row to tap ringed.
 *
 * **Why real screenshots rather than drawings.** The owner's call, and the right one for
 * recognition — a drawing of a settings list still has to be matched against the real thing.
 *
 * **Which brings the one real hazard: a picture of the WRONG phone is worse than no picture.** A
 * Samsung owner shown a stock-Android screen goes looking for a row that is not there and concludes
 * the app is lying. Two things answer that. The set is chosen **per brand** ([forPermission] takes
 * the brand, exactly as [DeviceVendor] does), and every guide carries [takenOn] — a plain sentence
 * naming the phone the pictures came from, shown under them. Honesty about the mismatch costs one
 * line and turns a contradiction into a hint.
 *
 * Only the stock-Android set exists today; brands with no set of their own fall back to it, which
 * is why [forPermission] never returns null for a permission that has any pictures at all. Adding
 * a brand later is one entry in [BY_BRAND] and no change to any caller.
 *
 * Pure data with no Android calls beyond drawable ids, so the mapping is unit-testable — the same
 * reasoning as [DeviceVendor], and the same reason it is safe: getting the brand wrong costs the
 * wrong illustration, never a blocking decision.
 *
 * The images themselves are produced by `tools/setup_shots.py`, which crops a raw screencap and
 * prints the [Ring] literal below it. Do not hand-compute those numbers.
 */
data class Ring(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** One picture: what it shows, what to ring on it, and what to do. */
data class GuideShot(
    @DrawableRes val image: Int,
    val ring: Ring,
    /** Plain language, imperative, and it must stand alone — a picture that fails to load leaves
     *  only this. */
    val caption: String,
)

data class SetupGuide(
    val shots: List<GuideShot>,
    /** Which phone these were photographed on, said out loud under the pictures. */
    val takenOn: String,
)

object SetupGuides {

    private const val STOCK_NOTE =
        "These pictures are from a standard Android phone. Yours may look a little different, " +
            "but the steps are the same."

    private val STOCK_ACCESSIBILITY = SetupGuide(
        takenOn = STOCK_NOTE,
        shots = listOf(
            GuideShot(
                R.drawable.setup_stock_a11y_list,
                Ring(0.037f, 0.367f, 0.796f, 0.920f),
                "Find AppBlocker in the list — it is usually under “Downloaded apps” — and tap it.",
            ),
            GuideShot(
                R.drawable.setup_stock_a11y_switch,
                Ring(0.025f, 0.177f, 0.973f, 0.885f),
                "Turn the switch on.",
            ),
            GuideShot(
                R.drawable.setup_stock_a11y_allow,
                Ring(0.000f, 0.122f, 1.000f, 0.367f),
                "Android asks whether to allow full control. Tap “Allow”. That is what lets " +
                    "AppBlocker see which app is open, so it can block it. Nothing is recorded " +
                    "or sent anywhere.",
            ),
        ),
    )

    private val STOCK_OVERLAY = SetupGuide(
        takenOn = STOCK_NOTE,
        shots = listOf(
            GuideShot(
                R.drawable.setup_stock_overlay_row,
                Ring(0.037f, 0.148f, 0.833f, 0.852f),
                "Find AppBlocker in the list, tap it, and turn the switch on.",
            ),
        ),
    )

    /** Per-permission pictures for one brand. An absent brand falls through to [STOCK]. */
    private val STOCK = mapOf(
        "accessibility" to STOCK_ACCESSIBILITY,
        "overlay" to STOCK_OVERLAY,
    )

    /**
     * Brand-specific sets, keyed by [VendorAdvice.brand].
     *
     * Empty today: the Samsung set needs a Remote Test Lab session to photograph
     * (docs/REMOTE_TEST_LAB.md) and the Xiaomi set needs screenshots from the owner's own phone.
     * Until then every phone gets [STOCK] plus its honest "yours may look different" line, which is
     * the whole reason that line exists.
     */
    private val BY_BRAND: Map<String, Map<String, SetupGuide>> = emptyMap()

    /**
     * Pictures for one permission on one brand, or null when we have none — callers must render
     * without them, since a missing set is the normal case for most phones, not an error.
     */
    fun forPermission(permKey: String, brand: String): SetupGuide? =
        BY_BRAND[brand]?.get(permKey) ?: STOCK[permKey]
}
