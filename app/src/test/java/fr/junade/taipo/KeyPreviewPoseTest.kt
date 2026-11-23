package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Bulle des touches animée (16/11/2026) : entrée, affichage d'au moins 200 ms depuis l'appui, sortie, selon le style d'animation. */
class KeyPreviewPoseTest {

    private val style = MotionStyle.SPRING
    private val settled = 2_000L

    @Test
    fun `la bulle reste au moins 200 ms a l ecran depuis l appui`() {
        assertEquals(200L, KeyPreviewPose.MIN_SHOW_MS)
    }

    @Test
    fun `touche maintenue, la bulle est posee a sa place quelle que soit l animation`() {
        KeyPreviewAnimation.entries.forEach { a ->
            val p = KeyPreviewPose.pose(a, style, settled, sinceExitMs = -1)!!
            assertEquals(1f, p.scaleX, 0.01f, "$a")
            assertEquals(1f, p.scaleY, 0.01f, "$a")
            assertEquals(0f, p.offsetY, 0.01f, "$a")
            assertEquals(1f, p.alpha, 0.01f, "$a")
            assertEquals(1f, p.grow, 0.01f, "$a")
        }
    }

    @Test
    fun `la sortie se termine apres sa duree, pas avant`() {
        MotionStyle.entries.forEach { s ->
            KeyPreviewAnimation.entries.forEach { a ->
                val exit = KeyPreviewPose.exitDurationMs(a, s)
                assertNotNull(KeyPreviewPose.pose(a, s, settled, exit - 1), "$a $s")
                assertNull(KeyPreviewPose.pose(a, s, settled, exit), "$a $s")
            }
        }
    }

    @Test
    fun `animations coupees, la bulle est immobile puis disparait d un coup`() {
        KeyPreviewAnimation.entries.forEach { a ->
            assertEquals(KeyPreviewPose.REST, KeyPreviewPose.pose(a, style, 0L, -1L, animated = false))
            assertNull(KeyPreviewPose.pose(a, style, 0L, 0L, animated = false))
        }
    }

    @Test
    fun `ressort, la bulle sort de la touche petite et transparente, puis depasse sa taille`() {
        val start = KeyPreviewPose.pose(KeyPreviewAnimation.SPRING, style, 0L, -1L)!!
        assertEquals(0.55f, start.scaleX, 0.01f)
        assertEquals(0f, start.alpha, 0.01f)
        assertTrue(start.offsetY > 0.5f, "part de la touche, plus bas")
        val peak = (0L..200L).maxOf { KeyPreviewPose.pose(KeyPreviewAnimation.SPRING, style, it, -1L)!!.scaleX }
        assertTrue(peak > 1.02f, "rebond ($peak)")
    }

    @Test
    fun `en style Vif, la bulle ne depasse pas sa taille`() {
        val peak = (0L..400L).maxOf { KeyPreviewPose.pose(KeyPreviewAnimation.SPRING, MotionStyle.SNAPPY, it, -1L)!!.scaleX }
        assertTrue(peak <= 1.0001f, "aucun rebond ($peak)")
    }

    @Test
    fun `envol, la bulle monte en sortant, la lettre plus vite`() {
        val half = KeyPreviewPose.exitDurationMs(KeyPreviewAnimation.FLIGHT, style) / 2
        val p = KeyPreviewPose.pose(KeyPreviewAnimation.FLIGHT, style, settled, half)!!
        assertTrue(p.offsetY < 0f)
        assertTrue(p.letterOffsetY < 0f)
        assertTrue(p.alpha < 1f)
    }

    @Test
    fun `goutte, la forme part de la touche et y revient`() {
        assertEquals(0f, KeyPreviewPose.pose(KeyPreviewAnimation.DROP, style, 0L, -1L)!!.grow, 0.01f)
        val end = KeyPreviewPose.exitDurationMs(KeyPreviewAnimation.DROP, style) - 1
        assertTrue(KeyPreviewPose.pose(KeyPreviewAnimation.DROP, style, settled, end)!!.grow < 0.05f)
    }

    @Test
    fun `glisse, la bulle reprise par la touche suivante ne refait pas son entree`() {
        val p = KeyPreviewPose.pose(KeyPreviewAnimation.GLIDE, style, 0L, -1L, fresh = false)!!
        assertEquals(1f, p.alpha, 0.001f)
        assertEquals(1f, p.scaleX, 0.001f)
        assertEquals(0f, p.offsetY, 0.001f)
    }

    @Test
    fun `chaque style d animation regle aussi la bulle des touches`() {
        assertEquals(KeyPreviewAnimation.SPRING, MotionStyle.SPRING.bubble)
        assertEquals(KeyPreviewAnimation.SPRING, MotionStyle.SNAPPY.bubble)
        assertEquals(KeyPreviewAnimation.SPRING, MotionStyle.JELLY.bubble)
        assertEquals(KeyPreviewAnimation.FLIGHT, MotionStyle.FLIGHT.bubble)
        assertEquals(KeyPreviewAnimation.DROP, MotionStyle.DROP.bubble)
        assertEquals(KeyPreviewAnimation.GLIDE, MotionStyle.GLIDE.bubble)
        // Seul Ressort, le style par défaut, est gratuit.
        assertEquals(MotionStyle.SPRING, MotionStyle.DEFAULT)
        assertEquals(listOf(MotionStyle.SPRING), MotionStyle.entries.filter { it.isAvailable(premium = false) })
    }

    @Test
    fun `styles a particules du 19-11-2026`() {
        assertEquals(KeyPreviewAnimation.SPARKS, MotionStyle.SPARKS.bubble)
        assertEquals(KeyPreviewAnimation.PIXELS, MotionStyle.PIXELS.bubble)
        assertEquals(KeyPreviewAnimation.HEART, MotionStyle.HEART.bubble)
        assertEquals(KeyPreviewAnimation.BLOOM, MotionStyle.BLOOM.bubble)
        assertTrue(KeyPreviewAnimation.entries.filter { it.hasParticles }.size == 4)
        // Étincelles : rebond vif à l'entrée.
        val peak = (0L..300L).maxOf { KeyPreviewPose.pose(KeyPreviewAnimation.SPARKS, MotionStyle.SPARKS, it, -1L)!!.scaleX }
        assertTrue(peak > 1.05f, "dépassement $peak")
        // Pixels : posée dès la deuxième image, sans rebond.
        assertEquals(1f, KeyPreviewPose.pose(KeyPreviewAnimation.PIXELS, MotionStyle.PIXELS, 40L, -1L)!!.scaleX)
        // Bulle cœur : s'envole et se balance en sortie.
        val half = KeyPreviewPose.exitDurationMs(KeyPreviewAnimation.HEART, MotionStyle.HEART) / 3
        val flying = KeyPreviewPose.pose(KeyPreviewAnimation.HEART, MotionStyle.HEART, settled, half)!!
        assertTrue(flying.offsetY < -0.5f)
        assertTrue(flying.offsetX != 0f)
        // Éclosion : les pétales s'ouvrent l'un après l'autre.
        assertTrue(KeyPreviewPose.petalOpen(30L, 0) > KeyPreviewPose.petalOpen(30L, 4))
        assertEquals(0f, KeyPreviewPose.petalOpen(50L, 4))
        assertEquals(1f, KeyPreviewPose.petalOpen(0L, 4, animated = false))
        assertEquals(1f, KeyPreviewPose.petalOpen(settled, 4), 0.01f)
    }
}
