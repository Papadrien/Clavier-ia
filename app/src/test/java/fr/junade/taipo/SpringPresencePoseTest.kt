package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Apparition et disparition en ressort des barres et de la zone de chat (calculs purs). */
class SpringPresencePoseTest {

    @Test
    fun `a sa place la vue n'est ni decalee ni transparente`() {
        assertEquals(0f, SpringPresencePose.translationY(1f, 40f))
        assertEquals(1f, SpringPresencePose.alpha(1f))
    }

    @Test
    fun `au depart la vue est en bas et transparente`() {
        assertEquals(40f, SpringPresencePose.translationY(0f, 40f))
        assertEquals(0f, SpringPresencePose.alpha(0f))
    }

    @Test
    fun `le depassement du ressort remonte la vue sans rendre l'opacite superieure a 1`() {
        assertTrue(SpringPresencePose.translationY(1.2f, 40f) < 0f)
        assertEquals(1f, SpringPresencePose.alpha(1.2f))
    }

    @Test
    fun `l'opacite est pleine des la moitie de la course`() {
        assertEquals(1f, SpringPresencePose.alpha(0.5f))
        assertTrue(SpringPresencePose.alpha(0.25f) in 0.1f..0.9f)
    }

    @Test
    fun `un ressort lance vers le bas atteint zero en moins de la duree maximale`() {
        val spring = SpringValue(1f)
        var elapsed = 0f
        val dt = 1f / 60f
        while (!SpringPresencePose.exitFinished(spring.value, elapsed) && elapsed < 2f) {
            spring.step(0f, dt * SpringPresencePose.EXIT_TIME_SCALE)
            elapsed += dt
        }
        assertTrue(spring.value <= 0f, "la vue doit avoir retrouvé le bas")
        assertTrue(elapsed < SpringPresencePose.EXIT_MAX_S, "sortie trop longue : $elapsed s")
        assertTrue(elapsed > 0.05f, "sortie trop brève pour se lire : $elapsed s")
    }

    @Test
    fun `la sortie se termine aussi au garde-fou de duree`() {
        assertFalse(SpringPresencePose.exitFinished(0.4f, 0.1f))
        assertTrue(SpringPresencePose.exitFinished(0.4f, SpringPresencePose.EXIT_MAX_S))
        assertTrue(SpringPresencePose.exitFinished(0f, 0f))
    }
}
