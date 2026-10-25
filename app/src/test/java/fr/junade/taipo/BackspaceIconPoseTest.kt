package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** Icône retour arrière animée : balancier de gauche à droite, inclinaison du glissement, vie des étincelles. */
class BackspaceIconPoseTest {

    @Test
    fun `le balancier part de l'amplitude demandee, vers la gauche`() {
        assertEquals(-32f, BackspaceIconPose.swingAngle(0f, -32f), 1e-4f)
        assertTrue(BackspaceIconPose.SWING_TAP_DEGREES < 0f)
    }

    @Test
    fun `le balancier passe de gauche a droite puis revient`() {
        val angles = (0..90).map { BackspaceIconPose.swingAngle(it * 0.01f, -32f) }
        assertTrue(angles.any { it > 10f }, "l'icône doit osciller franchement vers la droite")
        assertTrue(angles.any { it < -10f })
        val signChanges = angles.zipWithNext().count { (a, b) -> a * b < 0f }
        assertTrue(signChanges >= 3, "au moins un aller-retour et demi")
    }

    @Test
    fun `le balancier s'amortit et s'arrete`() {
        fun peak(from: Int, to: Int) = (from until to).maxOf { abs(BackspaceIconPose.swingAngle(it * 0.01f, -32f)) }
        assertTrue(peak(50, 90) < peak(0, 40))
        assertEquals(0f, BackspaceIconPose.swingAngle(BackspaceIconPose.SWING_DURATION_S, -32f), 1e-6f)
        assertEquals(0f, BackspaceIconPose.swingAngle(5f, -32f), 1e-6f)
        assertEquals(0f, BackspaceIconPose.swingAngle(-0.1f, -32f), 1e-6f)
        assertTrue(abs(BackspaceIconPose.swingAngle(BackspaceIconPose.SWING_DURATION_S - 0.001f, -32f)) < 2f)
    }

    @Test
    fun `l'appui maintenu balance moins fort qu'un appui simple`() {
        assertTrue(abs(BackspaceIconPose.SWING_REPEAT_DEGREES) < abs(BackspaceIconPose.SWING_TAP_DEGREES))
    }

    @Test
    fun `l'inclinaison du glissement va vers la gauche et est bornee`() {
        assertEquals(0f, BackspaceIconPose.dragTilt(0f), 1e-6f)
        assertEquals(0f, BackspaceIconPose.dragTilt(-10f), 1e-6f)
        assertEquals(-6f, BackspaceIconPose.dragTilt(30f), 1e-4f)
        assertEquals(-12f, BackspaceIconPose.dragTilt(60f), 1e-4f)
        assertEquals(-12f, BackspaceIconPose.dragTilt(400f), 1e-4f)
    }

    @Test
    fun `une etincelle part vite, rapetisse et s'eteint`() {
        assertEquals(0f, BackspaceSparkPose.ease(0f), 1e-6f)
        assertEquals(1f, BackspaceSparkPose.ease(1f), 1e-6f)
        assertTrue(BackspaceSparkPose.ease(0.5f) > 0.8f)
        assertEquals(1f, BackspaceSparkPose.alpha(0f), 1e-6f)
        assertEquals(0f, BackspaceSparkPose.alpha(1f), 1e-6f)
        assertEquals(1f, BackspaceSparkPose.scale(0f), 1e-6f)
        assertEquals(0.2f, BackspaceSparkPose.scale(1f), 1e-6f)
        assertEquals(2f, BackspaceSparkPose.RADIUS_DP, 1e-6f)
    }
}
