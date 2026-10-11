package fr.junade.taipo.ai

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Spike 14.1 : l'en-tête WAV envoyé au modèle doit être exactement celui d'un PCM 16 bits mono. */
class WavEncoderTest {

    private fun header(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    @Test
    fun `en-tete canonique de 44 octets suivi des echantillons`() {
        val samples = shortArrayOf(0, 1, -1, 32767, -32768)
        val wav = WavEncoder.pcm16Mono(samples, 16_000)
        assertEquals(WavEncoder.HEADER_SIZE + samples.size * 2, wav.size)

        val b = header(wav)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + samples.size * 2, b.getInt(4))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals(16, b.getInt(16))
        assertEquals(1, b.getShort(20).toInt()) // PCM
        assertEquals(1, b.getShort(22).toInt()) // mono
        assertEquals(16_000, b.getInt(24))
        assertEquals(32_000, b.getInt(28)) // octets par seconde
        assertEquals(2, b.getShort(32).toInt())
        assertEquals(16, b.getShort(34).toInt())
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(samples.size * 2, b.getInt(40))
    }

    @Test
    fun `les echantillons sont ecrits en little-endian dans l'ordre`() {
        val samples = shortArrayOf(1, -2, 0x1234)
        val wav = WavEncoder.pcm16Mono(samples, 16_000)
        val data = wav.copyOfRange(WavEncoder.HEADER_SIZE, wav.size)
        assertArrayEquals(byteArrayOf(0x01, 0x00, 0xFE.toByte(), 0xFF.toByte(), 0x34, 0x12), data)
    }

    @Test
    fun `un enregistrement vide donne un en-tete valide sans donnees`() {
        val wav = WavEncoder.pcm16Mono(ShortArray(0), 16_000)
        assertEquals(WavEncoder.HEADER_SIZE, wav.size)
        assertEquals(0, header(wav).getInt(40))
    }
}
