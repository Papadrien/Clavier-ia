package fr.junade.taipo.ai

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodage WAV (spike 14.1) : PCM 16 bits, mono, little-endian, avec l'en-tête canonique de 44 octets. C'est le format
 * que le spike envoie au modèle (`Content.AudioBytes` / `Content.AudioFile`) ; la documentation Google du modèle demande
 * du mono 16 kHz. Logique pure, testée en JVM.
 */
object WavEncoder {
    const val HEADER_SIZE = 44
    private const val BITS_PER_SAMPLE = 16
    private const val CHANNELS = 1

    fun pcm16Mono(samples: ShortArray, sampleRate: Int): ByteArray {
        val dataSize = samples.size * 2
        val buffer = ByteBuffer.allocate(HEADER_SIZE + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16) // taille du bloc fmt
        buffer.putShort(1) // PCM
        buffer.putShort(CHANNELS.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * CHANNELS * BITS_PER_SAMPLE / 8) // octets par seconde
        buffer.putShort((CHANNELS * BITS_PER_SAMPLE / 8).toShort()) // octets par échantillon (tous canaux)
        buffer.putShort(BITS_PER_SAMPLE.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)
        samples.forEach { buffer.putShort(it) }
        return buffer.array()
    }
}
