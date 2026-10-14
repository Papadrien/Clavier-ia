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
    fun `la mesure d usage Aptabase est annoncee et desactivable, sans promesse de zero telemetrie`() {
        val all = strings.filterKeys { it.startsWith("privacy_") }.values.joinToString(" ")
        // Aptabase est dans le projet : le texte doit le dire (sections 2 et 3), et ne plus prétendre qu'il n'y a rien à désactiver.
        assertTrue(strings["privacy_section_2_body"]!!.contains("Aptabase"))
        assertTrue(strings["privacy_section_3_body"]!!.contains("Aptabase"))
        assertFalse(all.contains("rien à désactiver"))
        assertFalse(all.contains("ni télémétrie"))
        // Le texte renvoie à la bascule, qui doit exister dans l'écran des paramètres.
        assertTrue(strings["privacy_section_3_body"]!!.contains("Paramètres du clavier > Mesure d"))
        assertTrue(!strings["keyboard_settings_analytics_switch"].isNullOrBlank())
    }

    @Test
    fun `le texte ne promet que ce que la mesure garantit`() {
        val body = strings["privacy_section_3_body"]!!
        assertTrue(body.contains("Jamais votre texte"))
        assertTrue(body.contains("champs de mot de passe"))
        assertTrue(body.contains("identifiant"))
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
