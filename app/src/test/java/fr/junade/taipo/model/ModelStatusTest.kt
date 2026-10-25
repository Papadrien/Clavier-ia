package fr.junade.taipo.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Lot UX 4 : statut des modèles sur l'accueil. */
class ModelStatusTest {

    @Test
    fun `modele IA avec fichier est pret`() {
        assertEquals(ModelStatus.READY, ModelStatus.forTextModel(fileProvided = true))
    }

    @Test
    fun `modele IA sans fichier ou sans modele actif est a fournir`() {
        assertEquals(ModelStatus.MISSING, ModelStatus.forTextModel(fileProvided = false))
    }

    @Test
    fun `modele vocal sans fichier est a fournir`() {
        assertEquals(ModelStatus.MISSING, ModelStatus.forVoiceModel(provided = 0, total = 4))
    }

    @Test
    fun `modele vocal partiel est incomplet`() {
        assertEquals(ModelStatus.INCOMPLETE, ModelStatus.forVoiceModel(provided = 1, total = 4))
        assertEquals(ModelStatus.INCOMPLETE, ModelStatus.forVoiceModel(provided = 3, total = 4))
    }

    @Test
    fun `modele vocal complet est pret`() {
        assertEquals(ModelStatus.READY, ModelStatus.forVoiceModel(provided = 4, total = 4))
    }

    @Test
    fun `total invalide est traite comme a fournir`() {
        assertEquals(ModelStatus.MISSING, ModelStatus.forVoiceModel(provided = 2, total = 0))
    }
}
