package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class InlineSuggestionColorsTest {

    private val darkTitle = 0xFFFFFFFF.toInt()
    private val darkSubtitle = 0x99FFFFFF.toInt()

    @Test
    fun `theme sombre, fond sombre et textes de la palette du clavier`() {
        val colors = InlineSuggestionColors.forMode(KeyboardTheme.Mode.DARK, darkTitle, darkSubtitle)
        assertEquals(R.drawable.bg_inline_chip_dark, colors.chipBackground)
        assertEquals(darkTitle, colors.title)
        assertEquals(darkSubtitle, colors.subtitle)
    }

    @Test
    fun `theme clair, fond clair et textes sombres`() {
        val colors = InlineSuggestionColors.forMode(KeyboardTheme.Mode.LIGHT, darkTitle, darkSubtitle)
        assertEquals(R.drawable.bg_inline_chip_light, colors.chipBackground)
        // Les textes du thème sombre (blancs) ne doivent pas être repris sur le fond clair.
        assertNotEquals(darkTitle, colors.title)
        assertNotEquals(darkSubtitle, colors.subtitle)
    }
}
