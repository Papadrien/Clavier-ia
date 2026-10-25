package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeyboardWidthTest {

    private val density = 2f

    @Test
    fun `sous 600 dp la classe est Compact`() {
        assertEquals(WidthClass.COMPACT, KeyboardWidth.widthClass(360f))
        assertEquals(WidthClass.COMPACT, KeyboardWidth.widthClass(599.9f))
    }

    @Test
    fun `a partir de 600 dp la classe est large`() {
        assertEquals(WidthClass.WIDE, KeyboardWidth.widthClass(600f))
        assertEquals(WidthClass.WIDE, KeyboardWidth.widthClass(840f))
    }

    @Test
    fun `en Compact le clavier occupe toute la largeur`() {
        val w = KeyboardWidth.forAvailableWidth(availableWidthPx = 720f, density = density) // 360 dp
        assertEquals(0f, w.leftPx)
        assertEquals(720f, w.widthPx)
        assertEquals(720f, w.rightPx)
    }

    @Test
    fun `juste sous le seuil le clavier reste plein ecran`() {
        val w = KeyboardWidth.forAvailableWidth(availableWidthPx = 1199f, density = density) // 599,5 dp
        assertEquals(0f, w.leftPx)
        assertEquals(1199f, w.widthPx)
    }

    @Test
    fun `en large sous le plafond le clavier reste plein ecran`() {
        val w = KeyboardWidth.forAvailableWidth(availableWidthPx = 1346f, density = density) // 673 dp
        assertEquals(0f, w.leftPx)
        assertEquals(1346f, w.widthPx)
    }

    @Test
    fun `en large au dessus du plafond la largeur est plafonnee et centree`() {
        val w = KeyboardWidth.forAvailableWidth(availableWidthPx = 2000f, density = density) // 1000 dp
        assertEquals(KeyboardWidth.MAX_WIDTH_DP * density, w.widthPx)
        assertEquals((2000f - w.widthPx) / 2f, w.leftPx)
        // Marges symétriques.
        assertEquals(w.leftPx, 2000f - w.rightPx)
    }

    @Test
    fun `la largeur du plafond depend de la densite`() {
        val w = KeyboardWidth.forAvailableWidth(availableWidthPx = 3000f, density = 3f) // 1000 dp
        assertEquals(KeyboardWidth.MAX_WIDTH_DP * 3f, w.widthPx)
    }

    @Test
    fun `une largeur nulle ou une densite invalide ne plantent pas`() {
        assertEquals(0f, KeyboardWidth.forAvailableWidth(0f, density).widthPx)
        assertEquals(0f, KeyboardWidth.forAvailableWidth(-10f, density).widthPx)
        val w = KeyboardWidth.forAvailableWidth(500f, 0f)
        assertEquals(0f, w.leftPx)
        assertEquals(500f, w.widthPx)
    }

    @Test
    fun `les touches ne depassent jamais la vue`() {
        listOf(300f, 599f, 600f, 720f, 841f, 1280f, 2000f).forEach { dp ->
            val w = KeyboardWidth.forAvailableWidth(dp * density, density)
            assertTrue(w.leftPx >= 0f)
            assertTrue(w.rightPx <= dp * density + 0.001f)
        }
    }
}
