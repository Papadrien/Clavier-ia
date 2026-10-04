package fr.junade.taipo

import fr.junade.taipo.clipboard.ClipboardDatabase
import fr.junade.taipo.dictionary.DatabasePassphraseProvider
import fr.junade.taipo.dictionary.PersonalDictionaryDatabase
import fr.junade.taipo.model.ModelFileResolver
import fr.junade.taipo.model.VoiceModelFileResolver
import fr.junade.taipo.suggestion.NextWordRepository
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

/**
 * Lot 3.4 (S2) : les règles de sauvegarde doivent exclure tout ce qui est chiffré par une clé Keystore
 * (illisible après restauration sur un autre appareil) et les modèles IA (plusieurs Go, au-delà du
 * plafond de la sauvegarde automatique). Les noms viennent des constantes du code : ajouter un fichier
 * sensible sans mettre à jour les règles ne se détecte que s'il est ajouté ici ; un renommage dans le
 * code, lui, fait échouer le test.
 *
 * Les trois sections (Android 6-11, cloud 12+, transfert d'appareil 12+) doivent être identiques sur
 * ces exclusions : elles s'appliquent à des versions et des scénarios différents.
 */
class BackupRulesTest {

    private class Exclusion(val domain: String, val path: String)

    private fun resource(relative: String): File {
        val candidates = listOf(File("src/main/$relative"), File("app/src/main/$relative"))
        return candidates.firstOrNull { it.isFile } ?: fail("Fichier introuvable : $relative (cherché dans $candidates)")
    }

    private fun parse(relative: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(resource(relative)).documentElement

    private fun exclusionsIn(section: Element): List<Exclusion> {
        val nodes = section.getElementsByTagName("exclude")
        return (0 until nodes.length).map {
            val element = nodes.item(it) as Element
            Exclusion(element.getAttribute("domain"), element.getAttribute("path").trimEnd('/'))
        }
    }

    private fun section(root: Element, tag: String): Element {
        val nodes = root.getElementsByTagName(tag)
        assertEquals(1, nodes.length, "section <$tag> attendue une fois")
        return nodes.item(0) as Element
    }

    private fun required(): List<Exclusion> {
        val databases = listOf(ClipboardDatabase.NAME, PersonalDictionaryDatabase.NAME).flatMap { name ->
            listOf(name, "$name-journal", "$name-wal", "$name-shm").map { Exclusion("database", it) }
        }
        val keyPrefs = listOf(
            DatabasePassphraseProvider.PREFS_NAME,
            ClipboardDatabase.KEY_PREFS_NAME,
            NextWordRepository.KEY_PREFS_NAME,
        ).map { Exclusion("sharedpref", "$it.xml") }
        val files = listOf(
            Exclusion("file", NextWordRepository.FILE_NAME),
            Exclusion("file", NextWordRepository.FILE_NAME + ".tmp"),
            Exclusion("file", ModelFileResolver.DIRECTORY_NAME),
            Exclusion("file", VoiceModelFileResolver.DIRECTORY_NAME),
        )
        return databases + keyPrefs + files
    }

    private fun assertContainsAll(sectionName: String, actual: List<Exclusion>) {
        val present = actual.map { it.domain to it.path }.toSet()
        val missing = required().filterNot { (it.domain to it.path) in present }
        assertTrue(missing.isEmpty(), "$sectionName : exclusions manquantes : ${missing.map { "${it.domain}:${it.path}" }}")
    }

    @Test
    fun `sauvegarde automatique Android 6 a 11`() {
        assertContainsAll("backup_rules.xml", exclusionsIn(parse("res/xml/backup_rules.xml")))
    }

    @Test
    fun `sauvegarde cloud Android 12 et plus`() {
        assertContainsAll("cloud-backup", exclusionsIn(section(parse("res/xml/data_extraction_rules.xml"), "cloud-backup")))
    }

    @Test
    fun `transfert d'appareil Android 12 et plus`() {
        assertContainsAll("device-transfer", exclusionsIn(section(parse("res/xml/data_extraction_rules.xml"), "device-transfer")))
    }

    @Test
    fun `le manifeste declare la sauvegarde explicitement et ses deux fichiers de regles`() {
        val application = parse("AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        assertEquals("true", application.getAttribute("android:allowBackup"), "android:allowBackup doit être déclaré")
        assertEquals("@xml/backup_rules", application.getAttribute("android:fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", application.getAttribute("android:dataExtractionRules"))
    }

    @Test
    fun `decision D5 - le clavier n'est pas directBootAware`() {
        val manifest = parse("AndroidManifest.xml")
        for (tag in listOf("application", "service", "activity")) {
            val nodes = manifest.getElementsByTagName(tag)
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as Element
                assertFalse(
                    element.getAttribute("android:directBootAware") == "true",
                    "<$tag ${element.getAttribute("android:name")}> est directBootAware : voir docs/securite.md (D5) avant de changer",
                )
            }
        }
    }
}
