package com.appblocker

import com.appblocker.data.DisplayName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The name the app knows you by, and specifically **the empty one**.
 *
 * Until v1.121 the stored display name defaulted to the original owner's own name, so every caller
 * could assume there was a real name to interpolate. Making the app usable by anybody meant
 * blanking that default — and the moment "" became an ordinary value on an ordinary phone, the
 * sentences built from it stopped being English. A bare `substringBefore(' ')` on an empty name
 * produced the AI Coach's opening line as **"Hi !"**, its system prompt as **"a screen-time app on
 * the phone of ."**, and its tip prompt as **"You are 's personal screen-time coach"** — the last
 * of which invites the model to invent a name for someone who deliberately declined to give one.
 *
 * None of that is reachable from a UI test, and all of it is reachable from here, which is why the
 * logic lives in a Context-free object at all (the same reason as `service/BlockDecision.kt`).
 */
class DisplayNameTest {

    // ---- The empty name: every accessor has to stay a sentence ---------------------------

    @Test fun `an unset name displays as You`() {
        assertEquals("You", DisplayName.display(""))
    }

    @Test fun `whitespace only counts as unset`() {
        assertEquals("You", DisplayName.display("   "))
        assertFalse(DisplayName.isSet("  \t "))
    }

    @Test fun `an unset first name is addressable, not empty`() {
        assertEquals("you", DisplayName.first(""))
    }

    @Test fun `an unset name has a third-person noun for prompts`() {
        assertEquals("the user", DisplayName.possessiveSubject(""))
    }

    @Test fun `an unset name still has an avatar to draw`() {
        assertEquals("?", DisplayName.initials(""))
    }

    // ---- A real name is passed through unchanged ------------------------------------------

    @Test fun `a set name is used as given`() {
        assertEquals("Sam", DisplayName.display("Sam"))
        assertEquals("Sam", DisplayName.first("Sam Carter"))
        assertEquals("Sam Carter", DisplayName.possessiveSubject("Sam Carter"))
        assertTrue(DisplayName.isSet("Sam"))
    }

    @Test fun `initials take the first and last word`() {
        assertEquals("S", DisplayName.initials("Sam"))
        assertEquals("SC", DisplayName.initials("Sam Carter"))
        assertEquals("SW", DisplayName.initials("Sam John Wright"))
    }

    @Test fun `initials are upper-cased whatever was typed`() {
        assertEquals("SC", DisplayName.initials("sam carter"))
    }

    // ---- Sanitizing, which is what the stored value actually goes through -------------------

    @Test fun `surrounding space is dropped`() {
        assertEquals("Sam", DisplayName.sanitize("  Sam  "))
        // And the display path uses the sanitized form, not the raw one.
        assertEquals("Sam", DisplayName.display("  Sam  "))
    }

    @Test fun `runs of whitespace collapse to one space`() {
        assertEquals("Sam Carter", DisplayName.sanitize("Sam    Carter"))
        assertEquals("Sam Carter", DisplayName.sanitize("Sam\t\nCarter"))
        // Otherwise the first name would come back empty from a double-spaced name.
        assertEquals("Sam", DisplayName.first("Sam    Carter"))
    }

    @Test fun `a very long name is capped`() {
        assertEquals(DisplayName.MAX_LENGTH, DisplayName.sanitize("z".repeat(60)).length)
    }

    /** The cap trims trailing space too, so a name cut mid-gap doesn't keep a dangling one. */
    @Test fun `a long multi-word name is capped and left tidy`() {
        val long = "Wolfeschlegelsteinhausenbergerdorff Von Habsburg Of Somewhere Else"
        val cut = DisplayName.sanitize(long)
        assertTrue("Cap not applied: '$cut'", cut.length <= DisplayName.MAX_LENGTH)
        assertEquals(cut, cut.trim())
        assertTrue("Cap should keep the start of the name", long.startsWith(cut))
    }

    /**
     * Cutting at 40 UTF-16 units can land between the halves of a surrogate pair, which renders as
     * a permanent broken box — the damage happens at *write* time, so no display-side fix reaches
     * it. 39 plain characters then an emoji is the exact boundary case.
     */
    @Test fun `the cap never splits a character in half`() {
        val name = "a".repeat(DisplayName.MAX_LENGTH - 1) + "😀" // 39 chars + emoji
        val cut = DisplayName.sanitize(name)
        assertFalse("Sanitize left a lone surrogate half", cut.last().isHighSurrogate())
        assertEquals("a".repeat(DisplayName.MAX_LENGTH - 1), cut)
    }

    @Test fun `initials keep both halves of a non-ascii character`() {
        assertEquals("😀", DisplayName.initials("😀"))
    }
}
