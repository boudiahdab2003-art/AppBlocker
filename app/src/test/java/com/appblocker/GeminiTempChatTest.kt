package com.appblocker

import com.appblocker.service.CoverGate
import com.appblocker.service.GeminiTempChat
import com.appblocker.service.TempChatView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Gemini's temporary chat, blocked on its own — invariant 99, his ask of 31 Oct 2026.
 *
 * The strings below are the ones read off his phone that day over USB (`uiautomator dump`, Gemini
 * in the Google app, Second Space; gemini.google.com in Chrome). What cannot be tested here —
 * whether the Google app still draws that sentence inside the zero-state container, and where BACK
 * lands — is a fact about someone else's app on a real phone; [GeminiTempChat] records what was
 * measured.
 */
class GeminiTempChatTest {

    private val appSentence = "Temporary chats don't appear in recent chats and aren't used to " +
        "improve Google AI. Stored for 72 hours for safety."

    @Test fun theStartScreenSentenceIsRecognised() {
        assertTrue(GeminiTempChat.isStartScreenText(appSentence))
    }

    /** The website splits the sentence into a link and plain text; the page walk joins them with
     *  whatever separator it uses. Either spelling must still match. */
    @Test fun theWebsitesSplitSentenceIsRecognised() {
        assertTrue(GeminiTempChat.isStartScreenText("Temporary chats\ndon't appear in\nrecent chats\nand aren't used"))
        assertTrue(GeminiTempChat.isStartScreenText("Just stopping by? Temporary chats don’t appear in recent chats"))
    }

    /**
     * ⚠️ **The rule this file exists for.** "New temporary chat" is the label of a button that is on
     * screen in EVERY ordinary Gemini chat. Matching it would block all of Gemini, which he did not
     * ask for — so the label, and anything short of the start-screen sentence, must not match.
     */
    @Test fun theButtonInEveryOrdinaryChatDoesNotMatch() {
        assertFalse(GeminiTempChat.isStartScreenText("New temporary chat"))
        assertFalse(GeminiTempChat.isStartScreenText("Temporary chat"))
        assertFalse(GeminiTempChat.isStartScreenText("Where should we start?"))
        assertFalse(GeminiTempChat.isStartScreenText("Recent chats"))
    }

    @Test fun bothGeminiPackagesAreWatched() {
        // The Gemini app is a shell; the chat runs in the Google app, which is what is in front.
        assertTrue("com.google.android.googlequicksearchbox" in GeminiTempChat.PACKAGES)
        assertTrue("com.google.android.apps.bard" in GeminiTempChat.PACKAGES)
    }

    @Test fun onlyGeminisOwnHostCounts() {
        assertTrue(GeminiTempChat.isGeminiHost("gemini.google.com"))
        assertTrue(GeminiTempChat.isGeminiHost("https://gemini.google.com/app"))
        assertTrue(GeminiTempChat.isGeminiHost("GEMINI.GOOGLE.COM/app?hl=en"))
        assertFalse(GeminiTempChat.isGeminiHost("google.com/search?q=gemini.google.com"))
        assertFalse(GeminiTempChat.isGeminiHost("notgemini.google.com"))
        assertFalse(GeminiTempChat.isGeminiHost(null))
    }

    // ---- the exit after "Got it" ---------------------------------------------------------------

    /** Measured: the first BACK closes the keyboard, the second leaves temporary mode. */
    @Test fun stillInTheModeStepsBackAgain() {
        assertEquals(GeminiTempChat.Step.BACK, GeminiTempChat.next(TempChatView.OPEN, 1, 350))
        assertEquals(GeminiTempChat.Step.BACK, GeminiTempChat.next(TempChatView.OPEN, 2, 700))
    }

    @Test fun anOrdinaryChatEndsTheWalkPressingNothing() {
        assertEquals(GeminiTempChat.Step.DONE, GeminiTempChat.next(TempChatView.CLOSED, 2, 700))
        assertEquals(GeminiTempChat.Step.DONE, GeminiTempChat.next(TempChatView.GONE, 1, 350))
    }

    /** Out of BACKs and still in the mode: leave Gemini. HOME is safe here (nothing floats away,
     *  unlike a Short), and staying would leave the temporary chat open under no cover. */
    @Test fun everyBackSpentLeavesGemini() {
        assertEquals(
            GeminiTempChat.Step.HOME,
            GeminiTempChat.next(TempChatView.OPEN, GeminiTempChat.MAX_BACKS, 1_000),
        )
    }

    /** A reading that could not be made never presses anything. */
    @Test fun anUnreadableScreenNeverPresses() {
        assertEquals(GeminiTempChat.Step.WAIT, GeminiTempChat.next(TempChatView.UNREADABLE, 1, 350))
        assertEquals(
            GeminiTempChat.Step.DONE,
            GeminiTempChat.next(TempChatView.UNREADABLE, 1, GeminiTempChat.GIVE_UP_MS),
        )
    }

    // ---- always ------------------------------------------------------------------------------

    /**
     * ⚠️ **His word, 31 Oct 2026: "make it blocked always".** No switch, and no waiting for Quick
     * Block. Every Gemini path in the watcher — the scheduler, the scan and the website branch — is
     * read here, and none of them may ask for a setting or for Quick Block. Reintroducing either
     * condition is how "always" would quietly become "when Quick Block is on".
     */
    @Test fun itIsBlockedAlways() {
        val text = listOf(File("src/main/java/com/appblocker/service/BlockerAccessibilityService.kt"),
            File("app/src/main/java/com/appblocker/service/BlockerAccessibilityService.kt"))
            .first { it.exists() }.readText()
        fun body(start: String): String {
            val from = text.indexOf(start)
            assertTrue("cannot find $start", from >= 0)
            return text.substring(from, text.indexOf("\n    private ", from + start.length))
        }
        val web = text.substring(text.indexOf("val onGemini = when (address)"))
            .substringBefore("// Browsers get the full filter")
        val paths = mapOf(
            "scheduleGeminiTempScan" to body("private fun scheduleGeminiTempScan()"),
            "scanGeminiTempChat" to body("private suspend fun scanGeminiTempChat()"),
            "the website branch" to web,
        )
        for ((name, code) in paths) {
            for (gate in listOf("quickBlockActive()", "SettingsStore.")) {
                assertFalse("$name asks $gate — Gemini's temporary chat is blocked always", gate in code)
            }
        }
    }

    // ---- the cover's place in CoverGate --------------------------------------------------------

    /** HOME would leave the mode open, so reopening Gemini would land on it and lock him out of all
     *  of Gemini. Its exit steps back out of the mode instead. */
    @Test fun theCoverStepsOutOfTheModeRatherThanLeaving() {
        assertEquals(CoverGate.Exit.BACK_OUT_OF_MODE, CoverGate.exitFor(GeminiTempChat.KEY))
    }

    /** The cover comes down before its walk (the mode cannot be read through it), so dismiss
     *  suppression must not stop a failed walk from being re-covered. */
    @Test fun theCoverIsNeverDismissSuppressed() {
        assertFalse(
            CoverGate.suppressed(
                GeminiTempChat.KEY, GeminiTempChat.KEY, "com.google.android.googlequicksearchbox",
                "com.google.android.googlequicksearchbox", sinceDismissMs = 10, viaBack = true,
            ),
        )
    }
}
