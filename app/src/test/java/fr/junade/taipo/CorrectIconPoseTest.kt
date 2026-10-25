package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Icône Correction animée (« Scanner ») : faisceau, retracé de la coche, opacités (calculs purs). */
class CorrectIconPoseTest {

    @Test
    fun `le faisceau va et vient entre les deux bords de l'icône`() {
        assertEquals(CorrectIconPose.BEAM_MIN_X, CorrectIconPose.beamX(0f), 1e-4f)
        assertEquals(CorrectIconPose.BEAM_MAX_X, CorrectIconPose.beamX(CorrectIconPose.BEAM_PERIOD_S / 2f), 1e-4f)
        assertEquals(CorrectIconPose.BEAM_MIN_X, CorrectIconPose.beamX(CorrectIconPose.BEAM_PERIOD_S), 1e-3f)
    }

    @Test
    fun `le faisceau figé est au milieu de l'icône`() {
        assertEquals(12f, CorrectIconPose.beamX(CorrectIconPose.BEAM_CENTERED_TIME_S), 1e-3f)
    }

    @Test
    fun `sans animation de fin la coche est entière et à l'échelle 1`() {
        assertEquals(1f, CorrectIconPose.checkDrawn(-1f))
        assertEquals(1f, CorrectIconPose.checkScale(-1f))
        assertEquals(1f, CorrectIconPose.checkDrawn(CorrectIconPose.DONE_DURATION_S))
    }

    @Test
    fun `à la fin la coche s'efface puis se retrace`() {
        assertEquals(1f, CorrectIconPose.checkDrawn(0f), 1e-4f)
        assertEquals(0f, CorrectIconPose.checkDrawn(0.12f), 1e-4f)
        assertTrue(CorrectIconPose.checkDrawn(0.3f) in 0.05f..0.95f)
        assertTrue(CorrectIconPose.checkDrawn(0.4f) > CorrectIconPose.checkDrawn(0.3f))
        assertEquals(1f, CorrectIconPose.checkDrawn(0.6f), 1e-4f)
    }

    @Test
    fun `la coche rebondit puis revient à l'échelle 1`() {
        var peak = 1f
        var t = 0f
        while (t < CorrectIconPose.DONE_DURATION_S) {
            peak = maxOf(peak, CorrectIconPose.checkScale(t))
            t += 0.01f
        }
        assertTrue(peak in 1.1f..1.3f)
        assertEquals(1f, CorrectIconPose.checkScale(CorrectIconPose.DONE_DURATION_S), 0.01f)
    }

    @Test
    fun `l'icône est pleine au repos, atténuée et éclairée par le faisceau en correction`() {
        assertEquals(1f, CorrectIconPose.dimAlpha(0f))
        assertEquals(0f, CorrectIconPose.brightAlpha(0f))
        assertEquals(0f, CorrectIconPose.beamAlpha(0f))
        assertEquals(0.35f, CorrectIconPose.dimAlpha(1f), 1e-4f)
        assertEquals(1f, CorrectIconPose.brightAlpha(1f))
    }

    @Test
    fun `le ressort peut dépasser les poids sans sortir des bornes d'opacité`() {
        assertEquals(0.35f, CorrectIconPose.dimAlpha(1.2f), 1e-4f)
        assertEquals(1f, CorrectIconPose.brightAlpha(1.2f))
        assertEquals(1f, CorrectIconPose.dimAlpha(-0.1f))
        assertEquals(0f, CorrectIconPose.brightAlpha(-0.1f))
    }
}
