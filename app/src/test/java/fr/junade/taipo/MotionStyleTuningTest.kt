package fr.junade.taipo

import kotlin.math.abs
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Transposition des animations réglées pour le style Ressort dans le style choisi (13/11/2026) : identique en Ressort, plus vive et
 * sans dépassement en Vif, plus lente et plus rebondie en Gelée. Vérifiée sur les ressorts propres de l'icône Maj et sur le
 * balancier d'Effacer.
 */
class MotionStyleTuningTest {

    @AfterEach
    fun reset() {
        MotionStyle.current = MotionStyle.DEFAULT
    }

    @Test
    fun `Ressort ne change aucun reglage`() {
        val style = MotionStyle.SPRING
        assertEquals(700f, style.tuneStiffness(700f))
        assertEquals(0.83f, style.tuneDampingRatio(0.83f))
        assertEquals(24f, style.tuneDamping(751f, 24f))
        assertEquals(0.25f, style.tuneSeconds(0.25f))
        assertEquals(300L, style.tuneDurationMs(300L))
        assertEquals(2f, style.tuneOvershootTension(2f))
    }

    @Test
    fun `Vif est plus rapide et n'a pas de depassement`() {
        val style = MotionStyle.SNAPPY
        assertTrue(style.tuneStiffness(400f) > 1_600f)
        assertEquals(1f, style.tuneDampingRatio(0.456f))
        assertEquals(1.2f, style.tuneDampingRatio(1.2f)) // déjà sans dépassement : inchangé
        assertTrue(style.tuneDurationMs(500L) < 250L)
        assertEquals(0f, style.tuneOvershootTension(2f))
    }

    @Test
    fun `Gelee est plus lente et plus rebondie`() {
        val style = MotionStyle.JELLY
        assertTrue(style.tuneStiffness(400f) < 400f)
        assertTrue(style.tuneDampingRatio(0.5f) < 0.5f)
        assertTrue(style.tuneDurationMs(500L) > 500L)
        assertTrue(style.tuneOvershootTension(2f) > 2f)
    }

    @Test
    fun `l'amortissement absolu suit le ratio transpose`() {
        val style = MotionStyle.SNAPPY
        val stiffness = 751f
        val damping = style.tuneDamping(stiffness, 24f)
        val ratio = damping / (2f * kotlin.math.sqrt(style.tuneStiffness(stiffness)))
        assertEquals(1f, ratio, 1e-4f)
    }

    /** Plus grand dépassement (au-delà de la cible 1) d'un [TunedSpring] lâché de 0, réglé k = 300, c = 12 (ratio ≈ 0,35). */
    private fun overshoot(style: MotionStyle): Float {
        MotionStyle.current = style
        val spring = TunedSpring(0f).apply { go(1f, 300f, 12f) }
        var peak = 0f
        repeat(300) {
            spring.step(1f / 120f)
            peak = maxOf(peak, spring.value)
        }
        return peak - 1f
    }

    @Test
    fun `les ressorts de l'icone Maj suivent le style`() {
        val spring = overshoot(MotionStyle.SPRING)
        assertTrue(spring > 0.2f, "Ressort : dépassement marqué ($spring)")
        assertTrue(overshoot(MotionStyle.SNAPPY) < 0.001f)
        assertTrue(overshoot(MotionStyle.JELLY) > spring)
    }

    @Test
    fun `le balancier d'Effacer suit le style`() {
        assertEquals(BackspaceIconPose.SWING_DURATION_S, BackspaceIconPose.swingDuration(MotionStyle.SPRING))
        assertTrue(BackspaceIconPose.swingDuration(MotionStyle.SNAPPY) < BackspaceIconPose.SWING_DURATION_S)
        assertTrue(BackspaceIconPose.swingDuration(MotionStyle.JELLY) > BackspaceIconPose.SWING_DURATION_S)
        // Même amplitude au départ dans tous les styles, et rien après la fin.
        for (style in MotionStyle.entries) {
            assertEquals(-32f, BackspaceIconPose.swingAngle(0f, -32f, style), 1e-4f)
            assertEquals(0f, BackspaceIconPose.swingAngle(BackspaceIconPose.swingDuration(style), -32f, style))
            // Fin du balancier : il reste moins de 2° quel que soit le style (pas de saut visible quand il s'arrête).
            val nearEnd = BackspaceIconPose.swingDuration(style) * 0.999f
            assertTrue(abs(BackspaceIconPose.swingAngle(nearEnd, -32f, style)) < 2.5f, "$style")
        }
    }
}
