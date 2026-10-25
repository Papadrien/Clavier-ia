package fr.junade.taipo

import kotlin.math.hypot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Icône du bouton d'envoi (étincelles en orbite, décollage de la flèche) : calculs purs. */
class SendIconPoseTest {

    @Test
    fun `les etincelles restent sur le cercle d'orbite et sont espacees de 120 degres`() {
        for (turns in listOf(0f, 0.13f, 0.5f, 0.87f)) {
            val points = (0..2).map { SendIconPose.sparklePosition(it, turns) }
            points.forEach { (x, y) ->
                assertEquals(SendIconPose.ORBIT_RADIUS, hypot(x, y), 1e-3f)
            }
            val (x0, y0) = points[0]
            val (x1, y1) = points[1]
            val chord = hypot(x1 - x0, y1 - y0)
            assertEquals(SendIconPose.ORBIT_RADIUS * 1.7320508f, chord, 1e-3f)
        }
    }

    @Test
    fun `la premiere etincelle part du haut puis tourne vers la droite`() {
        val (x, y) = SendIconPose.sparklePosition(0, 0f)
        assertEquals(0f, x, 1e-4f)
        assertEquals(-SendIconPose.ORBIT_RADIUS, y, 1e-4f)
        val (qx, qy) = SendIconPose.sparklePosition(0, 0.25f)
        assertEquals(SendIconPose.ORBIT_RADIUS, qx, 1e-3f)
        assertEquals(0f, qy, 1e-3f)
    }

    @Test
    fun `les etincelles ont la taille des points de la maquette et une traine decroissante`() {
        assertEquals(4.5f, SendIconPose.SPARKLE_SIZE)
        assertEquals(listOf(1f, 0.75f, 0.5f), SendIconPose.SPARKLE_SCALES.toList())
        assertTrue(SendIconPose.SPARKLE_ALPHAS[0] > SendIconPose.SPARKLE_ALPHAS[1])
        assertTrue(SendIconPose.SPARKLE_ALPHAS[1] > SendIconPose.SPARKLE_ALPHAS[2])
    }

    @Test
    fun `l'orbite se replie lentement puis vite et reste bornee`() {
        assertEquals(1f, SendIconPose.orbitScale(1f))
        assertEquals(0f, SendIconPose.orbitScale(0f))
        assertEquals(1f, SendIconPose.orbitScale(5f))
        assertEquals(0f, SendIconPose.orbitScale(-1f))
        val start = 1f - SendIconPose.orbitScale(0.9f)
        val end = SendIconPose.orbitScale(0.1f)
        assertTrue(start < 0.05f)
        assertTrue(end < 0.25f)
    }

    @Test
    fun `la fleche decolle vers le haut en s'etirant et en s'effacant`() {
        val rest = SendIconPose.launchPose(0f)
        assertEquals(0f, rest.offsetY)
        assertEquals(1f, rest.scaleY)
        assertEquals(1f, rest.alpha)
        assertTrue(rest.visible)
        val mid = SendIconPose.launchPose(SendIconPose.LAUNCH_S * 0.4f)
        assertTrue(mid.offsetY < 0f)
        assertTrue(mid.scaleY > 1f)
        assertTrue(mid.alpha < 1f)
        assertTrue(mid.visible)
    }

    @Test
    fun `apres le decollage la fleche est cachee jusqu'a l'icone suivante`() {
        assertFalse(SendIconPose.launchPose(SendIconPose.LAUNCH_S * 0.9f).visible)
        assertFalse(SendIconPose.launchPose(SendIconPose.LAUNCH_S * 5f).visible)
    }

    @Test
    fun `l'onde part apres un court delai et finit avant de disparaitre`() {
        assertNull(SendIconPose.ringProgress(0f))
        assertNull(SendIconPose.ringProgress(SendIconPose.RING_DELAY_S - 0.01f))
        assertNotNull(SendIconPose.ringProgress(SendIconPose.RING_DELAY_S + 0.2f))
        assertNull(SendIconPose.ringProgress(SendIconPose.RING_DELAY_S + SendIconPose.RING_S + 0.01f))
        assertEquals(0.2f, SendIconPose.ringRadiusShare(0f), 1e-4f)
        assertEquals(1.25f, SendIconPose.ringRadiusShare(1f), 1e-4f)
    }
}

/** Étincelle du bouton chat : poses de repos ouvert et fermé. */
class ChatIconPoseTest {

    @Test
    fun `ouvert l'etincelle est pleine et droite`() {
        assertEquals(1f, ChatIconPose.restScale(true))
        assertEquals(0f, ChatIconPose.restRotation(true))
    }

    @Test
    fun `ferme l'etincelle est reduite et tournee d'un quart de tour`() {
        assertEquals(0.7f, ChatIconPose.restScale(false))
        assertEquals(90f, ChatIconPose.restRotation(false))
    }

    @Test
    fun `l'apparition part d'un demi-tour en arriere`() {
        assertEquals(-180f, ChatIconPose.APPEAR_FROM_ROTATION)
    }

    @Test
    fun `l'angle se stabilise dans une tolerance de degres`() {
        val spring = SpringValue(0f)
        repeat(120) { spring.step(90f, 1f / 60f) }
        assertTrue(spring.isSettledAngle(90f))
    }
}
