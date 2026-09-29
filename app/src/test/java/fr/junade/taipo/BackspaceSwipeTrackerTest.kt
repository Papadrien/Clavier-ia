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
    fun `le premier mot est supprime des l activation vers la gauche`() {
        val t = tracker()
        t.onDown(100f)
        assertEquals(1, t.onMove(75f))
        assertTrue(t.isActive)
    }

    @Test
    fun `un mot de plus par distance parcourue vers la gauche`() {
        val t = tracker()
        t.onDown(100f)
        t.onMove(75f) // active, 1er mot supprimé, ancre à 75
        assertEquals(0, t.onMove(60f))
        assertEquals(1, t.onMove(45f))
        assertEquals(2, t.onMove(-15f))
    }

    @Test
    fun `le reste de distance est conserve entre deux mouvements`() {
        val t = tracker()
        t.onDown(100f)
        t.onMove(70f) // active, 1er mot supprimé, ancre à 70
        assertEquals(1, t.onMove(35f)) // 35 px parcourus -> 1 mot, 5 px de reste (ancre à 40)
        assertEquals(0, t.onMove(40f)) // retour à l'ancre : rien
        assertEquals(1, t.onMove(0f)) // 40 px parcourus depuis l'ancre -> 1 mot de plus
    }

    @Test
    fun `changer de sens dans le meme geste ne supprime rien vers la droite`() {
        val t = tracker()
        t.onDown(100f)
        t.onMove(60f) // active, 1er mot supprimé, ancre à 60
        assertEquals(1, t.onMove(20f)) // encore 40 px à gauche -> 1 mot, ancre à 30
        assertEquals(0, t.onMove(50f)) // retour vers la droite -> rien
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
