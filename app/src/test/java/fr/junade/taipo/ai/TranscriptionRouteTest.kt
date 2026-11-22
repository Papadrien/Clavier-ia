package fr.junade.taipo.ai

import fr.junade.taipo.model.AiModel
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Transcription IA (22/11/2026) : Gemma 3 et Gemma 4 passent par la dictée puis la reformulation ; audio natif seulement sur demande. */
class TranscriptionRouteTest {

    @Test
    fun `les modeles du catalogue passent par la dictee`() {
        for (model in listOf(AiModel.ULTRA_LEGER, AiModel.LEGER, AiModel.EQUILIBRE, AiModel.PERFORMANT)) {
            assertFalse(TranscriptionRoute.nativeAudio(model, customAcceptsAudio = true), model.id)
        }
    }

    @Test
    fun `le modele personnalise garde son reglage audio`() {
        assertTrue(TranscriptionRoute.nativeAudio(AiModel.CUSTOM, customAcceptsAudio = true))
        assertFalse(TranscriptionRoute.nativeAudio(AiModel.CUSTOM, customAcceptsAudio = false))
    }
}
