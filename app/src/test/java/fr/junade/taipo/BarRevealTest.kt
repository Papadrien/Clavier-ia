package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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

    @Test
    fun `le ressort part de 0 et se pose sur 1`() {
        assertEquals(0f, BarReveal.springProgress(0f), 0.0001f)
        assertEquals(1f, BarReveal.springProgress(1f), 0.01f)
    }

    @Test
    fun `le ressort depasse 1 avant de se poser`() {
        val peak = (0..100).maxOf { BarReveal.springProgress(it / 100f) }
        assertTrue(peak > 1.05f && peak < 1.3f, "dépassement attendu entre 5 % et 30 %, obtenu $peak")
    }

    @Test
    fun `l'avancement du ressort hors bornes est ramene entre 0 et 1`() {
        assertEquals(BarReveal.springProgress(1f), BarReveal.springProgress(3f), 0.0001f)
        assertEquals(0f, BarReveal.springProgress(-1f), 0.0001f)
    }

    @Test
    fun `la cascade du ressort decale chaque bouton de 45 ms sans premier delai`() {
        assertEquals(0L, BarReveal.springStartDelay(0))
        assertEquals(90L, BarReveal.springStartDelay(2))
    }

    @Test
    fun `le glissement en ressort part de l ecart et se pose sur zero`() {
        assertEquals(-40f, BarReveal.glideOffset(-40f, 0f), 0.001f)
        assertEquals(0f, BarReveal.glideOffset(-40f, 1f), 0.5f)
        assertEquals(0f, BarReveal.glideOffset(0f, 0.5f), 0.001f)
    }

    @Test
    fun `le glissement en ressort depasse sa destination avant de s y poser`() {
        // Parti de la gauche (négatif), le bouton dépasse d'abord sa place (valeur positive) avant de se poser.
        val overshoot = (0..100).maxOf { BarReveal.glideOffset(-40f, it / 100f) }
        assertTrue(overshoot > 1f, "dépassement attendu, obtenu $overshoot")
    }
}
