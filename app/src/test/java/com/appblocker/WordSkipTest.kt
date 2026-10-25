package com.appblocker

import com.appblocker.data.WordSkip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariant 87: the apps "check every app" leaves out, his choice of 10 Oct 2026 — YouTube, Claude
 * and WhatsApp, for battery. What it skips, what it never reaches, and the Strict rule on the write.
 */
class WordSkipTest {

    private val whatsapp = "com.whatsapp"
    private val claude = "com.anthropic.claude"
    private val chrome = "com.android.chrome"
    private val list = setOf(whatsapp, claude)

    @Test
    fun `an app on the list is not read for words`() {
        assertTrue(WordSkip.skips(whatsapp, list, isBrowser = false, dangerZoneOn = false))
    }

    @Test
    fun `an app not on the list is read as before`() {
        assertFalse(WordSkip.skips("com.instagram.android", list, isBrowser = false, dangerZoneOn = false))
    }

    @Test
    fun `an empty list skips nothing`() {
        assertFalse(WordSkip.skips(whatsapp, emptySet(), isBrowser = false, dangerZoneOn = false))
    }

    @Test
    fun `a browser is never skipped, even if it is on the list`() {
        // The browser scan carries the site layer and the adult list, not just his words.
        assertFalse(WordSkip.skips(chrome, list + chrome, isBrowser = true, dangerZoneOn = false))
    }

    @Test
    fun `the danger hour reads every app, the skipped ones too`() {
        assertFalse(WordSkip.skips(whatsapp, list, isBrowser = false, dangerZoneOn = true))
    }

    @Test
    fun `outside Strict the list is stored as chosen`() {
        assertEquals(setOf(whatsapp, "com.google.android.youtube"),
            WordSkip.allowed(setOf(whatsapp), setOf(whatsapp, "com.google.android.youtube"), strict = false))
        assertEquals(emptySet<String>(), WordSkip.allowed(list, emptySet(), strict = false))
    }

    @Test
    fun `during Strict nothing can be added to the list`() {
        assertEquals(list, WordSkip.allowed(list, list + "com.google.android.youtube", strict = true))
    }

    @Test
    fun `during Strict an app can always be taken off the list`() {
        // Taking one off makes blocking stronger, and refusing that is never the safe direction.
        assertEquals(setOf(claude), WordSkip.allowed(list, setOf(claude), strict = true))
    }

    @Test
    fun `during Strict a swap is only the removal half`() {
        assertEquals(setOf(claude), WordSkip.allowed(list, setOf(claude, "com.google.android.youtube"), strict = true))
    }

    // ---- invariant 94: Spotify, always ------------------------------------------------------

    private val spotify = "com.spotify.music"

    @Test
    fun `Spotify is never read for words, with no list at all`() {
        assertTrue(WordSkip.skips(spotify, emptySet(), isBrowser = false, dangerZoneOn = false))
        assertTrue(WordSkip.skips("com.spotify.lite", emptySet(), isBrowser = false, dangerZoneOn = false))
    }

    /** A Strict session refuses additions to the list, and Spotify needs none — that is the point. */
    @Test
    fun `Spotify needs no list entry, so Strict does not stand in its way`() {
        val duringStrict = WordSkip.allowed(emptySet(), setOf(spotify), strict = true)
        assertEquals(emptySet<String>(), duringStrict)
        assertTrue(WordSkip.skips(spotify, duringStrict, isBrowser = false, dangerZoneOn = false))
    }

    @Test
    fun `Claude is never read for words either`() {
        assertTrue(WordSkip.skips(claude, emptySet(), isBrowser = false, dangerZoneOn = false))
        assertFalse(WordSkip.skips(claude, emptySet(), isBrowser = false, dangerZoneOn = true))
    }

    @Test
    fun `the danger hour still reads Spotify`() {
        assertFalse(WordSkip.skips(spotify, emptySet(), isBrowser = false, dangerZoneOn = true))
    }

    @Test
    fun `the exception never reaches a browser or any other app`() {
        assertFalse(WordSkip.skips(spotify, emptySet(), isBrowser = true, dangerZoneOn = false))
        assertFalse(WordSkip.skips("com.instagram.android", emptySet(), isBrowser = false, dangerZoneOn = false))
        assertTrue(WordSkip.ALWAYS.none { it == chrome })
    }
}
