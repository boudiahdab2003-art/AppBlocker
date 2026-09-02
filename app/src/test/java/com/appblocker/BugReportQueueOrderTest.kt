package com.appblocker

import com.appblocker.data.BugReportQueue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **Which end of a backlog gets today's twelve.**
 *
 * [BugReportQueue.MAX_PER_DAY] caps a day's sends, so with anything queued the drain order is not
 * cosmetic: it decides whether the reports that arrive describe this morning or last week. On
 * 2 Sep 2026 twelve week-old reports went out and eleven stayed behind, among them every one
 * covering the stoppage the owner had just watched happen.
 */
class BugReportQueueOrderTest {

    @Test fun aBacklogGoesOutNewestFirst() =
        assertEquals(
            listOf("newest", "middle", "oldest"),
            BugReportQueue.sendOrder(listOf("oldest", "middle", "newest")),
        )

    /** Nothing is dropped and nothing is duplicated — the order is all that changes. */
    @Test fun everyQueuedReportStillGoesOut() {
        val queued = (1..20).map { "r$it" }
        assertEquals(queued.size, BugReportQueue.sendOrder(queued).size)
        assertEquals(queued.toSet(), BugReportQueue.sendOrder(queued).toSet())
    }

    @Test fun anEmptyQueueIsFine() =
        assertEquals(emptyList<String>(), BugReportQueue.sendOrder(emptyList<String>()))
}
