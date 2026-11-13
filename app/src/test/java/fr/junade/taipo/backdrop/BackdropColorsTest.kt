package fr.junade.taipo.backdrop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BackdropColorsTest {

    @Test
    fun `dans la bande des bleus, le second ton s'ecarte davantage`() {
        // Bleu vif (#0A66E8, teinte ~215°) : ±28° restait bleu, l'écart est élargi.
        assertEquals(28f * BackdropColors.BLUE_BAND_WIDENING, BackdropColors.widenedShift(215f, 28f), 0.001f)
        assertEquals(-28f * BackdropColors.BLUE_BAND_WIDENING, BackdropColors.widenedShift(215f, -28f), 0.001f)
    }

    @Test
    fun `hors de la bande des bleus, le decalage est inchange`() {
        for (hue in listOf(0f, 17f, 142f, 174f, 273f, 310f)) { // ambre, émeraude, lagon, violet, magenta…
            assertEquals(28f, BackdropColors.widenedShift(hue, 28f), 0.001f)
            assertEquals(-40f, BackdropColors.widenedShift(hue, -40f), 0.001f)
        }
    }
}
