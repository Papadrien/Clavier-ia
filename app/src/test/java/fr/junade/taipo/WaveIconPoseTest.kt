package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Icône Transcription IA animée : états, barres en onde et lignes (calculs purs). */
class WaveIconPoseTest {

    @Test
    fun `l'enregistrement montre l'écoute, le traitement et l'annulation les lignes`() {
        assertEquals(WaveIconMode.IDLE, WaveIconMode.of(AiTranscriptionBarState.IDLE))
        assertEquals(WaveIconMode.LISTENING, WaveIconMode.of(AiTranscriptionBarState.RECORDING))
        assertEquals(WaveIconMode.WORKING, WaveIconMode.of(AiTranscriptionBarState.PROCESSING))
        assertEquals(WaveIconMode.WORKING, WaveIconMode.of(AiTranscriptionBarState.CANCELLING))
    }

    @Test
    fun `au repos les trois barres sont droites, centrées, et la centrale est la plus haute`() {
        val bars = (0 until WaveIconPose.BAR_COUNT).map { WaveIconPose.bar(it, 0f, 0f, 0f, 1.3f) }
        bars.forEachIndexed { i, r ->
            assertEquals(WaveIconPose.REST_HEIGHT[i], r.height, 1e-4f)
            assertEquals(WaveIconPose.BAR_WIDTH, r.width, 1e-4f)
            assertEquals(WaveIconPose.CENTER, r.y + r.height / 2f, 1e-4f)
            assertEquals(WaveIconPose.CENTER + (i - 1) * WaveIconPose.BAR_PITCH, r.x + r.width / 2f, 1e-4f)
        }
        assertTrue(bars[1].height > bars[0].height && bars[1].height > bars[2].height)
    }

    @Test
    fun `à l'écoute les barres grandissent avec le niveau sonore, la centrale en tête`() {
        for (i in 0 until WaveIconPose.BAR_COUNT) {
            val silent = WaveIconPose.barHeight(i, 1f, 0f, 0.4f)
            val loud = WaveIconPose.barHeight(i, 1f, 1f, 0.4f)
            assertEquals(WaveIconPose.LISTEN_MIN_HEIGHT, silent, 1e-4f)
            assertTrue(loud > silent)
            assertTrue(loud <= WaveIconPose.LISTEN_MAX_HEIGHT)
        }
        val loud = (0 until WaveIconPose.BAR_COUNT).map { WaveIconPose.barHeight(it, 1f, 1f, 0f) }
        assertTrue(loud[1] > loud[0] && loud[1] > loud[2])
    }

    @Test
    fun `hors écoute le niveau sonore ne change pas la hauteur des barres`() {
        for (i in 0 until WaveIconPose.BAR_COUNT) {
            assertEquals(WaveIconPose.barHeight(i, 0f, 0f, 2f), WaveIconPose.barHeight(i, 0f, 1f, 2f), 1e-4f)
        }
    }

    @Test
    fun `en attente les barres se couchent en trois lignes empilées de longueurs différentes`() {
        val lines = (0 until WaveIconPose.BAR_COUNT).map { WaveIconPose.bar(it, 0f, 1f, 0f, 0.7f) }
        lines.forEachIndexed { i, r ->
            assertEquals(WaveIconPose.LINE_LENGTH[i], r.width, 1e-4f)
            assertEquals(WaveIconPose.BAR_WIDTH, r.height, 1e-4f)
            assertEquals(WaveIconPose.CENTER, r.x + r.width / 2f, 1e-4f)
            assertEquals(WaveIconPose.CENTER + (i - 1) * WaveIconPose.BAR_PITCH, r.y + r.height / 2f, 1e-4f)
        }
        assertEquals(3, lines.map { it.width }.toSet().size)
    }

    @Test
    fun `à mi-chemin la barre garde son centre, ni disparue ni inversée`() {
        val mid = WaveIconPose.bar(1, 0f, 0.5f, 0f, 0f)
        assertEquals(WaveIconPose.CENTER, mid.x + mid.width / 2f, 1e-4f)
        assertEquals(WaveIconPose.CENTER + 0f, mid.y + mid.height / 2f, 1e-4f)
    }

    @Test
    fun `le ressort ne fait jamais disparaître une barre`() {
        for (i in 0 until WaveIconPose.BAR_COUNT) {
            for (load in listOf(-0.3f, 0f, 1f, 1.3f)) {
                for (listen in listOf(-0.3f, 0f, 1f, 1.3f)) {
                    val r = WaveIconPose.bar(i, listen, load, 0.5f, 0.2f)
                    assertTrue(r.width >= WaveIconPose.MIN_SIDE)
                    assertTrue(r.height >= WaveIconPose.MIN_SIDE)
                }
            }
        }
    }

    @Test
    fun `le reflet reste entre 0 et 1 et se décale d'une ligne à l'autre`() {
        for (i in 0 until WaveIconPose.BAR_COUNT) {
            for (t in listOf(0f, 0.3f, 1.7f, 12f)) {
                assertTrue(WaveIconPose.sweep(i, t) in 0f..1f)
            }
        }
        assertTrue(WaveIconPose.sweep(0, 0.4f) != WaveIconPose.sweep(1, 0.4f))
    }

    @Test
    fun `le lissage monte plus vite qu'il ne descend et ne dépasse jamais la cible`() {
        val rise = WaveIconPose.smooth(0f, 1f, 0.05f)
        val fall = 1f - WaveIconPose.smooth(1f, 0f, 0.05f)
        assertTrue(rise > fall)
        assertTrue(WaveIconPose.smooth(0f, 1f, 10f) <= 1f)
        assertEquals(0.5f, WaveIconPose.smooth(0.5f, 0.5f, 0.05f))
    }
}
