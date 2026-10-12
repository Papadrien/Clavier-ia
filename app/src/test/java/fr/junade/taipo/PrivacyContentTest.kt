package fr.junade.taipo

import fr.junade.taipo.model.AiModel
import java.io.File
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

/** Menu « Confidentialité » (story 10.2) : sections complètes et texte cohérent avec les connexions réelles de l'application. */
class PrivacyContentTest {

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
    fun `les 5 sections ont chacune un titre et un texte`() {
        for (n in 1..5) {
            assertTrue(!strings["privacy_section_${n}_title"].isNullOrBlank(), "titre $n")
            assertTrue(!strings["privacy_section_${n}_body"].isNullOrBlank(), "texte $n")
        }
        assertEquals(5, Regex("privacy_section_\\d+_title").findAll(strings.keys.joinToString(" ")).count())
        assertEquals(PrivacyContent.sections.size, 5)
    }

    @Test
    fun `le texte annonce le traitement local et le telechargement des modeles`() {
        assertTrue(strings["privacy_section_1_body"]!!.contains("sur votre appareil"))
        assertTrue(strings["privacy_section_2_body"]!!.contains("télécharger"))
    }

    @Test
    fun `aucune bascule de telemetrie n est proposee et aucun outil absent n est cite`() {
        val all = strings.filterKeys { it.startsWith("privacy_") }.values.joinToString(" ")
        assertTrue(all.contains("rien à désactiver"))
        // Aptabase n'est pas dans le projet : le citer serait faux. À ajouter ici et à la section 3 le jour où il y entre.
        assertFalse(all.contains("Aptabase"))
    }

    @Test
    fun `chaque domaine de telechargement des modeles est cite dans le texte`() {
        val body = strings["privacy_section_2_body"]!!
        val hosts = AiModel.entriesOrdered().mapNotNull { it.downloadUrl }.map { URI(it).host }.toSet()
        hosts.forEach { host ->
            val mention = when {
                host.endsWith("huggingface.co") -> "Hugging Face"
                host.endsWith("workers.dev") -> "serveur de Junadé"
                else -> fail("Domaine de téléchargement non couvert par le texte de confidentialité : $host")
            }
            assertTrue(body.contains(mention), "le texte devrait citer « $mention » ($host)")
        }
    }
}
