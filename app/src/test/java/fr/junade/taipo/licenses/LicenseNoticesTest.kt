package fr.junade.taipo.licenses

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/** Audit 1.9 : mentions de licence de l'écran « Licences » (modèle vocal, dictionnaires, bibliothèques, police). */
class LicenseNoticesTest {

    private val mainDir: File = listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory }
        ?: fail("Dossier src/main introuvable")

    @Test
    fun `les identifiants sont uniques`() {
        val ids = LicenseNotices.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `chaque rubrique a au moins une mention`() {
        LicenseSection.entries.forEach { assertTrue(LicenseNotices.inSection(it).isNotEmpty(), "Rubrique vide : $it") }
    }

    @Test
    fun `noms, licences et liens sont renseignes et en HTTPS`() {
        LicenseNotices.all.forEach {
            assertTrue(it.name.isNotBlank() && it.licenseName.isNotBlank(), "Nom ou licence vide : ${it.id}")
            assertTrue(it.projectUrl.startsWith("https://"), "Lien projet non HTTPS : ${it.id}")
            assertTrue(it.licenseUrl.startsWith("https://"), "Lien licence non HTTPS : ${it.id}")
        }
    }

    @Test
    fun `les composants attendus sont tous mentionnes`() {
        val ids = LicenseNotices.all.map { it.id }.toSet()
        listOf("nemotron", "frequencywords", "litertlm", "sherpa-onnx", "onnxruntime", "sqlcipher", "aptabase", "opensans")
            .forEach { assertTrue(it in ids, "Mention manquante : $it") }
    }

    @Test
    fun `la mention des dictionnaires cite la licence et les modifications`() {
        val notice = LicenseNotices.all.first { it.id == "frequencywords" }
        assertTrue(notice.licenseName.contains("CC BY-SA 4.0"))
        assertTrue(notice.noticeRes != null, "Les modifications apportées aux listes doivent être indiquées (CC BY-SA)")
    }

    @Test
    fun `le texte de la licence de la police est embarque`() {
        val text = File(mainDir, LicenseNotices.OFL_ASSET).readText()
        assertTrue(text.contains("SIL OPEN FONT LICENSE Version 1.1"))
        assertTrue(text.contains("Open Sans Project Authors"))
    }

    @Test
    fun `les textes de l ecran existent en anglais et en francais`() {
        fun keys(dir: String) = Regex("""<string name="(licenses_[a-z0-9_]+)"""")
            .findAll(File(mainDir, "res/$dir/strings.xml").readText()).map { it.groupValues[1] }.toSet()
        assertEquals(keys("values"), keys("values-fr"))
    }
}
