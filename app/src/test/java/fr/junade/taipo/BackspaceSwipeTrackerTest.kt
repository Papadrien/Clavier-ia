package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackspaceSwipeTrackerTest {

    // Activation à 20 px, un mot tous les 30 px.
    private fun tracker() = BackspaceSwipeTracker(activationPx = 20f, stepPx = 30f)

    @Test
    fun `un simple appui ou un leger tremblement ne supprime rien`() {
        val t = tracker()
        t.onDown(100f)
        assertEquals(0, t.onMove(95f))
        assertEquals(0, t.onMove(110f))
        assertFalse(t.isActive)
    }

    @Test
    fun `glisser vers la droite ne supprime rien`() {
        val t = tracker()
        t.onDown(100f)
        assertEquals(0, t.onMove(150f))
        assertFalse(t.isActive)
    }

    @Test
    fun `le premier mot est selectionne des l activation vers la gauche`() {
        val t = tracker()
        t.onDown(100f)
        assertEquals(1, t.onMove(80f))
        assertTrue(t.isActive)
    }

    @Test
    fun `un mot de plus par pas parcouru vers la gauche`() {
        val t = tracker()
        t.onDown(100f)
        assertEquals(1, t.onMove(75f))
        assertEquals(1, t.onMove(55f)) // 45 px : activation (20) + 25 px, pas encore un 2e pas
        assertEquals(2, t.onMove(50f)) // 50 px : activation + 1 pas
        assertEquals(3, t.onMove(20f)) // 80 px : activation + 2 pas
    }

    @Test
    fun `revenir vers la droite diminue le nombre de mots`() {
        val t = tracker()
        t.onDown(100f)
        assertEquals(3, t.onMove(20f))
        assertEquals(2, t.onMove(50f))
        assertEquals(1, t.onMove(75f))
        assertTrue(t.isActive)
    }

    @Test
    fun `revenir sous le seuil d activation annule la selection mais le geste reste actif`() {
        val t = tracker()
        t.onDown(100f)
        assertEquals(1, t.onMove(70f))
        assertEquals(0, t.onMove(95f))
        assertTrue(t.isActive) // le relâchement ne doit pas effacer un caractère
        assertEquals(1, t.onMove(70f)) // et on peut repartir vers la gauche
    }

    @Test
    fun `reset et nouvel appui repartent de zero`() {
        val t = tracker()
        t.onDown(100f)
        t.onMove(70f)
        assertTrue(t.isActive)
        t.onDown(50f)
        assertFalse(t.isActive)
        t.reset()
        assertFalse(t.isActive)
    }
}
