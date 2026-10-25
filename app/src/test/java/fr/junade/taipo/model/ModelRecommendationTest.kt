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
        assertEquals(AiModel.EQUILIBRE, ModelRecommendation.recommended(gb(7.3))) // téléphone « 8 Go »
        assertEquals(AiModel.EQUILIBRE, ModelRecommendation.recommended(gb(9.4))) // téléphone « 10 Go »
        assertEquals(AiModel.EQUILIBRE, ModelRecommendation.recommended(gb(11.2))) // Pixel 9 (12 Go)
        assertEquals(
            AiModel.EQUILIBRE,
            ModelRecommendation.recommended(ModelRecommendation.PERFORMANT_FROM_BYTES - 1),
        )
    }

    @Test
    fun `au-dessus de 12 Go recommande Performant`() {
        assertEquals(AiModel.PERFORMANT, ModelRecommendation.recommended(ModelRecommendation.PERFORMANT_FROM_BYTES))
        assertEquals(AiModel.PERFORMANT, ModelRecommendation.recommended(gb(15.0))) // téléphone « 16 Go »
    }

    @Test
    fun `le modele recommande est toujours un modele du catalogue`() {
        listOf(0L, gb(1.0), gb(4.0), gb(13.0), gb(64.0)).forEach {
            assertEquals(true, ModelRecommendation.recommended(it) in AiModel.entriesOrdered())
        }
    }
}
