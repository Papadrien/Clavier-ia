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

/**
 * Menu « Confidentialité » (story 10.2) : sections complètes et texte cohérent avec les connexions réelles de l'application,
 * dans les deux langues de l'interface (anglais par défaut dans `values/`, français dans `values-fr/`).
 */
class PrivacyContentTest {

    private fun load(folder: String): Map<String, String> {
        val dir = listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory }
            ?: fail("Dossier src/main introuvable")
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(dir, "res/$folder/strings.xml")).documentElement
        val out = HashMap<String, String>()
        val nodes = root.getElementsByTagName("string")
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as Element
            out[e.getAttribute("name")] = e.textContent
        }
        return out
    }

    /** Textes propres à une langue : ce que les sections doivent dire, et les promesses qu'elles ne doivent pas faire. */
    private class Locale(
        val folder: String,
        val onDevice: String,
        val download: String,
        val settingsPath: String,
        val neverText: String,
        val passwordFields: String,
        val identifier: String,
        val ownServer: String,
        val nothingToDisable: String,
        val noTelemetry: String,
    ) {
        val strings: Map<String, String> by lazy { PrivacyContentTest().load(folder) }
    }

    private val locales = listOf(
        Locale(
            "values", "on your device", "download", "Keyboard settings > Usage data", "Never your text",
            "password fields", "identifier", "a Junadé server", "nothing to turn off", "no telemetry",
        ),
        Locale(
            "values-fr", "sur votre appareil", "télécharger", "Paramètres du clavier > Données d", "Jamais votre texte",
            "champs de mot de passe", "identifiant", "serveur de Junadé", "rien à désactiver", "ni télémétrie",
        ),
    )

    @Test
    fun `les 5 sections ont chacune un titre et un texte`() {
        for (l in locales) {
            val strings = l.strings
            for (n in 1..5) {
                assertTrue(!strings["privacy_section_${n}_title"].isNullOrBlank(), "${l.folder} : titre $n")
                assertTrue(!strings["privacy_section_${n}_body"].isNullOrBlank(), "${l.folder} : texte $n")
            }
            assertEquals(5, Regex("privacy_section_\\d+_title").findAll(strings.keys.joinToString(" ")).count())
        }
        assertEquals(PrivacyContent.sections.size, 5)
    }

    @Test
    fun `le texte annonce le traitement local et le telechargement des modeles`() {
        for (l in locales) {
            assertTrue(l.strings["privacy_section_1_body"]!!.contains(l.onDevice), l.folder)
            assertTrue(l.strings["privacy_section_2_body"]!!.contains(l.download), l.folder)
        }
    }

    @Test
    fun `la mesure d usage Aptabase est annoncee et desactivable, sans promesse de zero telemetrie`() {
        for (l in locales) {
            val strings = l.strings
            val all = strings.filterKeys { it.startsWith("privacy_") }.values.joinToString(" ")
            // Aptabase est dans le projet : le texte doit le dire (sections 2 et 3), et ne plus prétendre qu'il n'y a rien à désactiver.
            assertTrue(strings["privacy_section_2_body"]!!.contains("Aptabase"), l.folder)
            assertTrue(strings["privacy_section_3_body"]!!.contains("Aptabase"), l.folder)
            assertFalse(all.contains(l.nothingToDisable), l.folder)
            assertFalse(all.contains(l.noTelemetry), l.folder)
            // Le texte renvoie à la bascule, qui doit exister dans l'écran des paramètres.
            assertTrue(strings["privacy_section_3_body"]!!.contains(l.settingsPath), l.folder)
            assertTrue(!strings["keyboard_settings_analytics_switch"].isNullOrBlank(), l.folder)
        }
    }

    @Test
    fun `le texte ne promet que ce que la mesure garantit`() {
        for (l in locales) {
            val body = l.strings["privacy_section_3_body"]!!
            assertTrue(body.contains(l.neverText), l.folder)
            assertTrue(body.contains(l.passwordFields), l.folder)
            assertTrue(body.contains(l.identifier), l.folder)
        }
    }

    @Test
    fun `chaque domaine de telechargement des modeles est cite dans le texte`() {
        val hosts = AiModel.entriesOrdered().mapNotNull { it.downloadUrl }.map { URI(it).host }.toSet()
        for (l in locales) {
            val body = l.strings["privacy_section_2_body"]!!
            hosts.forEach { host ->
                val mention = when {
                    host.endsWith("huggingface.co") -> "Hugging Face"
                    host.endsWith("workers.dev") -> l.ownServer
                    else -> fail("Domaine de téléchargement non couvert par le texte de confidentialité : $host")
                }
                assertTrue(body.contains(mention), "${l.folder} : le texte devrait citer « $mention » ($host)")
            }
        }
    }
}
