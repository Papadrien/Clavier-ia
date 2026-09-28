package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpaceSwipeTrackerTest {

    // Activation à 16 px, un caractère tous les 10 px.
    private fun tracker() = SpaceSwipeTracker(activationPx = 16f, stepPx = 10f)

    @Test
    fun `un simple appui ou un leger tremblement ne deplace pas le curseur`() {
        val t = tracker()
        t.onDown(100f)
        assertEquals(0, t.onMove(105f))
        assertEquals(0, t.onMove(92f))
        assertFalse(t.isActive)
    }

    @Test
    fun `le glissement s active apres la distance d activation sans sauter`() {
        val t = tracker()
        t.onDown(100f)
        assertEquals(0, t.onMove(120f))
        assertTrue(t.isActive)
    }

    @Test
    fun `un pas de curseur par distance parcourue vers la droite`() {
        val t = tracker()
        t.onDown(100f)
        t.onMove(120f) // active, ancre à 120
        assertEquals(0, t.onMove(125f))
        assertEquals(1, t.onMove(130f))
        assertEquals(2, t.onMove(150f))
    }

    @Test
    fun `glisser vers la gauche donne des pas negatifs`() {
        val t = tracker()
        t.onDown(100f)
        t.onMove(80f)
        assertEquals(-1, t.onMove(70f))
        assertEquals(-3, t.onMove(40f))
    }

    @Test
    fun `le reste de distance est conserve entre deux mouvements`() {
        val t = tracker()
        t.onDown(0f)
        t.onMove(20f)
        assertEquals(1, t.onMove(34f)) // 14 px -> 1 pas, 4 px de reste
        assertEquals(1, t.onMove(46f)) // 4 + 12 = 16 px -> 1 pas
    }

    @Test
    fun `changer de sens dans le meme geste fonctionne`() {
        val t = tracker()
        t.onDown(100f)
        t.onMove(120f)
        assertEquals(2, t.onMove(140f))
        assertEquals(-2, t.onMove(120f))
    }

    @Test
    fun `reset et nouvel appui repartent de zero`() {
        val t = tracker()
        t.onDown(100f)
        t.onMove(130f)
        assertTrue(t.isActive)
        t.onDown(50f)
        assertFalse(t.isActive)
        assertEquals(0, t.onMove(55f))
        t.reset()
        assertFalse(t.isActive)
    }
}
