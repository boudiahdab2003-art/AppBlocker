package com.appblocker

import com.appblocker.service.BlockWords
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The real `values/strings.xml`, read off disk, so a JVM test can assert on the words the app
 * actually ships.
 *
 * **Why not just hardcode the English in the test.** `BlockDecisionTest` has thirty-four cases and
 * twenty-one of them assert on exact cover wording — that coverage is worth keeping, and it was
 * written before any of this text lived in a resource file. Copying the strings into the test would
 * have made it a test of a copy: the resource could drift and every assertion would still pass.
 * Reading the shipped file means those assertions now also guard the English resources themselves.
 *
 * Android's `R` class is on the unit-test classpath (its fields are plain static ints), so the
 * id → name direction comes from reflection and the name → text direction from the XML.
 */
object EnglishStrings : BlockWords {

    override fun get(id: Int, vararg args: Any): String {
        val name = stringNames[id] ?: error("no <string> in R.string with id $id")
        val raw = strings[name] ?: error("R.string.$name is not in values/strings.xml")
        return if (args.isEmpty()) raw else String.format(raw, *args)
    }

    override fun plural(id: Int, count: Int, vararg args: Any): String {
        val name = pluralNames[id] ?: error("no <plurals> in R.plurals with id $id")
        val forms = plurals[name] ?: error("R.plurals.$name is not in values/strings.xml")
        // English only distinguishes one from other; the six Arabic forms are the translation's
        // problem, and `StringResourcesTest` is what checks they are all present.
        val raw = (if (count == 1) forms["one"] else forms["other"]) ?: forms["other"]
            ?: error("R.plurals.$name has no \"other\" form")
        return if (args.isEmpty()) raw else String.format(raw, *args)
    }

    /** Every `<string>` name in the shipped English file. */
    val stringKeys: Set<String> get() = strings.keys

    /** Every `<plurals>` name, with the quantities each one declares. */
    val pluralKeys: Map<String, Set<String>> get() = plurals.mapValues { it.value.keys }

    private val stringNames: Map<Int, String> by lazy { idsOf("string") }
    private val pluralNames: Map<Int, String> by lazy { idsOf("plurals") }

    private fun idsOf(inner: String): Map<Int, String> {
        val cls = Class.forName("com.appblocker.R\$$inner")
        return cls.fields.associate { it.getInt(null) to it.name }
    }

    private val strings: Map<String, String> by lazy { parse().first }
    private val plurals: Map<String, Map<String, String>> by lazy { parse().second }

    private val parsed by lazy { read(resourceFile("values")) }

    private fun parse() = parsed

    /**
     * The file for a resource qualifier, e.g. "values" or "values-ar".
     *
     * Gradle runs unit tests with the module directory as the working directory, but a run started
     * from the repository root does not — so both are tried rather than assumed. Failing loudly
     * here beats a test that silently sees an empty string table and passes.
     */
    fun resourceFile(qualifier: String): File {
        val candidates = listOf(
            File("src/main/res/$qualifier/strings.xml"),
            File("app/src/main/res/$qualifier/strings.xml"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("cannot find $qualifier/strings.xml from ${File(".").absolutePath}")
    }

    /** Both tables out of one strings.xml. */
    fun read(file: File): Pair<Map<String, String>, Map<String, Map<String, String>>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val singles = LinkedHashMap<String, String>()
        val many = LinkedHashMap<String, Map<String, String>>()

        val stringNodes = doc.getElementsByTagName("string")
        for (n in 0 until stringNodes.length) {
            val e = stringNodes.item(n) as Element
            singles[e.getAttribute("name")] = unescape(e.textContent)
        }
        val pluralNodes = doc.getElementsByTagName("plurals")
        for (n in 0 until pluralNodes.length) {
            val e = pluralNodes.item(n) as Element
            val forms = LinkedHashMap<String, String>()
            val items = e.getElementsByTagName("item")
            for (m in 0 until items.length) {
                val item = items.item(m) as Element
                forms[item.getAttribute("quantity")] = unescape(item.textContent)
            }
            many[e.getAttribute("name")] = forms
        }
        return singles to many
    }

    /** Android resource escaping: an apostrophe and a quote must be backslashed in XML. */
    private fun unescape(text: String): String = text.replace("\\'", "'").replace("\\\"", "\"")
}
