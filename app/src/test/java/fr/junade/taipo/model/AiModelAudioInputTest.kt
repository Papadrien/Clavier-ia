package fr.junade.taipo.model

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Épopée 14 : seuls les deux modèles Gemma 4 ont une entrée audio (documentation Google). */
class AiModelAudioInputTest {

    @Test
    fun `Equilibre et Performant acceptent l'audio`() {
        assertTrue(AiModel.EQUILIBRE.hasAudioInput)
        assertTrue(AiModel.PERFORMANT.hasAudioInput)
    }

    @Test
    fun `Ultra-leger et Leger (Gemma 3) n'ont pas d'entree audio`() {
        assertFalse(AiModel.ULTRA_LEGER.hasAudioInput)
        assertFalse(AiModel.LEGER.hasAudioInput)
    }
}
