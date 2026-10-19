package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class KeyClickSynthTest {

    private val key = KeyClickSynth.render(KeyClickProfiles.KEY, seed = 1)
    private val space = KeyClickSynth.render(KeyClickProfiles.SPACE, seed = 1)

    @Test
    fun `un son dure 5 ms soit 220 echantillons`() {
        assertEquals(220, key.size)
        assertEquals(220, space.size)
    }

    @Test
    fun `le pic est a 70 pour cent de la pleine echelle`() {
        for (samples in listOf(key, space)) {
            val peak = samples.maxOf { abs(it.toInt()) }
            assertTrue(abs(peak - 22937) <= 2, "pic $peak")
        }
    }

    @Test
    fun `une meme graine redonne exactement le meme son`() {
        assertTrue(key.contentEquals(KeyClickSynth.render(KeyClickProfiles.KEY, seed = 1)))
        assertTrue(!key.contentEquals(KeyClickSynth.render(KeyClickProfiles.KEY, seed = 2)))
    }

    /** Valeurs de référence calculées par tools/key_click_reference.py (graine 1) : le port Kotlin doit les retrouver. */
    @Test
    fun `le son des touches reproduit la reference Python`() {
        assertReference(key, listOf(0, 602, 444, 817, 3680, 6556), sumAbs = 1112367, max = 22937, min = -13908)
    }

    @Test
    fun `le son de la barre espace reproduit la reference Python`() {
        assertReference(space, listOf(0, 1101, 440, 1344, 5655, 7021), sumAbs = 889541, max = 22937, min = -17913)
    }

    @Test
    fun `la barre espace est plus aigue que les touches`() {
        // Énergie de la dérivée rapportée à l'énergie du signal : croît avec la brillance.
        fun brightness(s: ShortArray): Double {
            val diff = (1 until s.size).sumOf { val d = (s[it] - s[it - 1]).toDouble(); d * d }
            val energy = s.sumOf { it.toDouble() * it }
            return diff / energy
        }
        assertTrue(brightness(space) > brightness(key) * 1.3)
    }

    @Test
    fun `le fichier WAV a un en-tete PCM 16 bits mono valide`() {
        val wav = KeyClickSynth.toWav(key)
        assertEquals(44 + 440, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVEfmt ", String(wav, 8, 8, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(36 + 440, le32(wav, 4))
        assertEquals(44100, le32(wav, 24))
        assertEquals(440, le32(wav, 40))
        assertEquals(key[1].toInt(), (wav[46].toInt() and 0xFF) or (wav[47].toInt() shl 8))
    }

    @Test
    fun `lettres chiffres et symboles utilisent le son des touches et la barre espace le sien`() {
        assertEquals(KeySound.KEY, KeySound.forAction(KeyAction.TypeChar('a')))
        assertEquals(KeySound.KEY, KeySound.forAction(KeyAction.TypeChar('7')))
        assertEquals(KeySound.KEY, KeySound.forAction(KeyAction.TypeChar('@')))
        assertEquals(KeySound.SPACE, KeySound.forAction(KeyAction.Space))
    }

    @Test
    fun `les autres touches n'ont pas de son`() {
        assertNull(KeySound.forAction(KeyAction.Backspace))
        assertNull(KeySound.forAction(KeyAction.Enter))
        assertNull(KeySound.forAction(KeyAction.Shift))
        assertNull(KeySound.forAction(KeyAction.ToggleLayout))
        assertNull(KeySound.forAction(KeyAction.SymbolPage))
        assertNull(KeySound.forAction(KeyAction.Emoji))
    }

    private fun assertReference(samples: ShortArray, first: List<Int>, sumAbs: Int, max: Int, min: Int) {
        first.forEachIndexed { i, expected ->
            assertTrue(abs(samples[i] - expected) <= 3, "échantillon $i : ${samples[i]} au lieu de $expected")
        }
        val total = samples.sumOf { abs(it.toInt()) }
        assertTrue(abs(total - sumAbs) <= sumAbs / 500, "somme $total au lieu de $sumAbs")
        assertTrue(abs(samples.max() - max) <= 3, "max ${samples.max()}")
        assertTrue(abs(samples.min() - min) <= 3, "min ${samples.min()}")
    }

    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}
