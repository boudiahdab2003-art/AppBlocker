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
 */
object StoppageHistory {

    /** Both rings at full is 40 lines; a report has other sections to fit around it. */
    internal const val MAX_LINES = 30

    /** Pure, so the order and the cap are tested. Stable: ties keep outages first. */
    internal fun merge(
        outages: List<OutageLog.Episode>,
        switchOffs: List<SwitchOffLog.Episode>,
        max: Int = MAX_LINES,
    ): List<String> =
        (outages.map { it.startedAt to it.render() } + switchOffs.map { it.startedAt to it.render() })
            .sortedByDescending { it.first }
            .take(max)
            .map { it.second }

    fun lines(context: Context): List<String> = merge(
        OutageLog.recentEpisodes(context),
        SwitchOffLog.recentEpisodes(context),
    )
}
