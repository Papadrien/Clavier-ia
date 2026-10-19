package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Ouverture en ressort de la bulle de symboles (calculs purs). */
class PopupOpenPoseTest {

    @Test
    fun `la bulle apparait en grandissant puis est opaque`() {
        assertEquals(0f, PopupOpenPose.alpha(0f))
        assertTrue(PopupOpenPose.alpha(PopupOpenPose.START_SCALE) in 0.01f..0.99f)
        assertEquals(1f, PopupOpenPose.alpha(PopupOpenPose.OPAQUE_AT))
        assertEquals(1f, PopupOpenPose.alpha(1.1f))
    }

    @Test
    fun `le ressort depasse la taille finale puis se pose dessus`() {
        val spring = SpringValue(PopupOpenPose.START_SCALE)
        var maxValue = 0f
        repeat(300) {
            spring.step(1f, 1f / 60f)
            maxValue = maxOf(maxValue, spring.value)
        }
        assertTrue(maxValue > 1f, "dépassement attendu")
        assertTrue(spring.isSettled(1f))
    }

    @Test
    fun `l'ouverture est deux fois plus rapide`() {
        fun framesToSettle(timeScale: Float): Int {
            val spring = SpringValue(PopupOpenPose.START_SCALE)
            var frames = 0
            while (!spring.isSettled(1f) && frames < 1000) {
                spring.step(1f, timeScale / 60f)
                frames++
            }
            return frames
        }
        val normal = framesToSettle(1f)
        val fast = framesToSettle(PopupOpenPose.TIME_SCALE)
        assertEquals(2f, PopupOpenPose.TIME_SCALE)
        assertTrue(fast in (normal / 2 - 1)..(normal / 2 + 1), "normal=$normal rapide=$fast")
    }

    @Test
    fun `le depassement est plafonne par la place disponible`() {
        // Bulle de 100 de large (de 100 à 200), pivot au milieu ; 2 de marge de chaque côté : 1,04 au plus.
        val max = PopupOpenPose.maxScale(
            pivotX = 150f, pivotY = 300f, left = 100f, right = 200f, top = 200f,
            minLeft = 98f, maxRight = 202f, minTop = -500f,
        )
        assertEquals(1.04f, max, 1e-4f)
    }

    @Test
    fun `sans marge le plafond reste a 1`() {
        val max = PopupOpenPose.maxScale(
            pivotX = 150f, pivotY = 300f, left = 100f, right = 200f, top = 200f,
            minLeft = 100f, maxRight = 200f, minTop = 200f,
        )
        assertEquals(1f, max)
    }
}
