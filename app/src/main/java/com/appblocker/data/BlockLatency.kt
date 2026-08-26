package com.appblocker.data

import android.content.Context

/**
 * **How long the block took**, in buckets — the one thing this app has never measured about
 * itself.
 *
 * Every other instrument here records a *state* or a *count*: whether the service is bound, how
 * many opens were covered, how many decisions were declined. [SilenceLog] came closest and still
 * stopped short — it is handed the exact interval and keeps only whether it crossed a line. So
 * when the owner said the blocking was too slow, there was no number anywhere on the phone that
 * could agree or disagree with him, and `docs/BLOCKING_INVARIANTS.md` has carried "the before/
 * after measurement is still owed" ever since the 25 Aug 2026 relapse.
 *
 * This closes that. It is the same question [SilenceLog] asks — *if this quietly got worse, what
 * number would move?* — pointed at speed instead of silence.
 *
 * **Buckets, not a running average.** An average hides the shape: what he actually feels is the
 * slow ones, and twenty fast blocks would bury one two-second block inside a mean. Buckets keep
 * the tail visible. They are also all a report needs — the fix for a slow block is never "it was
 * 1,340ms rather than 1,120ms", it is "these are landing in the over-a-second row".
 *
 * **Counts only, like everything else that leaves this phone.** No package, no host, no word — a
 * latency carries no content, and this keeps none either.
 */
object BlockLatency {

    private const val PREFS = "block_latency"

    /**
     * Upper edges in millis; anything past the last one lands in the final bucket. Chosen around
     * what the pipeline actually costs rather than round numbers for their own sake: the page
     * scan's own debounce caps at 700ms, so a block under a quarter of a second came from the
     * undebounced address-bar path, and anything past two seconds means something waited that
     * was not meant to.
     */
    private val EDGES = listOf(250L, 500L, 1_000L, 2_000L)

    /** Human labels, one per bucket, in order. Read on the diagnostics screen. */
    val LABELS = listOf(
        "under a quarter second",
        "a quarter to half a second",
        "half a second to a second",
        "one to two seconds",
        "over two seconds",
    )

    /** How many buckets there are. */
    val SIZE = LABELS.size

    /**
     * Which bucket [ms] falls in. Pure, and the whole of the rule — the storage around it is the
     * same prefs pattern as [AttemptCounter] and cannot be reached from a JVM test.
     *
     * A negative interval reads as the fastest bucket rather than throwing: the clock this is
     * measured on is monotonic ([android.os.SystemClock.elapsedRealtime], invariant 9) so it
     * should not happen, but a measurement that crashes the block is worse than a wrong one.
     */
    fun bucketFor(ms: Long): Int {
        EDGES.forEachIndexed { i, edge -> if (ms < edge) return i }
        return EDGES.size
    }

    /** One bucket's today/total pair. */
    data class Count(val today: Int, val total: Int)

    /** Records one cover, having taken [ms] to appear. Never throws: this runs on the way out of
     *  raising a block, and an instrument must not be able to break what it measures. */
    fun record(context: Context, ms: Long) {
        runCatching {
            val bucket = bucketFor(ms)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val today = todayStamp()
            val storedDay = prefs.getInt("day_$bucket", -1)
            prefs.edit()
                .putInt("total_$bucket", prefs.getInt("total_$bucket", 0) + 1)
                .putInt(
                    "today_$bucket",
                    if (storedDay == today) prefs.getInt("today_$bucket", 0) + 1 else 1,
                )
                .putInt("day_$bucket", today)
                .apply()
        }
    }

    /** One bucket's counts. */
    fun get(context: Context, bucket: Int): Count {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today =
            if (prefs.getInt("day_$bucket", -1) == todayStamp()) {
                prefs.getInt("today_$bucket", 0)
            } else {
                0
            }
        return Count(today, prefs.getInt("total_$bucket", 0))
    }

    /** Every bucket with its label, fastest first. */
    fun summary(context: Context): List<Pair<String, Count>> =
        LABELS.indices.map { LABELS[it] to get(context, it) }

    /**
     * The share of blocks that landed in under half a second, as a percentage — or null when
     * there is nothing recorded yet.
     *
     * One number for the top of the card and for a report, because "how fast is it" should not
     * need five rows to answer. Half a second is the line because that is roughly the point the
     * cover stops feeling like a response to what he did and starts feeling like a delay.
     */
    fun quickShare(context: Context): Int? {
        val counts = (0 until SIZE).map { get(context, it).total }
        val all = counts.sum()
        if (all == 0) return null
        val quick = counts[0] + counts[1]
        return quick * 100 / all
    }
}
