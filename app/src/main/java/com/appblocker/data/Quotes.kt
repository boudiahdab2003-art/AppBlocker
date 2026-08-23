package com.appblocker.data

import android.content.Context
import com.appblocker.R

/** A short motivational line shown on the block screen. */
data class Quote(val text: String, val author: String)

/**
 * Bundled motivational quotes for the block screen — the moment of temptation.
 * Short (1–2 lines), about attention, time, discipline and beating distraction.
 * A fresh one is picked every time the block screen appears.
 *
 * Quality bar: real quotes carry verified attributions (a famous line whose true
 * source is unclear is signed "Unknown", never a borrowed famous name); the app's
 * own lines are signed "Your coach" and must earn their place — no poster clichés.
 */
object Quotes {

    private var lastIndex = -1

    /**
     * Every quote, in the app's language.
     *
     * **Two parallel arrays, zipped here.** Android resources have no structured type, so the text
     * and the attribution are separate `<string-array>`s — and a translation that drops one line
     * from either would silently shift every attribution onto the wrong quote. Zipping to the
     * shorter of the two makes that a missing quote rather than fifty-eight wrong ones, and
     * `StringResourcesTest` fails the build if the lengths ever disagree.
     */
    fun all(context: Context): List<Quote> {
        val res = AppLocale.wrap(context).resources
        val texts = res.getStringArray(R.array.quote_texts)
        val authors = res.getStringArray(R.array.quote_authors)
        return texts.zip(authors) { t, a -> Quote(t, a) }
    }

    /** A random quote, never the same one twice in a row. */
    fun random(context: Context): Quote {
        val all = all(context)
        if (all.isEmpty()) return Quote("", "")
        var i = all.indices.random()
        if (i == lastIndex && all.size > 1) i = (i + 1) % all.size
        lastIndex = i
        return all[i]
    }

    /**
     * The longest line in the list — the block screen's genuine worst case for height.
     *
     * Exists for `BlockScreenMatrixTest`, which measures whether "Got it" is still on screen. That
     * test is only worth anything if it uses text the app can really produce: an invented string
     * would report failures that cannot happen, and an empty one reports success that isn't real.
     * Reading it from the resources also means adding a longer quote re-runs the worst case
     * automatically — **and it now does that per language**, so the Arabic worst case is measured
     * as the Arabic worst case rather than as a translation of the English one.
     */
    internal fun longest(context: Context): Quote {
        val all = all(context)
        return all.maxByOrNull { it.text.length } ?: Quote("", "")
    }

    /** Poster-style text size: short quotes go huge, long ones stay readable. */
    fun sizeSpFor(text: String): Float = when {
        text.length <= 60 -> 38f
        text.length <= 100 -> 32f
        else -> 28f
    }
}
