package com.appblocker.data

import android.content.Context

/**
 * **Every stoppage and every switched-off period, as one newest-first list** — the "Every stoppage
 * on this install" section of a report.
 *
 * Two logs, one list, because the question the list answers is a question about time: do these
 * cluster after restarts, at one hour of the night, after updates? A switched-off period in a
 * separate section would put the answer to that question in two places, and the pattern would
 * only be visible to someone who merged them by hand.
 *
 * The totals stay apart ([OutageLog.totals], [SwitchOffLog.totals]); only the lines are merged,
 * and each line names its own kind.
 *
 * **Android's own record of each death rides in the same order** ([ProcessExits], invariant 72), as
 * `EXITED` lines read when the report is built. A death beside the stoppage it began is the one
 * thing that stoppage's line cannot say. They are not stoppages and do not count against
 * [MAX_LINES], so they can never push one out.
 */
object StoppageHistory {

    /** Both rings at full is 40 lines; a report has other sections to fit around it. */
    internal const val MAX_LINES = 30

    /** Pure, so the order and the cap are tested. Stable: ties keep outages first, then stoppages
     *  before deaths. */
    internal fun merge(
        outages: List<OutageLog.Episode>,
        switchOffs: List<SwitchOffLog.Episode>,
        exits: List<ProcessExits.Exit> = emptyList(),
        max: Int = MAX_LINES,
    ): List<String> {
        val stoppages =
            (outages.map { it.startedAt to it.render() } + switchOffs.map { it.startedAt to it.render() })
                .sortedByDescending { it.first }
                .take(max)
        return (stoppages + exits.map { it.at to it.render() })
            .sortedByDescending { it.first }
            .map { it.second }
    }

    fun lines(context: Context): List<String> = merge(
        OutageLog.recentEpisodes(context),
        SwitchOffLog.recentEpisodes(context),
        ProcessExits.read(context).orEmpty(),
    )

    /**
     * `Sun 21:40`, or `?` when the stamp was never recorded — **the one format every line in the
     * list uses**, so lines of different kinds read side by side. Never a date-with-year: the day of
     * the week and the hour are the pattern, and the rest is noise in a fixed-width line. Local time
     * on purpose: the phone's own evening is the thing being asked about.
     */
    internal fun label(at: Long): String =
        if (at <= 0L) "?" else runCatching {
            java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.US).format(java.util.Date(at))
        }.getOrDefault("?")
}
