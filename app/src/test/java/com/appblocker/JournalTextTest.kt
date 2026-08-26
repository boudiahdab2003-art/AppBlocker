package com.appblocker

import com.appblocker.ui.plainLength
import com.appblocker.ui.withPrompt
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two bits of the recovery screens that turn a number or a tap into words on a page.
 *
 * Small, but both are the sort of thing that goes wrong invisibly: a prompt chip that eats the
 * sentence somebody was half-way through typing, and a duration that reads "1 days".
 */
class JournalTextTest {

    private val day = 86_400_000L
    private val hour = 3_600_000L

    // ─────────────────────────────────────────────────────── the prompt chips

    @Test
    fun `a prompt on an empty page just starts it`() {
        assertEquals("What happened\n", withPrompt("", "What happened"))
    }

    /** The chip must never overwrite or truncate what is already there — it is an addition to a
     *  page somebody is in the middle of, not a template. */
    @Test
    fun `a prompt is appended under what is already written`() {
        assertEquals(
            "Rough evening.\n\nWhat helped\n",
            withPrompt("Rough evening.", "What helped"),
        )
    }

    /** Tapping a chip twice in a row should not leave a growing pile of blank lines. */
    @Test
    fun `trailing blank lines are absorbed rather than stacked`() {
        assertEquals(
            "What happened\n\nTomorrow\n",
            withPrompt("What happened\n", "Tomorrow"),
        )
    }

    @Test
    fun `a page of only whitespace counts as empty`() {
        assertEquals("What set it off\n", withPrompt("   \n\n  ", "What set it off"))
    }

    // ─────────────────────────────────────────────────────── durations in prose

    @Test
    fun `one of anything is singular`() {
        assertEquals("1 day", plainLength(day, EnglishStrings))
        assertEquals("1 hour", plainLength(hour, EnglishStrings))
        assertEquals("1 minute", plainLength(60_000L, EnglishStrings))
    }

    @Test
    fun `the largest unit that fits is the one used`() {
        assertEquals("12 days", plainLength(12 * day + 5 * hour, EnglishStrings))
        assertEquals("5 hours", plainLength(5 * hour + 30 * 60_000L, EnglishStrings))
        assertEquals("30 minutes", plainLength(30 * 60_000L, EnglishStrings))
    }

    /** A run of seconds still has to read as something. "0 minutes" would be the app telling
     *  somebody their effort rounds to nothing. */
    @Test
    fun `a run of seconds is described, not rounded to zero`() {
        assertEquals("less than a minute", plainLength(9_000L, EnglishStrings))
        assertEquals("less than a minute", plainLength(0L, EnglishStrings))
    }
}
