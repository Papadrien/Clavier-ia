package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Rotation de l'icône Paramètres pendant l'ouverture de l'application (calculs purs). */
class SpinIconPoseTest {

    @Test
    fun `l'angle vise avance a vitesse constante puis s'arrete a la duree maximale`() {
        assertEquals(0f, SpinIconPose.spinTarget(0f))
        assertEquals(270f, SpinIconPose.spinTarget(0.5f), 1e-3f)
        assertEquals(SpinIconPose.spinTarget(SpinIconPose.MAX_SPIN_S), SpinIconPose.spinTarget(10f), 1e-3f)
    }

    @Test
    fun `l'arrivee est le prochain tour entier`() {
        assertEquals(360f, SpinIconPose.settleTarget(270f))
        assertEquals(720f, SpinIconPose.settleTarget(361f))
        assertEquals(720f, SpinIconPose.settleTarget(720f))
        assertEquals(0f, SpinIconPose.settleTarget(0f))
    }

    @Test
    fun `le ressort suit l'angle vise, le depasse a l'arret puis se pose sur un tour entier`() {
        val spring = SpringValue()
        var seconds = 0f
        val dt = 1f / 60f
        while (seconds < 1f) {
            seconds += dt
            spring.step(SpinIconPose.spinTarget(seconds), dt)
        }
        val arrival = SpinIconPose.settleTarget(SpinIconPose.spinTarget(seconds))
        var maxValue = spring.value
        repeat(300) {
            spring.step(arrival, dt)
            maxValue = maxOf(maxValue, spring.value)
        }
        assertTrue(maxValue > arrival, "dépassement attendu")
        assertTrue(spring.isSettledAngle(arrival))
        assertEquals(0f, arrival % 360f)
    }
}
