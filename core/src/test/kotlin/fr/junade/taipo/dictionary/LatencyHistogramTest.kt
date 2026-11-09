package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Phase 6 : histogramme de latence (seaux par puissances de deux, centiles en bornes hautes). */
class LatencyHistogramTest {

    @Test
    fun `les seaux suivent les puissances de deux en microsecondes`() {
        assertEquals(0, LatencyHistogram.bucketOf(0))
        assertEquals(1, LatencyHistogram.bucketOf(1))
        assertEquals(2, LatencyHistogram.bucketOf(2))
        assertEquals(2, LatencyHistogram.bucketOf(3))
        assertEquals(3, LatencyHistogram.bucketOf(4))
        assertEquals(3, LatencyHistogram.bucketOf(7))
        assertEquals(10, LatencyHistogram.bucketOf(1_000))
        assertEquals(14, LatencyHistogram.bucketOf(16_000))
    }

    @Test
    fun `une duree enorme tombe dans le dernier seau`() {
        assertEquals(LatencyHistogram.BUCKETS - 1, LatencyHistogram.bucketOf(Long.MAX_VALUE))
    }

    @Test
    fun `chaque seau a pour borne haute la puissance de deux suivante`() {
        for (micros in listOf(1L, 2L, 3L, 5L, 100L, 1_000L, 16_000L, 1_000_000L)) {
            val bucket = LatencyHistogram.bucketOf(micros)
            assertTrue(micros < LatencyHistogram.upperBoundMicros(bucket), "$micros µs")
        }
    }

    @Test
    fun `sans mesure, tout vaut zero`() {
        val histogram = LatencyHistogram()
        assertEquals(0L, histogram.count)
        assertEquals(0L, histogram.percentileMicros(0.5))
        assertEquals(0L, histogram.maxMicros)
        assertEquals(0L, histogram.slowCount)
    }

    @Test
    fun `un centile ne depasse jamais le maximum mesure`() {
        val histogram = LatencyHistogram()
        repeat(100) { histogram.record(3_000) } // 3 µs : seau [2, 4), borne haute 4
        assertEquals(3L, histogram.percentileMicros(0.5))
        assertEquals(3L, histogram.percentileMicros(0.99))
        assertEquals(3L, histogram.maxMicros)
    }

    @Test
    fun `les centiles separent la masse des quelques mesures lentes`() {
        val histogram = LatencyHistogram()
        repeat(95) { histogram.record(10_000) } // 10 µs : seau [8, 16)
        repeat(5) { histogram.record(5_000_000) } // 5 000 µs
        assertEquals(16L, histogram.percentileMicros(0.50))
        assertEquals(16L, histogram.percentileMicros(0.95))
        assertEquals(5_000L, histogram.percentileMicros(0.99))
        assertEquals(5_000L, histogram.maxMicros)
        assertEquals(100L, histogram.count)
    }

    @Test
    fun `les durees lentes sont comptees a partir du seuil`() {
        val histogram = LatencyHistogram(slowMicros = 1_000)
        histogram.record(999_000)
        histogram.record(1_000_000)
        histogram.record(50_000_000)
        assertEquals(2L, histogram.slowCount)
    }

    @Test
    fun `une duree negative compte pour zero`() {
        val histogram = LatencyHistogram()
        histogram.record(-5)
        assertEquals(1L, histogram.count)
        assertEquals(0L, histogram.maxMicros)
    }

    @Test
    fun `reset efface tout`() {
        val histogram = LatencyHistogram()
        repeat(10) { histogram.record(20_000_000) }
        histogram.reset()
        assertEquals(0L, histogram.count)
        assertEquals(0L, histogram.percentileMicros(0.99))
        assertEquals(0L, histogram.slowCount)
        assertEquals(0L, histogram.maxMicros)
    }
}
