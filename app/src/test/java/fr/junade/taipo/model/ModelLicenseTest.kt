package fr.junade.taipo.model

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/** Story 8.11 : licence de chaque modèle, mention avant téléchargement et texte Apache 2.0 embarqué. */
class ModelLicenseTest {

    @Test
    fun `Gemma 3 270M et 1B sont sous les conditions Gemma`() {
        assertEquals(ModelLicense.GEMMA_TERMS, AiModel.ULTRA_LEGER.license)
        assertEquals(ModelLicense.GEMMA_TERMS, AiModel.LEGER.license)
    }

    @Test
    fun `Gemma 4 E2B et E4B sont sous Apache 2`() {
        assertEquals(ModelLicense.APACHE_2, AiModel.EQUILIBRE.license)
        assertEquals(ModelLicense.APACHE_2, AiModel.PERFORMANT.license)
    }

    @Test
    fun `les conditions Gemma pointent vers les pages Google en HTTPS`() {
        assertEquals("https://ai.google.dev/gemma/terms", ModelLicense.GEMMA_TERMS.termsUrl)
        assertEquals("https://ai.google.dev/gemma/prohibited_use_policy", ModelLicense.GEMMA_TERMS.policyUrl)
    }

    @Test
    fun `Apache 2 n a pas de politique d usage ni d acceptation prealable`() {
        assertNull(ModelLicense.APACHE_2.policyUrl)
        assertFalse(ModelLicense.APACHE_2.requiresAcceptance)
        assertEquals("https://ai.google.dev/gemma/apache_2", ModelLicense.APACHE_2.termsUrl)
    }

    @Test
    fun `la mention Gemma s affiche tant qu elle n est pas acceptee, puis plus`() {
        assertTrue(ModelLicense.GEMMA_TERMS.mustAskBeforeDownload(alreadyAccepted = false))
        assertFalse(ModelLicense.GEMMA_TERMS.mustAskBeforeDownload(alreadyAccepted = true))
    }

    @Test
    fun `aucune mention prealable pour un modele Apache 2`() {
        assertFalse(ModelLicense.APACHE_2.mustAskBeforeDownload(alreadyAccepted = false))
    }

    @Test
    fun `chaque modele du catalogue a une licence`() {
        AiModel.entriesOrdered().forEach { assertNotNull(it.license) }
    }

    @Test
    fun `le texte complet d Apache 2 est embarque dans les assets`() {
        val dir = listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory }
            ?: fail("Dossier src/main introuvable")
        val text = File(dir, "assets/licenses/apache-2.0.txt").readText()
        assertTrue(text.contains("Apache License"))
        assertTrue(text.contains("Version 2.0, January 2004"))
        assertTrue(text.contains("END OF TERMS AND CONDITIONS"))
    }
}
