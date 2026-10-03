package fr.junade.taipo.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class CaptureGuardsTest {

    @Test
    fun `une lecture non vide donne des donnees`() {
        assertEquals(ReadVerdict.DATA, ReadMonitor().onRead(640))
    }

    @ParameterizedTest
    @ValueSource(ints = [-1, -2, -3, -6])
    fun `un code d'erreur est un echec immediat`(code: Int) {
        assertEquals(ReadVerdict.FAILED, ReadMonitor().onRead(code))
    }

    @Test
    fun `les lectures vides attendent puis echouent a la limite`() {
        val monitor = ReadMonitor(maxEmptyReads = 3)
        assertEquals(ReadVerdict.WAIT, monitor.onRead(0))
        assertEquals(ReadVerdict.WAIT, monitor.onRead(0))
        assertEquals(ReadVerdict.FAILED, monitor.onRead(0))
    }

    @Test
    fun `une lecture non vide remet le compteur de lectures vides a zero`() {
        val monitor = ReadMonitor(maxEmptyReads = 3)
        assertEquals(ReadVerdict.WAIT, monitor.onRead(0))
        assertEquals(ReadVerdict.WAIT, monitor.onRead(0))
        assertEquals(ReadVerdict.DATA, monitor.onRead(320))
        assertEquals(ReadVerdict.WAIT, monitor.onRead(0))
        assertEquals(ReadVerdict.WAIT, monitor.onRead(0))
        assertEquals(ReadVerdict.FAILED, monitor.onRead(0))
    }

    @Test
    fun `la limite par defaut represente environ une seconde sans donnee`() {
        val monitor = ReadMonitor()
        repeat(ReadMonitor.MAX_EMPTY_READS - 1) { assertEquals(ReadVerdict.WAIT, monitor.onRead(0)) }
        assertEquals(ReadVerdict.FAILED, monitor.onRead(0))
        assertEquals(1_000L, ReadMonitor.MAX_EMPTY_READS * ReadMonitor.EMPTY_READ_DELAY_MS)
    }

    @Test
    fun `la file couvre 60 secondes d'audio a 16 kHz`() {
        // 16 000 Hz × 60 s = 960 000 échantillons ; blocs de 1 280 → 750 blocs.
        assertEquals(750, CaptureBacklog.capacityFor(chunkSamples = 1_280, sampleRate = 16_000))
    }

    @Test
    fun `la capacite est arrondie au-dessus`() {
        // 960 000 / 1 700 = 564,7 → 565.
        assertEquals(565, CaptureBacklog.capacityFor(chunkSamples = 1_700, sampleRate = 16_000))
    }

    @Test
    fun `la capacite vaut au moins un bloc`() {
        assertEquals(1, CaptureBacklog.capacityFor(chunkSamples = 10_000_000, sampleRate = 16_000))
    }

    @Test
    fun `la duree est configurable`() {
        assertEquals(125, CaptureBacklog.capacityFor(chunkSamples = 1_280, sampleRate = 16_000, seconds = 10))
    }

    @Test
    fun `des parametres invalides sont refuses`() {
        assertThrows(IllegalArgumentException::class.java) { CaptureBacklog.capacityFor(0, 16_000) }
        assertThrows(IllegalArgumentException::class.java) { CaptureBacklog.capacityFor(1_280, 0) }
        assertThrows(IllegalArgumentException::class.java) { CaptureBacklog.capacityFor(1_280, 16_000, 0) }
    }
}
