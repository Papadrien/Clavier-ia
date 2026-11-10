package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FloatingPlacementTest {
    private val area = FloatingArea(left = 0, top = 100, right = 1000, bottom = 2100)

    @Test
    fun `la largeur du clavier flottant reste dans ses bornes, independamment du mode une main`() {
        assertEquals(FloatingWidth.MIN_PERCENT, FloatingWidth.clamp(10))
        assertEquals(FloatingWidth.MAX_PERCENT, FloatingWidth.clamp(200))
        assertEquals(60, FloatingWidth.clamp(60))
    }

    @Test
    fun `une position hors bornes ou invalide est ramenee dans l'ecran`() {
        assertEquals(FloatingPosition.of(1f, 0f), FloatingPosition.of(3f, -2f))
        assertEquals(FloatingPosition.DEFAULT, FloatingPosition.of(Float.NaN, Float.NaN))
    }

    @Test
    fun `la carte est placee dans l'espace libre, sans jamais sortir de la zone`() {
        assertEquals(FloatingOffset(100, 600), FloatingPlacement.place(FloatingPosition.of(0.5f, 0.5f), 800, 1000, area))
        assertEquals(FloatingOffset(0, 100), FloatingPlacement.place(FloatingPosition.of(0f, 0f), 800, 1000, area))
        assertEquals(FloatingOffset(200, 1100), FloatingPlacement.place(FloatingPosition.of(1f, 1f), 800, 1000, area))
        // Carte plus large que la zone : collée au bord gauche de la zone.
        assertEquals(0, FloatingPlacement.place(FloatingPosition.of(1f, 1f), 1200, 1000, area).left)
    }

    @Test
    fun `le glissement deplace la carte sous le doigt et s'arrete aux bords`() {
        val start = FloatingPosition.of(0.5f, 0.5f)
        assertEquals(FloatingPosition.of(1f, 0.25f), FloatingPlacement.dragged(start, 100f, -250f, 800, 1000, area))
        assertEquals(FloatingPosition.of(0f, 1f), FloatingPlacement.dragged(start, -5000f, 5000f, 800, 1000, area))
        // Retour d'un bord dans le même geste : la position est recalculée depuis le début du geste.
        assertEquals(start, FloatingPlacement.dragged(start, 0f, 0f, 800, 1000, area))
    }
}
