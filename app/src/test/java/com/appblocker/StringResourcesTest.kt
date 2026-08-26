package com.appblocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The two string tables have to agree.
 *
 * **A missing translation does not fail — it falls back.** Android silently serves the English
 * string for any key `values-ar` does not carry, so the failure mode is one English sentence in the
 * middle of an Arabic screen, on whichever screen nobody happened to open while testing. There is
 * no runtime error to notice and no crash to report. This is the only thing that will catch it.
 *
 * **And a wrong placeholder does crash.** `String.format` throws on an index that is not there, so
 * an Arabic string that says `%3$s` where the English has two arguments is a guaranteed exception
 * on a screen that works perfectly in English.
 *
 * Arabic needs all six plural forms. English distinguishes one from other and nothing else, so a
 * translation that copies the English shape reads wrong at 2, at 3–10 and at 11–99 — which covers
 * most of the numbers this app ever shows.
 */
class StringResourcesTest {

    /** Keys that are deliberately not translated. Keep this list tiny and say why for each. */
    private val untranslated = setOf(
        // The app is called AppBlocker in every language; a translated name would not match the
        // launcher icon, the Play listing or anything the owner has ever called it.
        "app_name",
    )

    /** What Arabic requires. Android's own plural rules for `ar` use every one of these. */
    private val arabicForms = setOf("zero", "one", "two", "few", "many", "other")

    private val english by lazy { EnglishStrings.read(EnglishStrings.resourceFile("values")) }
    private val arabic by lazy { EnglishStrings.read(EnglishStrings.resourceFile("values-ar")) }

    @Test
    fun `every english string is translated`() {
        val missing = (english.first.keys - untranslated) - arabic.first.keys
        assertEquals(
            "${missing.size} string(s) have no Arabic and will silently render in English",
            emptySet<String>(), missing,
        )
    }

    @Test
    fun `every english plural is translated`() {
        val missing = english.second.keys - arabic.second.keys
        assertEquals("plurals with no Arabic", emptySet<String>(), missing)
    }

    /** A key only Arabic has is dead: nothing looks it up, and it is usually a typo of a real one. */
    @Test
    fun `arabic has no keys english does not`() {
        val extra = (arabic.first.keys - english.first.keys) +
            (arabic.second.keys - english.second.keys)
        assertEquals("Arabic keys that no English key matches", emptySet<String>(), extra)
    }

    @Test
    fun `every arabic plural carries all six forms`() {
        val wrong = arabic.second.filterValues { !it.keys.containsAll(arabicForms) }
            .map { (name, forms) -> "$name has ${forms.keys.sorted()}" }
        assertEquals(
            "Arabic changes the noun at 1, 2, 3-10, 11-99 and 100+; every form is needed",
            emptyList<String>(), wrong,
        )
    }

    /**
     * **The one that would crash rather than merely read badly.**
     *
     * A translation may drop a placeholder — Arabic says "يوم واحد" where English says "1 day", and
     * the number is carried by the word itself. What it must never do is invent one: `%3$s` against
     * a two-argument call throws `MissingFormatArgumentException` the moment that string is shown.
     */
    @Test
    fun `no arabic string uses a placeholder its english does not`() {
        val offences = mutableListOf<String>()

        for ((key, ar) in arabic.first) {
            val en = english.first[key] ?: continue
            (placeholders(ar) - placeholders(en)).forEach {
                offences += "string/$key uses $it, English has ${placeholders(en).sorted()}"
            }
        }
        for ((key, forms) in arabic.second) {
            val enForms = english.second[key] ?: continue
            // Compared against every English form together: the English "one" and "other" may
            // legitimately differ, and an Arabic form is allowed to use anything either of them has.
            val allowed = enForms.values.flatMap { placeholders(it) }.toSet()
            for ((quantity, text) in forms) {
                (placeholders(text) - allowed).forEach {
                    offences += "plurals/$key[$quantity] uses $it, English has ${allowed.sorted()}"
                }
            }
        }
        assertEquals("placeholders that would throw at runtime", emptyList<String>(), offences)
    }

    /** A blank translation renders as an empty label, which looks like a broken screen. */
    @Test
    fun `no arabic string is empty`() {
        val blank = arabic.first.filterValues { it.isBlank() }.keys +
            arabic.second.filterValues { forms -> forms.values.any { it.isBlank() } }.keys
        assertEquals("empty Arabic strings", emptySet<String>(), blank)
    }

    /** The file has to exist at all — an `assertEquals` against two empty maps would pass. */
    @Test
    fun `the arabic file is really being read`() {
        assertTrue(
            "values-ar/strings.xml parsed to nothing, so every check above is vacuous",
            arabic.first.size > 100,
        )
        assertTrue(File(EnglishStrings.resourceFile("values-ar").path).isFile)
    }

    /**
     * **A dropped quote is not a missing quote — it is fifty-seven wrong attributions.**
     *
     * The block screen's quotes are two parallel `<string-array>`s zipped by index, because Android
     * resources have no structured type. Lose one line from either array in either language and
     * every line after it is credited to the wrong person, silently, on the one screen read at the
     * worst moment. Nothing else would catch that.
     */
    @Test
    fun `the quote arrays line up, in both languages`() {
        val en = EnglishStrings.readArrays(EnglishStrings.resourceFile("values"))
        val ar = EnglishStrings.readArrays(EnglishStrings.resourceFile("values-ar"))
        for ((label, table) in listOf("English" to en, "Arabic" to ar)) {
            val texts = table["quote_texts"] ?: error("$label has no quote_texts array")
            val authors = table["quote_authors"] ?: error("$label has no quote_authors array")
            assertEquals(
                "$label: ${texts.size} quotes but ${authors.size} attributions — every quote " +
                    "after the mismatch is credited to the wrong person",
                texts.size, authors.size,
            )
            assertTrue("$label has no quotes at all", texts.isNotEmpty())
        }
        assertEquals(
            "Arabic has a different number of quotes from English, so the two cannot be the " +
                "same list",
            en["quote_texts"]!!.size, ar["quote_texts"]!!.size,
        )
    }

    private fun placeholders(text: String): Set<String> =
        Regex("%\\d+\\$[sd]").findAll(text).map { it.value }.toSet()
}
