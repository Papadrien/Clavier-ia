package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Icône micro animée : états, niveau sonore, ressort, trait de la capsule et jauge (calculs purs). */
class MicIconPoseTest {

    @Test
    fun `le chargement et la transcription montrent l'attente, l'enregistrement l'écoute`() {
        assertEquals(MicIconMode.IDLE, MicIconMode.of(VoiceBarState.IDLE))
        assertEquals(MicIconMode.LOADING, MicIconMode.of(VoiceBarState.LOADING))
        assertEquals(MicIconMode.LOADING, MicIconMode.of(VoiceBarState.TRANSCRIBING))
        assertEquals(MicIconMode.LISTENING, MicIconMode.of(VoiceBarState.RECORDING))
    }

    @Test
    fun `le niveau sonore suit l'amplitude sur une échelle logarithmique bornée`() {
        assertEquals(0f, VoiceLevel.fromRms(0.0))
        assertEquals(1f, VoiceLevel.fromRms(32768.0))
        assertTrue(VoiceLevel.fromRms(500.0) in 0.3f..0.5f)
        assertTrue(VoiceLevel.fromRms(3000.0) > VoiceLevel.fromRms(500.0))
        assertTrue(VoiceLevel.fromRms(3000.0) in 0.7f..0.95f)
    }

    @Test
    fun `le ressort dépasse sa cible d'environ 20 pour cent puis s'y stabilise`() {
        val spring = SpringValue()
        var peak = 0f
        repeat(90) {
            spring.step(1f, 1f / 60f)
            peak = maxOf(peak, spring.value)
        }
        assertTrue(peak in 1.05f..1.35f)
        assertTrue(spring.isSettled(1f))
        spring.step(0f, 1f / 60f)
        assertTrue(spring.value < 1f)
    }

    @Test
    fun `sans chargement le contour de la capsule est entier`() {
        assertEquals(0f to 1f, MicIconPose.traceWindow(0f, 0f))
        assertEquals(0f to 1f, MicIconPose.traceWindow(1.1f, 0f))
    }

    @Test
    fun `en chargement le trait se dessine puis s'efface`() {
        val p = MicIconPose.TRACE_PERIOD_S
        assertEquals(0f to 0f, MicIconPose.traceWindow(0f, 1f))
        val drawing = MicIconPose.traceWindow(p * 0.25f, 1f)
        assertEquals(0f, drawing.first, 1e-4f)
        assertEquals(0.5f, drawing.second, 1e-4f)
        val erasing = MicIconPose.traceWindow(p * 0.75f, 1f)
        assertEquals(0.5f, erasing.first, 1e-4f)
        assertEquals(1f, erasing.second, 1e-4f)
        val half = MicIconPose.traceWindow(p * 0.25f, 0.5f)
        assertEquals(0.75f, half.second, 1e-4f)
    }

    @Test
    fun `la jauge est vide hors écoute et suit le niveau sonore pendant l'écoute`() {
        assertEquals(0f, MicIconPose.gaugeFill(0f, 1f))
        assertEquals(MicIconPose.GAUGE_FLOOR, MicIconPose.gaugeFill(1f, 0f), 1e-4f)
        assertEquals(1f, MicIconPose.gaugeFill(1f, 1f), 1e-4f)
        assertTrue(MicIconPose.gaugeFill(1f, 0.8f) > MicIconPose.gaugeFill(1f, 0.3f))
    }

    @Test
    fun `le ressort peut faire déborder la jauge un peu, jamais sous zéro ni au-delà du maximum`() {
        assertEquals(MicIconPose.GAUGE_MAX, MicIconPose.gaugeFill(1.2f, 1f))
        assertEquals(0f, MicIconPose.gaugeFill(-0.1f, 0.5f))
    }

    @Test
    fun `la surface du liquide va du bas au haut de la capsule`() {
        assertEquals(14f, MicIconPose.surfaceY(0f, 8f, 0f), MicIconPose.WAVE_AMPLITUDE + 0.01f)
        assertEquals(3f, MicIconPose.surfaceY(1f, 8f, 0f), MicIconPose.WAVE_AMPLITUDE + 0.01f)
    }

    @Test
    fun `le lissage monte plus vite qu'il ne descend et ne dépasse jamais la cible`() {
        val rise = MicIconPose.smooth(0f, 1f, 0.05f)
        val fall = 1f - MicIconPose.smooth(1f, 0f, 0.05f)
        assertTrue(rise > fall)
        assertTrue(MicIconPose.smooth(0f, 1f, 10f) <= 1f)
        assertEquals(0.5f, MicIconPose.smooth(0.5f, 0.5f, 0.05f))
    }
}
