package com.appblocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **English typed straight into a translated screen.**
 *
 * [StringResourcesTest] guards the two string tables, and a literal in Kotlin never reaches them: it
 * renders in English on an Arabic phone — with its punctuation at the wrong end — and no test
 * notices. The first Arabic tour of the app (15 Sep 2026, on the emulator) found the onboarding's
 * "Continue", its "Granted" chip and its whole last step in English, and a scan of `ui/` found about
 * two hundred and fifty more literals, nearly all added after v1.141's translation, because nothing
 * mechanical ever stopped the next one.
 *
 * So this is a ratchet rather than a ban. `english_literals_baseline.txt` lists the English still in
 * translated screens today. A literal that is not on it fails the build, and so does a baseline line
 * whose literal has gone — the list may only ever shrink. The six screens the owner chose to keep in
 * English (each wrapped in `EnglishOnly` at its `AppRoot` call site) and the two files that only feed
 * them are not scanned.
 */
class TranslatedScreensTest {

    private val englishOnly = setOf(
        "ChangelogScreen.kt", "InstructionsScreen.kt", "DiagnosticsScreen.kt",
        "DopamineDetoxScreen.kt", "ScenariosScreen.kt", "TwelveStepsScreen.kt",
        // The content of the English-only guides above, used by nothing else.
        "Scenarios.kt", "GuideComponents.kt",
    )

    private fun firstExisting(vararg paths: String): File =
        paths.map(::File).firstOrNull { it.exists() }
            ?: error("none of ${paths.toList()} exists from ${File(".").absolutePath}")

    private fun uiDir(): File =
        firstExisting("src/main/java/com/appblocker/ui", "app/src/main/java/com/appblocker/ui")

    private fun baselineFile(): File = firstExisting(
        "src/test/resources/english_literals_baseline.txt",
        "app/src/test/resources/english_literals_baseline.txt",
    )

    private val literal = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")

    /** Lines whose strings never reach the screen: imports, comments, annotations, tags, logs. */
    private val notUserText = Regex(
        """^(import |package |//|\*|/\*|@Suppress|@OptIn|@Preview)|const val |testTag\(|Log\.[dewiv]\(|\berror\(|\brequire\(|\bcheck\(|TODO\(""",
    )

    /**
     * Whether a literal reads as words for a person: two letters in a row once the string templates
     * are taken out, and either a capital first letter or a space. Keys (`"usage"`), formats (`"%d"`)
     * and addresses are not prose.
     */
    internal fun looksLikeProse(raw: String): Boolean {
        val visible = raw
            .replace(Regex("""\$\{[^}]*\}"""), " ")
            .replace(Regex("""\$[A-Za-z_][A-Za-z0-9_]*"""), " ")
            .trim()
        if (visible.isEmpty()) return false
        if (Regex("^(https?://|package:|market://|mailto:)").containsMatchIn(visible)) return false
        if (!Regex("[A-Za-z]{2,}").containsMatchIn(visible)) return false
        return visible.first().isUpperCase() || ' ' in visible
    }

    /** Every prose literal in a translated `ui/` file, as `File.kt|text`. */
    internal fun scan(): Set<String> {
        val out = sortedSetOf<String>()
        val files = uiDir().listFiles()!!
            .filter { it.isFile && it.extension == "kt" && it.name !in englishOnly }
        for (file in files) {
            for (line in file.readLines().map { it.trim() }) {
                if (notUserText.containsMatchIn(line)) continue
                for (m in literal.findAll(line)) {
                    val text = m.groupValues[1].trim()
                    if (looksLikeProse(text)) out += "${file.name}|$text"
                }
            }
        }
        return out
    }

    private fun baseline(): Set<String> = baselineFile().readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .toSet()

    @Test
    fun `no new english is typed into a translated screen`() {
        val found = scan()
        // The whole current list, for the one legitimate edit: shrinking the baseline after a fix.
        runCatching {
            File("build").mkdirs()
            File("build/english_literals_current.txt").writeText(found.joinToString("\n", postfix = "\n"))
        }
        val added = found - baseline()
        assertEquals(
            "English typed straight into a translated screen shows in English on an Arabic phone. " +
                "Move it to values/strings.xml, with its Arabic in values-ar/strings.xml:\n" +
                added.joinToString("\n"),
            emptySet<String>(), added,
        )
    }

    @Test
    fun `the english baseline only shrinks`() {
        val gone = baseline() - scan()
        assertEquals(
            "No longer in the code — delete these lines from english_literals_baseline.txt, so the " +
                "same English cannot come back without failing the build:\n" + gone.joinToString("\n"),
            emptySet<String>(), gone,
        )
    }

    @Test
    fun `the scan recognises prose and ignores the rest`() {
        assertTrue(looksLikeProse("Continue"))
        assertTrue(looksLikeProse("You're all set"))
        assertTrue(looksLikeProse("You skipped \${n} of the \$total switches"))
        assertTrue(looksLikeProse("🔒 Strict Mode — you can add blocks"))
        assertFalse(looksLikeProse("usage"))
        assertFalse(looksLikeProse("\$headline · \$detail"))
        assertFalse(looksLikeProse("https://Example.com/Path"))
        assertFalse(looksLikeProse("%d"))
        assertTrue("the scan read almost nothing, so the ratchet is vacuous", scan().size > 50)
    }
}
