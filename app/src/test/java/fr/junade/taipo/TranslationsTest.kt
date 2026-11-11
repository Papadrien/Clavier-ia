package fr.junade.taipo

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

/**
 * Audit UX-07 : chaque langue de l'interface traduit tous les textes de `values/strings.xml`, avec les mêmes marqueurs de
 * format (`%1$s`, `%d`…) : un marqueur oublié ou en trop fait planter l'écran qui affiche le texte. Les langues déclarées à
 * Android (`res/xml/locales_config.xml`) sont exactement celles qui ont un dossier `values-<langue>`.
 */
class TranslationsTest {

    private val res: File = listOf(File("src/main/res"), File("app/src/main/res")).firstOrNull { it.isDirectory }
        ?: fail("Dossier res introuvable")

    private val languages = listOf("fr", "de", "es", "it", "pt", "nl")

    private val placeholder = Regex("""%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]""")

    /** Textes traduisibles : nom → texte (les quantités d'un pluriel sont réunies). */
    private fun strings(folder: String): Map<String, String> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(res, "$folder/strings.xml")).documentElement
        val out = LinkedHashMap<String, String>()
        val nodes = root.childNodes
        val elements = (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
        for (e in elements.filter { it.getAttribute("translatable") != "false" }) {
            when (e.tagName) {
                "string" -> out[e.getAttribute("name")] = e.textContent
                "plurals" -> out[e.getAttribute("name")] = e.getElementsByTagName("item").item(0)?.textContent.orEmpty()
            }
        }
        return out
    }

    private fun placeholders(text: String) = placeholder.findAll(text).map { it.value }.sorted().toList()

    @Test
    fun `chaque langue traduit tous les textes`() {
        val source = strings("values")
        for (language in languages) {
            val translated = strings("values-$language")
            val missing = source.keys - translated.keys
            val unknown = translated.keys - source.keys
            assertTrue(missing.isEmpty(), "$language : textes manquants $missing")
            assertTrue(unknown.isEmpty(), "$language : textes inconnus $unknown")
        }
    }

    @Test
    fun `les marqueurs de format sont ceux de l anglais`() {
        val source = strings("values")
        for (language in languages) {
            val translated = strings("values-$language")
            val wrong = source.filter { (name, text) -> translated[name]?.let { placeholders(it) != placeholders(text) } == true }.keys
            assertTrue(wrong.isEmpty(), "$language : marqueurs différents dans $wrong")
        }
    }

    @Test
    fun `les langues declarees a Android sont celles qui sont traduites`() {
        val declared = Regex("""android:name="([a-z-]+)"""").findAll(File(res, "xml/locales_config.xml").readText())
            .map { it.groupValues[1] }.toSet()
        assertEquals(setOf("en") + languages, declared)
        val folders = res.listFiles().orEmpty()
            .filter { it.isDirectory && File(it, "strings.xml").isFile }
            .mapNotNull { Regex("""values-([a-z]{2})""").matchEntire(it.name)?.groupValues?.get(1) }
            .toSet()
        assertEquals(languages.toSet(), folders)
    }
}
