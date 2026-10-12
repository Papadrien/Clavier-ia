package fr.junade.taipo

import org.junit.Assert.assertEquals
import org.junit.Test

/** Dévoilement de la barre du haut : calculs purs (rognage du rideau, glissement, cascade). */
class BarRevealTest {

    @Test
    fun `le rideau part du bord gauche et s'ouvre vers la droite`() {
        assertEquals(0 to 0, BarReveal.visibleSpan(100, 0f, fromEnd = false))
        assertEquals(0 to 50, BarReveal.visibleSpan(100, 0.5f, fromEnd = false))
        assertEquals(0 to 100, BarReveal.visibleSpan(100, 1f, fromEnd = false))
    }

    @Test
    fun `le rideau part du bord droit quand le bouton touche est a droite`() {
        assertEquals(100 to 100, BarReveal.visibleSpan(100, 0f, fromEnd = true))
        assertEquals(50 to 100, BarReveal.visibleSpan(100, 0.5f, fromEnd = true))
        assertEquals(0 to 100, BarReveal.visibleSpan(100, 1f, fromEnd = true))
    }

    @Test
    fun `l'avancement hors bornes est ramene entre 0 et 1`() {
        assertEquals(0 to 100, BarReveal.visibleSpan(100, 1.4f, fromEnd = false))
        assertEquals(0 to 0, BarReveal.visibleSpan(100, -0.2f, fromEnd = false))
    }

    @Test
    fun `le glissement vient du cote du bouton touche et s'annule a l'arrivee`() {
        assertEquals(-18f, BarReveal.slideOffset(18f, 0f, fromEnd = false), 0.001f)
        assertEquals(18f, BarReveal.slideOffset(18f, 0f, fromEnd = true), 0.001f)
        assertEquals(0f, BarReveal.slideOffset(18f, 1f, fromEnd = false), 0.001f)
        assertEquals(9f, BarReveal.slideOffset(18f, 0.5f, fromEnd = true), 0.001f)
    }

    @Test
    fun `la cascade decale chaque bouton de 30 ms apres un premier delai de 40 ms`() {
        assertEquals(40L, BarReveal.startDelay(0, full = true))
        assertEquals(100L, BarReveal.startDelay(2, full = true))
    }

    @Test
    fun `le mode discret n'a pas de decalage`() {
        assertEquals(0L, BarReveal.startDelay(3, full = false))
    }
}
