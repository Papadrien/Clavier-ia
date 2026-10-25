package fr.junade.taipo.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Les préférences vocales dépendent d'Android (SharedPreferences) : leur logique de mise à jour passe par
 * [ModelUpdate], déjà testé ; on vérifie ici qu'elle s'applique bien aux fichiers du modèle vocal.
 */
class VoiceModelPreferencesLogicTest {

    @Test
    fun `un fichier vocal telecharge dont l empreinte change est a mettre a jour`() {
        assertTrue(ModelUpdate.isAvailable(downloaded = true, installedSha256 = "a".repeat(64), catalogSha256 = "b".repeat(64)))
        assertFalse(ModelUpdate.isAvailable(downloaded = true, installedSha256 = "a".repeat(64), catalogSha256 = "A".repeat(64)))
    }

    @Test
    fun `sans empreinte au catalogue aucune fausse alerte de mise a jour`() {
        assertFalse(ModelUpdate.isAvailable(downloaded = true, installedSha256 = "a".repeat(64), catalogSha256 = null))
    }

    @Test
    fun `statut d accueil du modele vocal selon les fichiers presents`() {
        val total = VoiceModelFile.all().size
        assertEquals(ModelStatus.MISSING, ModelStatus.forVoiceModel(0, total))
        assertEquals(ModelStatus.INCOMPLETE, ModelStatus.forVoiceModel(total - 1, total))
        assertEquals(ModelStatus.READY, ModelStatus.forVoiceModel(total, total))
    }
}
