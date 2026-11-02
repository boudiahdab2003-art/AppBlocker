package com.appblocker

import com.appblocker.service.GeminiInstructions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Gemini's instructions page, blocked always — invariant 100, his ask of 2 Nov 2026.
 *
 * The page text below is what his phone showed that day over USB: Gemini app ▸ Profile ▸ Personal
 * context opened `gemini.google.com/saved-info` in a Chrome Custom Tab.
 */
class GeminiInstructionsTest {

    private val page = "Personal context\nGet a personalized experience with memory of your past " +
        "chats. Also, customize Gemini's responses by giving it instructions. Learn more\nMemory\n" +
        "Gemini learns from your past chats to understand more about you.\nYour instructions for " +
        "Gemini\nCustomize your Gemini experience by giving it instructions. Learn more\nAdd\nDelete all"

    @Test fun thePageAsReadOnHisPhoneIsRecognised() {
        assertTrue(GeminiInstructions.isInstructionsText(page))
        // The Custom Tab's address bar shows only the host, so the text decides there.
        assertTrue(GeminiInstructions.isInstructionsPage("gemini.google.com", page))
    }

    @Test fun theOlderSavedInfoHeadingCountsToo() {
        assertTrue(GeminiInstructions.isInstructionsText("Saved info\nYour instructions for Gemini\nAdd"))
    }

    /** The page's own address decides by itself, whatever has been read of the page so far. */
    @Test fun theAddressAloneIsEnough() {
        assertTrue(GeminiInstructions.isInstructionsPage("https://gemini.google.com/saved-info?hl=en-US", ""))
        assertTrue(GeminiInstructions.isInstructionsPage("gemini.google.com/saved-info", ""))
        assertTrue(GeminiInstructions.isInstructionsUrl("GEMINI.GOOGLE.COM/saved-info/"))
    }

    /** ⚠️ The rest of Gemini must keep working: an ordinary chat, its address, and a chat in which
     *  the heading's words were typed are not the page. */
    @Test fun ordinaryGeminiIsNotThePage() {
        assertFalse(GeminiInstructions.isInstructionsPage("https://gemini.google.com/app", "Hello, Abdallah\nAsk Gemini"))
        assertFalse(GeminiInstructions.isInstructionsPage("gemini.google.com/app/abc123",
            "what are your instructions for gemini? New chat"))
        assertFalse(GeminiInstructions.isInstructionsText("Personal context"))
        assertFalse(GeminiInstructions.isInstructionsUrl("https://google.com/search?q=gemini.google.com/saved-info"))
        assertFalse(GeminiInstructions.isInstructionsUrl("notgemini.google.com/saved-info"))
        assertFalse(GeminiInstructions.isInstructionsUrl(null))
    }

    /**
     * ⚠️ **His choice, 2 Nov 2026: "Always".** The website branch that covers the page must not ask
     * for a setting or for Quick Block — that is how "always" would quietly become "when Quick Block
     * is on", which is precisely the moment of a relapse when he pauses it.
     */
    @Test fun itIsBlockedAlways() {
        val text = listOf(File("src/main/java/com/appblocker/service/BlockerAccessibilityService.kt"),
            File("app/src/main/java/com/appblocker/service/BlockerAccessibilityService.kt"))
            .first { it.exists() }.readText()
        val start = text.indexOf("// Gemini's instructions page (invariant 100)")
        assertTrue("the instructions branch is gone from the watcher", start >= 0)
        val branch = text.substring(start).substringBefore("// Browsers get the full filter")
        assertTrue(branch.contains("GeminiInstructions.isInstructionsPage("))
        assertTrue(branch.contains("showBlockScreen("))
        for (gate in listOf("quickBlockActive()", "SettingsStore.")) {
            assertFalse("the instructions branch asks $gate — it is blocked always", gate in branch)
        }
    }
}
