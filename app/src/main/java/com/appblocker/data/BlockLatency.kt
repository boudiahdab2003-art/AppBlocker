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

    /**
     * Which pipeline raised the cover — and the reason this histogram cannot be read without it.
     *
     * ⚠️ **The two are not comparable, and the difference is deliberate waiting rather than
     * speed.** [INSTANT] is decided in the same turn of the main thread as the event that caused
     * it. [SETTLED] is the debounced page scan, which on purpose does nothing at all for 250ms —
     * and up to ~950ms across a burst — so that the page has stopped changing before it is read.
     * The stopwatch starts at the event, so that wait is *inside* every settled measurement:
     * **a settled cover can never reach the fastest two buckets, however fast the code is.**
     *
     * That was already written down here — "the page scan's own debounce caps at 700ms, so a block
     * under a quarter of a second came from the undebounced address-bar path" — and the conclusion
     * was never drawn. One blended percentage over both paths does not measure speed, it measures
     * **which paths the owner happened to use**: a day of more browsing lowers it with nothing in
     * the code having changed, and that is exactly what was read as a slide on 6 Sep 2026 and
     * reported to him as blocking getting slower. It was not.
     *
     * Splitting the counters is not a new instrument. It is this one saying which question it is
     * answering, so that "is our code slow" can be asked of the only path that can answer it.
     */
    enum class Path {
        /** App blocks and the address-bar scan: no debounce, decided in the event's own turn. */
        INSTANT,

        /** The debounced page scan: carries 250–950ms of deliberate settle inside its number. */
        SETTLED,
    }

    /**
     * When the event that led to a cover arrived, **and which pipeline carried it**.
     *
     * ⚠️ **One value rather than two parameters, so a duration cannot be recorded without naming
     * its path.** A `path` argument defaulting to [Path.INSTANT] would have been smaller and would
     * have been wrong in the way this codebase keeps being wrong: the next debounced caller
     * forgets it, its settle time is filed as instant, and the metric quietly goes back to being
     * a blend — with nothing failing. Here there is nothing to forget; `null` means "no meaningful
     * start", which is the honest answer for the re-check tick, the guard and the blind fallback.
     *
     * @param at monotonic ([android.os.SystemClock.elapsedRealtime], invariant 9).
     */
    data class Start(val at: Long, val path: Path)

    /**
     * How many covers a path needs before its share is allowed to be a verdict.
     *
     * A percentage over a handful of covers is noise wearing a number's clothes: at n=18 the
     * difference between 66% and 77% is one cover either way and sits well inside the interval.
     * Below this the fact is still reported — he should see it — but it is not called a fault.
     */
    const val MIN_FOR_VERDICT = 20

    /** One bucket's today/total pair. */
    data class Count(val today: Int, val total: Int)

    /**
     * Records one cover, having taken [ms] to appear by way of [path]. Never throws: this runs on
     * the way out of raising a block, and an instrument must not be able to break what it measures.
     *
     * Writes the combined counters *and* the path's own. The combined pair is left exactly as it
     * was so the lifetime history — every cover recorded since v1.140 — keeps its meaning and the
     * report's `speedBuckets` still describes what the owner actually waited for. The per-path
     * counters start empty and are the only ones a verdict may be taken from.
     */
    fun record(context: Context, ms: Long, path: Path) {
        runCatching {
            val bucket = bucketFor(ms)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val today = todayStamp()
            val edit = prefs.edit()
            listOf("", "${path.name.lowercase()}_").forEach { scope ->
                val storedDay = prefs.getInt("${scope}day_$bucket", -1)
                edit.putInt("${scope}total_$bucket", prefs.getInt("${scope}total_$bucket", 0) + 1)
                    .putInt(
                        "${scope}today_$bucket",
                        if (storedDay == today) prefs.getInt("${scope}today_$bucket", 0) + 1 else 1,
                    )
                    .putInt("${scope}day_$bucket", today)
            }
            edit.apply()
        }
    }

    /** One bucket's counts, over every path — what the owner actually waited for. */
    fun get(context: Context, bucket: Int): Count = get(context, bucket, scope = "")

    /** One bucket's counts for a single [path]. */
    fun get(context: Context, bucket: Int, path: Path): Count =
        get(context, bucket, "${path.name.lowercase()}_")

    private fun get(context: Context, bucket: Int, scope: String): Count {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today =
            if (prefs.getInt("${scope}day_$bucket", -1) == todayStamp()) {
                prefs.getInt("${scope}today_$bucket", 0)
            } else {
                0
            }
        return Count(today, prefs.getInt("${scope}total_$bucket", 0))
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
    fun quickShare(context: Context): Int? = share { get(context, it).total }

    /**
     * The same share over **today's** covers only, or null when there are none yet.
     *
     * A lifetime percentage over a hundred blocks moves by one point when a whole day goes badly,
     * so a real slide reads as noise: 82% -> 79% -> 78% across 5-6 Sep 2026 was roughly half of
     * the recent covers being slow, and the lifetime figure hid it until it crossed a threshold.
     * The per-bucket `today` counts already exist beside the totals, so this is a second reading
     * of storage the recorder is keeping either way — no new writes, and nothing about how the
     * blocker scans changes.
     */
    fun quickShareToday(context: Context): Int? = share { get(context, it).today }

    /** The share of one [path]'s covers that landed in under half a second, or null when it has
     *  recorded none. This is the only share a verdict may be taken from — see [Path]. */
    fun quickShare(context: Context, path: Path): Int? = share { get(context, it, path).total }

    /** How many covers [path] has recorded, for [MIN_FOR_VERDICT]. */
    fun measured(context: Context, path: Path): Int =
        (0 until SIZE).sumOf { get(context, it, path).total }

    private inline fun share(count: (Int) -> Int): Int? = sharePercent((0 until SIZE).map(count))

    /**
     * The arithmetic behind every percentage this object reports, pulled out so a JVM test can
     * reach it — the storage around it cannot be.
     *
     * ⚠️ **Worth pinning because the derivation is where a wrong reading gets its authority.**
     * "Quick" is the first two buckets, i.e. under half a second, and integer division rounds
     * down. Over a small [counts] total that is a very coarse number: at eighteen covers one of
     * them is five and a half points, which is how a difference well inside the noise came to be
     * quoted as a slide. Callers that turn a share into a verdict must check the total too, and
     * [MIN_FOR_VERDICT] is the line.
     */
    fun sharePercent(counts: List<Int>): Int? {
        val all = counts.sum()
        if (all == 0) return null
        val quick = counts.take(2).sum()
        return quick * 100 / all
    }
}
