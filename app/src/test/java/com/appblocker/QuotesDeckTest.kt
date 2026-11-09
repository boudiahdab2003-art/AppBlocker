package com.appblocker

import com.appblocker.data.Quotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Every quote is shown once before any repeats, and a round never opens on the last one's close. */
class QuotesDeckTest {

    @Test fun everyLineIsShownOnceBeforeAnyRepeats() {
        val round = (1..76).map { Quotes.draw(76) }
        assertEquals((0 until 76).toSet(), round.toSet())
    }

    @Test fun aNewRoundNeverOpensWithTheLineJustShown() {
        repeat(200) {
            val last = (1..5).map { Quotes.draw(5) }.last()
            // The deck is empty now; the next draw opens a new round.
            val first = Quotes.draw(5)
            assertNotEquals(last, first)
            repeat(4) { Quotes.draw(5) }
        }
    }

    @Test fun anEmptyListDrawsNothing() {
        assertEquals(-1, Quotes.draw(0))
    }
}
