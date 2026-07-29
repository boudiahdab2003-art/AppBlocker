package com.appblocker

import com.appblocker.data.TypedChallenge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that stands in front of every protection this app can lower.
 *
 * It is the last thing between a bad moment and an unblocked phone, and it lives inside a Compose
 * screen where nothing can be tested — so the three rules that make it work are out here. The
 * failure worth guarding against is not a crash: it is the gate quietly becoming easy (a paste
 * that slips through, a clock that leaves no chance, a paragraph that stops changing) while
 * looking exactly as strict as before.
 */
class TypedChallengeTest {

    // --- the comparison: forgiving about form, strict about content ---

    @Test
    fun `capitals and stray spaces are forgiven`() {
        // Typing forty words against a clock is the friction. Transcription perfection is not, and
        // a gate that refuses a correct answer for an invisible reason just reads as broken.
        assertTrue(TypedChallenge.matches("candlestick riverbank", "Candlestick  riverbank "))
        assertTrue(TypedChallenge.matches("candlestick riverbank", "\ncandlestick\nriverbank"))
    }

    @Test
    fun `a partial answer is not a match`() {
        assertFalse(TypedChallenge.matches("candlestick riverbank", "candlestick"))
        assertFalse(TypedChallenge.matches("candlestick riverbank", "candlestick riverbed"))
    }

    // --- the paste check, which is the part he has now asked for twice ---

    @Test
    fun `a pasted paragraph is refused`() {
        val phrase = TypedChallenge.newPhrase()
        assertTrue(TypedChallenge.isPaste("", phrase))
    }

    @Test
    fun `ordinary typing is not a paste`() {
        assertFalse(TypedChallenge.isPaste("candlestic", "candlestick"))
        // Swipe typing and accepting a correction both commit a whole word in one edit, which is
        // exactly why this is a size check and not a "one character at a time" check.
        assertFalse(TypedChallenge.isPaste("", "mountainside"))
        assertFalse(TypedChallenge.isPaste("riverbank ", "riverbank chandelier"))
    }

    @Test
    fun `deleting is always allowed`() {
        // Clearing the field is not a way round anything, and refusing it would trap someone who
        // wanted to start over.
        assertFalse(TypedChallenge.isPaste(TypedChallenge.newPhrase(), ""))
        assertFalse(TypedChallenge.isPaste("candlestick", "candlestic"))
    }

    // --- the paragraph itself ---

    @Test
    fun `every attempt gets a different paragraph`() {
        // One that repeated could be learnt, and a gate you can type from memory has stopped
        // costing anything — which matters now that running out of time hands you a new one.
        assertNotEquals(TypedChallenge.newPhrase(), TypedChallenge.newPhrase())
    }

    @Test
    fun `the paragraph is the agreed length and uses only known words`() {
        val words = TypedChallenge.newPhrase().split(" ")
        assertEquals(TypedChallenge.WORDS, words.size)
        assertEquals(emptyList<String>(), words.filterNot { it in TypedChallenge.CHALLENGE_WORDS })
    }

    // --- the calibration, which is a pair of numbers that only mean anything together ---

    @Test
    fun `there is enough time to type it, and not enough to wander off`() {
        // The owner asked for "not that easy but also not impossible". Changing either number
        // alone is how this silently becomes pointless or unwinnable, so the ratio is asserted
        // rather than the numbers: seconds per word, kept in a band a phone typist can hit.
        val perWord = TypedChallenge.ATTEMPT_SECONDS.toDouble() / TypedChallenge.WORDS
        assertTrue("$perWord s/word is too tight to type", perWord >= 3.0)
        assertTrue("$perWord s/word is long enough to be no pressure", perWord <= 8.0)
    }
}
