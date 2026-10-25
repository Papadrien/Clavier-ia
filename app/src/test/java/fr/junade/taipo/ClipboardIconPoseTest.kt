package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Icône Smart Clipboard animée (« Étincelles ») : inclinaison, taille, rotation et opacité des étincelles. */
class ClipboardIconPoseTest {

    @Test
    fun `au repos le presse-papiers est droit et les étincelles invisibles`() {
        assertEquals(0f, ClipboardIconPose.tilt(0f), 1e-6f)
        assertEquals(0f, ClipboardIconPose.sparkAlpha(0f))
        ClipboardIconPose.SPARKS.indices.forEach { assertEquals(0f, ClipboardIconPose.sparkScale(it, 0f, 1.7f)) }
    }

    @Test
    fun `ouvert, le presse-papiers s'incline vers la gauche d'au moins 8 degrés`() {
        assertEquals(-8f, ClipboardIconPose.tilt(1f), 1e-4f)
        assertTrue(ClipboardIconPose.tilt(1f) < ClipboardIconPose.tilt(0.5f))
    }

    @Test
    fun `les étincelles sont deux fois plus grosses qu'à la première maquette`() {
        assertEquals(5.0f, ClipboardIconPose.SPARKS[0].radius, 1e-4f)
        assertTrue(ClipboardIconPose.SPARKS[0].radius > ClipboardIconPose.SPARKS[1].radius)
        // Ouvertes, elles scintillent autour de leur rayon (±18 %).
        (0..60).forEach { step ->
            val scale = ClipboardIconPose.sparkScale(0, 1f, step * 0.1f)
            assertTrue(scale in 5.0f * 0.81f..5.0f * 1.19f)
        }
    }

    @Test
    fun `les étincelles arrivent en tournant puis oscillent doucement`() {
        assertTrue(ClipboardIconPose.sparkRotation(0, 0f, 0f) < -100f)
        (0..60).forEach { step ->
            assertTrue(kotlin.math.abs(ClipboardIconPose.sparkRotation(0, 1f, step * 0.1f)) <= 10f + 1e-3f)
        }
    }

    @Test
    fun `le ressort ne rend jamais les étincelles négatives ni plus opaques que 1`() {
        assertEquals(0f, ClipboardIconPose.sparkScale(1, -0.1f, 0.3f))
        assertEquals(1f, ClipboardIconPose.sparkAlpha(1.2f))
        assertEquals(0f, ClipboardIconPose.sparkAlpha(-0.1f))
        assertEquals(1f, ClipboardIconPose.sparkAlpha(0.5f), 1e-4f)
    }
}
