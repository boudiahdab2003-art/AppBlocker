package com.appblocker

import com.appblocker.data.AppRule
import com.appblocker.data.BlockMode
import com.appblocker.data.QuickSession
import com.appblocker.data.SiteWord
import com.appblocker.data.StrictEdits
import com.appblocker.service.WebContentFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides which blocked words Strict Mode can afford to let go of.
 *
 * The owner asked for exactly one thing: "if the word is a website that is already blocked, let me
 * remove it even in Strict Mode". Everything here exists to make sure "already blocked" means what
 * it says — a word is only removable when some OTHER live rule blocks every address that word
 * blocked, so the removal cannot open anything.
 */
class StrictEditsTest {

    private val instagramBlocked = listOf(
        AppRule("com.instagram.android", "Instagram", isBlocked = true),
    )
    private val instagramWords = StrictEdits.liveSiteWords(
        instagramBlocked, allowlistMode = false, enforcing = true,
    )

    // --- the coverage rule -------------------------------------------------------------------

    @Test
    fun `a word equal to a live site word is covered`() {
        assertEquals("Instagram", StrictEdits.coveredBy("instagram", instagramWords))
    }

    @Test
    fun `a narrower word is covered - the site word still matches inside it`() {
        assertEquals("Instagram", StrictEdits.coveredBy("instagram.com", instagramWords))
        assertEquals("Instagram", StrictEdits.coveredBy("www.instagram.com/reels", instagramWords))
    }

    @Test
    fun `a BROADER word is not covered - it blocks addresses the site word never would`() {
        // "insta" would block insta.example.com; "instagram" would not. Removing it loses that.
        assertNull(StrictEdits.coveredBy("insta", instagramWords))
        assertNull(StrictEdits.coveredBy("gram", instagramWords))
    }

    @Test
    fun `a word the site word cannot match inside is not covered`() {
        // "instagram" never matches "instagrammers" (glued to a letter), so it covers nothing here.
        assertNull(StrictEdits.coveredBy("instagrammers", instagramWords))
    }

    @Test
    fun `an unrelated word is never covered`() {
        assertNull(StrictEdits.coveredBy("casino", instagramWords))
    }

    @Test
    fun `a short domain does not false-match inside a longer word`() {
        val twitter = StrictEdits.liveSiteWords(
            listOf(AppRule("com.twitter.android", "X (Twitter)", isBlocked = true)),
            allowlistMode = false, enforcing = true,
        )
        // "t.co" is one of X's site words; "chat.com" must not read as covered by it.
        assertNull(StrictEdits.coveredBy("chat.com", twitter))
        assertEquals("X (Twitter)", StrictEdits.coveredBy("t.co/abc", twitter))
    }

    @Test
    fun `covered by a later entry in the list, not just the first`() {
        assertEquals("Instagram", StrictEdits.coveredBy("ig.me/x", instagramWords))
    }

    @Test
    fun `with no live site words nothing is covered`() {
        assertNull(StrictEdits.coveredBy("instagram", emptyList()))
    }

    @Test
    fun `blank words are not covered`() {
        assertNull(StrictEdits.coveredBy("", instagramWords))
        assertNull(StrictEdits.coveredBy("   ", instagramWords))
    }

    @Test
    fun `case and spacing do not decide it`() {
        assertEquals("Instagram", StrictEdits.coveredBy("  InstaGram.COM  ", instagramWords))
        assertEquals(
            "Instagram",
            StrictEdits.coveredBy("instagram", listOf(SiteWord(" INSTAGRAM ", "Instagram"))),
        )
    }

    @Test
    fun `coveringSiteWord names the word and the app, for the row and the dialog`() {
        val hit = StrictEdits.coveringSiteWord("instagram.com", instagramWords)
        assertEquals("instagram", hit?.word)
        assertEquals("Instagram", hit?.appLabel)
    }

    /**
     * The promise the rule makes, checked against the matcher that has to keep it: every address a
     * covered word blocked is still blocked by the site word alone. This is what stops the rule
     * and [WebContentFilter.checkUrl] drifting apart if either is ever touched.
     */
    @Test
    fun `a covered word never blocked an address its site word does not`() {
        val filter = WebContentFilter(emptyList(), emptyList(), emptyList(), emptyList(), EnglishStrings)
        val addresses = listOf(
            "instagram.com", "www.instagram.com/reels", "m.instagram.com/p/abc",
            "instagram.com.evil.example", "example.com/instagram.com", "notinstagram.com",
            "ig.me/u/x", "example.com", "instagrammers.example.com",
        )
        for (word in listOf("instagram", "instagram.com", "www.instagram.com/reels", "ig.me")) {
            val covering = StrictEdits.coveringSiteWord(word, instagramWords) ?: continue
            for (url in addresses) {
                val byWord = filter.checkUrl(url, listOf(word), emptyList()) != null
                val bySite = filter.checkUrl(url, emptyList(), listOf(covering.word)) != null
                assertTrue(
                    "removing “$word” would unblock $url — it is not really covered",
                    !byWord || bySite,
                )
            }
        }
    }

    @Test
    fun `an uncovered word has an address that proves it - the word itself`() {
        val filter = WebContentFilter(emptyList(), emptyList(), emptyList(), emptyList(), EnglishStrings)
        val word = "insta"
        assertNull(StrictEdits.coveringSiteWord(word, instagramWords))
        assertTrue(filter.checkUrl(word, listOf(word), emptyList()) != null)
        assertNull(filter.checkUrl(word, emptyList(), listOf("instagram")))
    }

    // --- which site words are live --------------------------------------------------------

    @Test
    fun `a blocked social app contributes all of its website words`() {
        assertEquals(listOf("instagram", "ig.me"), instagramWords.map { it.word })
        assertTrue(instagramWords.all { it.appLabel == "Instagram" })
    }

    @Test
    fun `an app that is not blocked contributes nothing`() {
        val rules = listOf(AppRule("com.instagram.android", "Instagram", isBlocked = false))
        assertTrue(StrictEdits.liveSiteWords(rules, false, true).isEmpty())
    }

    @Test
    fun `a daily-limit rule contributes nothing - it is not a block`() {
        val rules = listOf(
            AppRule("com.instagram.android", "Instagram", isBlocked = true, mode = BlockMode.LIMIT),
        )
        assertTrue(StrictEdits.liveSiteWords(rules, false, true).isEmpty())
    }

    @Test
    fun `an app with no known website contributes nothing`() {
        val rules = listOf(AppRule("com.example.notes", "Notes", isBlocked = true))
        assertTrue(StrictEdits.liveSiteWords(rules, false, true).isEmpty())
    }

    @Test
    fun `allowlist mode has no site words, so no word is removable`() {
        assertTrue(StrictEdits.liveSiteWords(instagramBlocked, allowlistMode = true, enforcing = true).isEmpty())
    }

    @Test
    fun `nothing enforcing means no site words`() {
        assertTrue(StrictEdits.liveSiteWords(instagramBlocked, allowlistMode = false, enforcing = false).isEmpty())
    }

    @Test
    fun `the lite builds and YouTube carry their websites too`() {
        val rules = listOf(
            AppRule("com.instagram.lite", "Instagram Lite", isBlocked = true),
            AppRule("com.google.android.youtube", "YouTube", isBlocked = true),
        )
        val words = StrictEdits.liveSiteWords(rules, false, true).map { it.word }
        assertTrue(words.containsAll(listOf("instagram", "ig.me", "youtube", "youtu.be")))
    }

    // --- what a write is allowed to do ------------------------------------------------------

    @Test
    fun `outside a session every removal goes through`() {
        val allowed = StrictEdits.allowedDeletions(
            current = setOf("instagram", "casino"), target = setOf("instagram"),
            strict = false, siteWords = emptyList(),
        )
        assertEquals(setOf("casino"), allowed)
    }

    @Test
    fun `during a session only covered words may go`() {
        val allowed = StrictEdits.allowedDeletions(
            current = setOf("instagram", "casino"), target = emptySet(),
            strict = true, siteWords = instagramWords,
        )
        assertEquals(setOf("instagram"), allowed)
    }

    @Test
    fun `a staged save cannot delete a word it never saw`() {
        // The editor was opened when only "casino" existed; "betting" was added elsewhere since.
        val allowed = StrictEdits.allowedDeletions(
            current = setOf("casino", "betting"), target = setOf("casino"),
            strict = true, siteWords = instagramWords,
        )
        assertTrue(allowed.isEmpty())
    }

    @Test
    fun `additions are never touched by the deletion rule`() {
        val allowed = StrictEdits.allowedDeletions(
            current = setOf("casino"), target = setOf("casino", "poker"),
            strict = true, siteWords = emptyList(),
        )
        assertTrue(allowed.isEmpty())
    }

    @Test
    fun `outside a session the app selections are committed as staged`() {
        val (blocked, allowed) = StrictEdits.allowedSelections(
            currentBlocked = setOf("a", "b"), currentAllowed = setOf("c"),
            targetBlocked = setOf("a"), targetAllowed = setOf("c", "d"),
            strict = false, allowlistMode = false,
        )
        assertEquals(setOf("a"), blocked)
        assertEquals(setOf("c", "d"), allowed)
    }

    @Test
    fun `a stale save cannot un-block an app during a session`() {
        val (blocked, _) = StrictEdits.allowedSelections(
            currentBlocked = setOf("a", "b"), currentAllowed = emptySet(),
            targetBlocked = setOf("a"), targetAllowed = emptySet(),
            strict = true, allowlistMode = false,
        )
        assertEquals(setOf("a", "b"), blocked)
    }

    @Test
    fun `blocking more during a session still goes through`() {
        val (blocked, _) = StrictEdits.allowedSelections(
            currentBlocked = setOf("a"), currentAllowed = emptySet(),
            targetBlocked = setOf("a", "b"), targetAllowed = emptySet(),
            strict = true, allowlistMode = false,
        )
        assertEquals(setOf("a", "b"), blocked)
    }

    @Test
    fun `in allowlist mode a session refuses to allow anything new`() {
        val (_, allowed) = StrictEdits.allowedSelections(
            currentBlocked = emptySet(), currentAllowed = setOf("a"),
            targetBlocked = emptySet(), targetAllowed = setOf("a", "b"),
            strict = true, allowlistMode = true,
        )
        assertEquals(setOf("a"), allowed)
    }

    @Test
    fun `in allowlist mode a session still lets you take an app off the allowed list`() {
        val (_, allowed) = StrictEdits.allowedSelections(
            currentBlocked = emptySet(), currentAllowed = setOf("a", "b"),
            targetBlocked = emptySet(), targetAllowed = setOf("a"),
            strict = true, allowlistMode = true,
        )
        assertEquals(setOf("a"), allowed)
    }

    // --- the shared "is Quick Block enforcing" answer ------------------------------------------

    private fun idle() = QuickSession.State(false, false, 0L, "")
    private fun working() = QuickSession.State(true, true, 60_000L, "Work")
    private fun onBreak() = QuickSession.State(true, false, 60_000L, "Break")

    @Test
    fun `Strict beats every pause there is`() {
        assertTrue(
            QuickSession.enforcing(
                strictRemainingMs = 1L, updatePaused = true,
                session = onBreak(), quickBlockPaused = true,
            )
        )
    }

    @Test
    fun `the after-update pause stops enforcement when no session is running`() {
        assertFalse(QuickSession.enforcing(0L, updatePaused = true, session = idle(), quickBlockPaused = false))
    }

    @Test
    fun `a running session enforces during work and not during a break`() {
        assertTrue(QuickSession.enforcing(0L, false, working(), quickBlockPaused = true))
        assertFalse(QuickSession.enforcing(0L, false, onBreak(), quickBlockPaused = false))
    }

    @Test
    fun `with no session it comes down to the manual pause`() {
        assertTrue(QuickSession.enforcing(0L, false, idle(), quickBlockPaused = false))
        assertFalse(QuickSession.enforcing(0L, false, idle(), quickBlockPaused = true))
    }
}
