package com.appblocker

import com.appblocker.service.BugReportSender
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Strings the app reads back must not change with the phone's language.**
 *
 * Arabic shipped in v1.141 and the owner has never switched to it, so nothing in this app has ever
 * run under a locale whose digits are not `0123456789`. Two kinds of string care about that and
 * they pull in opposite directions:
 *
 *  - **For a person to read** — a clock on the block screen, a duration in the coach. A localised
 *    digit is the *right* answer there, and `ui/TimeFormat` and friends deliberately stay on the
 *    default locale.
 *  - **For the app to read back** — a dedupe key, a stored hash, anything parsed with `toInt()`.
 *    A localised digit there is a bug that only appears in one language.
 *
 * The lesson is in this repo already, from the localisation release: *`%d` rendering ٠١٢٣*. It was
 * written as a fact about layout strings and never grepped for, and `"%d".format(...)` — Kotlin's
 * shorthand for `String.format` — takes the **default** locale every time.
 *
 * ⚠️ These tests set the JVM default locale. Each one restores it in [restore], because a leaked
 * default would quietly change the meaning of every other test in the suite.
 */
class LocaleSafetyTest {

    private val original: Locale = Locale.getDefault()

    /** An Arabic locale that really does render digits as ٠١٢٣ — plain `ar` does not, so a test
     *  written with it would pass against the bug and prove nothing. */
    private val arabicDigits: Locale = Locale.forLanguageTag("ar-EG-u-nu-arab")

    @After fun restore() = Locale.setDefault(original)

    /** The premise, proved rather than assumed: this locale formats `%d` in Arabic-Indic digits. */
    @Test
    fun `the test locale really does change what a number looks like`() {
        Locale.setDefault(arabicDigits)
        assertEquals(
            "if this reads 2026 the locale is not exercising anything and every other test here " +
                "is worthless",
            "٢٠٢٦",
            "%d".format(2026),
        )
    }

    /**
     * ⚠️ **The weekly report's key, which is parsed back with `toInt()`.** In Arabic-Indic digits
     * that parse throws, so the skipped-weeks count would silently read 0 while the dedupe key
     * changed shape underneath the queue.
     */
    @Test
    fun `the week label stays ascii in every language`() {
        // Both the explicitly-Arabic-numbered locale and plain `ar`, which is what the app's own
        // language setting produces. Asserting the ASCII result under each keeps this honest
        // whichever of them a future JDK or CLDR decides to render in Arabic-Indic digits — the
        // property under test is the formatter's locale, not the platform's default numbering.
        for (locale in listOf(arabicDigits, Locale.forLanguageTag("ar"), Locale.GERMANY)) {
            Locale.setDefault(locale)
            assertEquals(locale.toString(), "2026-W36", BugReportSender.weekLabel(2026, 36))
            assertEquals(locale.toString(), "2026-W05", BugReportSender.weekLabel(2026, 5))
        }
    }

    /** And it still round-trips through the parser the sender uses on it. */
    @Test
    fun `the week label parses back after being built in another language`() {
        Locale.setDefault(arabicDigits)
        val label = BugReportSender.weekLabel(2026, 36)
        val (year, week) = label.split("-W").let { it[0].toInt() to it[1].toInt() }
        assertEquals(2026, year)
        assertEquals(36, week)
    }

    /**
     * **The PIN hash, and the reason this test exists rather than a change to `PinStore`.**
     *
     * `PinStore.hash` builds a SHA-256 as hex with `"%02x".format(byte)`, which is the same
     * default-locale call as everything above. If `%x` localised its digits the way `%d` does, the
     * stored hash would depend on the phone's language: switch language and the owner is locked
     * out of his own PIN, and "fixing" it afterwards would lock him out again.
     *
     * ⚠️ It does not localise — `java.util.Formatter` applies localised digits to decimal
     * conversions only — and **that is worth a test rather than a memory of the JDK source**. This
     * pins the property `PinStore` depends on without touching `PinStore`, which is the safe way
     * round: a stored hash may not change format under anyone's feet.
     */
    @Test
    fun `hex formatting is not localised, which is what the pin hash rests on`() {
        Locale.setDefault(arabicDigits)
        val hex = byteArrayOf(0x0a, 0x2f, 0xff.toByte()).joinToString("") { "%02x".format(it) }
        assertEquals("0a2fff", hex)
        assertTrue(
            "a hash must be ascii hex whatever the phone's language, or a language change " +
                "locks the owner out of his own PIN",
            hex.all { it in '0'..'9' || it in 'a'..'f' },
        )
    }
}
