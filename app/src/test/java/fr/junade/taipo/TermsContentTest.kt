package fr.junade.taipo

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

/** Conditions d'utilisation : sections complètes et restrictions d'usage Gemma présentes (Gemma Terms of Use §2.2). */
class TermsContentTest {

    private val strings: Map<String, String> by lazy {
        val dir = listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory }
            ?: fail("Dossier src/main introuvable")
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(dir, "res/values/strings.xml")).documentElement
        val out = HashMap<String, String>()
        val nodes = root.getElementsByTagName("string")
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as Element
            out[e.getAttribute("name")] = e.textContent
        }
        out
    }

    @Test
    fun `les 7 sections ont chacune un titre et un texte`() {
        for (n in 1..7) {
            assertTrue(!strings["terms_section_${n}_title"].isNullOrBlank(), "titre $n")
            assertTrue(!strings["terms_section_${n}_body"].isNullOrBlank(), "texte $n")
        }
        assertEquals(7, Regex("terms_section_\\d+_title").findAll(strings.keys.joinToString(" ")).count())
    }

    @Test
    fun `les restrictions d usage Gemma sont reprises avec la politique officielle`() {
        val body = strings["terms_section_4_body"] ?: fail("section 4 absente")
        assertTrue(body.contains("Prohibited Use Policy"))
        assertTrue(body.contains("ai.google.dev/gemma/prohibited_use_policy"))
    }

    @Test
    fun `les conditions mentionnent les deux licences des modeles`() {
        val body = strings["terms_section_3_body"] ?: fail("section 3 absente")
        assertTrue(body.contains("Gemma Terms of Use"))
        assertTrue(body.contains("Apache 2.0"))
    }
}
