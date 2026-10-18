package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Icône Vocal animée : états, niveau sonore, ressort, barres en onde et lignes (calculs purs). */
class MicIconPoseTest {

    @Test
    fun `le chargement et la transcription montrent l'attente (lignes), l'enregistrement l'écoute`() {
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
    fun `au repos les trois barres sont droites, centrées, et la centrale est la plus haute`() {
        val bars = (0 until MicIconPose.BAR_COUNT).map { MicIconPose.bar(it, 0f, 0f, 0f, 1.3f) }
        bars.forEachIndexed { i, r ->
            assertEquals(MicIconPose.REST_HEIGHT[i], r.height, 1e-4f)
            assertEquals(MicIconPose.BAR_WIDTH, r.width, 1e-4f)
            assertEquals(MicIconPose.CENTER, r.y + r.height / 2f, 1e-4f)
            assertEquals(MicIconPose.CENTER + (i - 1) * MicIconPose.BAR_PITCH, r.x + r.width / 2f, 1e-4f)
        }
        assertTrue(bars[1].height > bars[0].height && bars[1].height > bars[2].height)
    }

    @Test
    fun `à l'écoute les barres grandissent avec le niveau sonore, la centrale en tête`() {
        for (i in 0 until MicIconPose.BAR_COUNT) {
            val silent = MicIconPose.barHeight(i, 1f, 0f, 0.4f)
            val loud = MicIconPose.barHeight(i, 1f, 1f, 0.4f)
            assertEquals(MicIconPose.LISTEN_MIN_HEIGHT, silent, 1e-4f)
            assertTrue(loud > silent)
            assertTrue(loud <= MicIconPose.LISTEN_MAX_HEIGHT)
        }
        val loud = (0 until MicIconPose.BAR_COUNT).map { MicIconPose.barHeight(it, 1f, 1f, 0f) }
        assertTrue(loud[1] > loud[0] && loud[1] > loud[2])
    }

    @Test
    fun `hors écoute le niveau sonore ne change pas la hauteur des barres`() {
        for (i in 0 until MicIconPose.BAR_COUNT) {
            assertEquals(MicIconPose.barHeight(i, 0f, 0f, 2f), MicIconPose.barHeight(i, 0f, 1f, 2f), 1e-4f)
        }
    }

    @Test
    fun `en attente les barres se couchent en trois lignes empilées de longueurs différentes`() {
        val lines = (0 until MicIconPose.BAR_COUNT).map { MicIconPose.bar(it, 0f, 1f, 0f, 0.7f) }
        lines.forEachIndexed { i, r ->
            assertEquals(MicIconPose.LINE_LENGTH[i], r.width, 1e-4f)
            assertEquals(MicIconPose.BAR_WIDTH, r.height, 1e-4f)
            assertEquals(MicIconPose.CENTER, r.x + r.width / 2f, 1e-4f)
            assertEquals(MicIconPose.CENTER + (i - 1) * MicIconPose.BAR_PITCH, r.y + r.height / 2f, 1e-4f)
        }
        assertEquals(3, lines.map { it.width }.toSet().size)
    }

    @Test
    fun `à mi-chemin la barre garde son centre, ni disparue ni inversée`() {
        val mid = MicIconPose.bar(1, 0f, 0.5f, 0f, 0f)
        assertEquals(MicIconPose.CENTER, mid.x + mid.width / 2f, 1e-4f)
        assertEquals(MicIconPose.CENTER + 0f, mid.y + mid.height / 2f, 1e-4f)
    }

    @Test
    fun `le ressort ne fait jamais disparaître une barre`() {
        for (i in 0 until MicIconPose.BAR_COUNT) {
            for (load in listOf(-0.3f, 0f, 1f, 1.3f)) {
                for (listen in listOf(-0.3f, 0f, 1f, 1.3f)) {
                    val r = MicIconPose.bar(i, listen, load, 0.5f, 0.2f)
                    assertTrue(r.width >= MicIconPose.MIN_SIDE)
                    assertTrue(r.height >= MicIconPose.MIN_SIDE)
                }
            }
        }
    }

    @Test
    fun `le reflet reste entre 0 et 1 et se décale d'une ligne à l'autre`() {
        for (i in 0 until MicIconPose.BAR_COUNT) {
            for (t in listOf(0f, 0.3f, 1.7f, 12f)) {
                assertTrue(MicIconPose.sweep(i, t) in 0f..1f)
            }
        }
        assertTrue(MicIconPose.sweep(0, 0.4f) != MicIconPose.sweep(1, 0.4f))
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
