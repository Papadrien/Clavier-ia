package fr.junade.taipo.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ModelRecommendationTest {

    private fun gb(value: Double) = (value * 1_000_000_000L).toLong()

    @Test
    fun `moins de 4 Go recommande le modele Leger`() {
        assertEquals(AiModel.LEGER, ModelRecommendation.recommended(gb(2.8)))
        assertEquals(AiModel.LEGER, ModelRecommendation.recommended(gb(3.7))) // téléphone « 4 Go »
        assertEquals(AiModel.LEGER, ModelRecommendation.recommended(ModelRecommendation.LIGHT_BELOW_BYTES - 1))
    }

    @Test
    fun `milieu de gamme recommande Equilibre`() {
        assertEquals(AiModel.EQUILIBRE, ModelRecommendation.recommended(ModelRecommendation.LIGHT_BELOW_BYTES))
        assertEquals(AiModel.EQUILIBRE, ModelRecommendation.recommended(gb(5.5))) // téléphone « 6 Go »
    }

    @Test
    fun `haut de gamme recommande Performant`() {
        assertEquals(AiModel.PERFORMANT, ModelRecommendation.recommended(gb(7.3))) // téléphone « 8 Go »
        assertEquals(AiModel.PERFORMANT, ModelRecommendation.recommended(gb(11.2))) // Pixel 9
    }

    @Test
    fun `le modele recommande est toujours un modele du catalogue`() {
        listOf(0L, gb(1.0), gb(4.0), gb(7.0), gb(64.0)).forEach {
            assertEquals(true, ModelRecommendation.recommended(it) in AiModel.entriesOrdered())
        }
    }
}
