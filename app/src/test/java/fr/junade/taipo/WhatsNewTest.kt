package fr.junade.taipo

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

/**
 * Pilule « Nouveautés » : montrée les trois premières ouvertures du clavier, jusqu'à ce que la page soit ouverte. Page et notes de
 * version Play restent d'accord.
 */
class WhatsNewTest {

    @Test
    fun `montree les trois premieres fois`() {
        assertTrue(WhatsNew.shouldShow(shownCount = 0, opened = false))
        assertTrue(WhatsNew.shouldShow(shownCount = 2, opened = false))
        assertFalse(WhatsNew.shouldShow(shownCount = 3, opened = false))
    }

    @Test
    fun `plus montree une fois la page ouverte`() {
        assertFalse(WhatsNew.shouldShow(shownCount = 0, opened = true))
    }

    @Test
    fun `la 4_0 a des nouveautes, sans doublon`() {
        assertEquals("4.0", WhatsNew.RELEASE)
        assertTrue(WhatsNew.items.isNotEmpty())
        assertEquals(WhatsNew.items.size, WhatsNew.items.map { it.title }.toSet().size)
        assertEquals(WhatsNew.items.size, WhatsNew.items.mapNotNull { it.body }.toSet().size)
    }

    /**
     * Notes de version Play (`distribution/release-notes/<langue>.txt`) : un titre de la page par ligne, dans le même ordre, et
     * 500 caractères au plus (limite de Play, vérifiée aussi par tools/play_upload.py).
     */
    @Test
    fun `les notes Play reprennent les titres de la page`() {
        val notesDir = listOf(File("../distribution/release-notes"), File("distribution/release-notes")).first { it.isDirectory }
        val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        val names = WhatsNew.items.map { item -> resourceName(item.title) }
        for (file in notesDir.listFiles { f -> f.extension == "txt" }.orEmpty()) {
            val language = file.nameWithoutExtension
            val folder = if (language == "en") "values" else "values-$language"
            val titles = stringValues(File(res, "$folder/strings.xml"))
            val text = file.readText()
            assertTrue(text.length <= MAX_PLAY_NOTES, "$language : ${text.length} caractères")
            val bullets = text.lines().filter { it.startsWith("• ") }.map { it.removePrefix("• ") }
            assertEquals(names.map { titles[it] }, bullets, "$language : notes Play différentes des titres de la page")
        }
    }

    /** Nom de la ressource texte [id], retrouvé dans la classe R générée. */
    private fun resourceName(id: Int): String = R.string::class.java.fields.first { it.getInt(null) == id }.name

    /** Textes d'un strings.xml, apostrophes déséchappées (`\'` -> `'`). */
    private fun stringValues(file: File): Map<String, String> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val nodes = root.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val e = nodes.item(i) as Element
            e.getAttribute("name") to e.textContent.replace("\\'", "'").replace("\\\"", "\"")
        }
    }

    private companion object {
        const val MAX_PLAY_NOTES = 500
    }
}
