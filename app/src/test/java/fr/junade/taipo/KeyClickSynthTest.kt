package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class KeyClickSynthTest {

    private val key = KeyClickSynth.render(KeyClickProfiles.KEY, seed = 1)
    private val space = KeyClickSynth.render(KeyClickProfiles.SPACE, seed = 1)
    private val control = KeyClickSynth.render(KeyClickProfiles.CONTROL, seed = 1)

    @Test
    fun `un son dure 5 ms soit 220 echantillons`() {
        assertEquals(220, key.size)
        assertEquals(220, space.size)
        assertEquals(220, control.size)
    }

    @Test
    fun `le pic est a 70 pour cent de la pleine echelle`() {
        for (samples in listOf(key, space, control)) {
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
    fun `le son des touches de commande reproduit la reference Python`() {
        assertReference(control, listOf(0, 484, 378, 650, 2950, 5458), sumAbs = 1107168, max = 22937, min = -10708)
    }

    @Test
    fun `le son des boutons de la barre du haut reproduit la reference Python`() {
        val bar = SwitchClickSynth.render(SwitchClickSynth.BAR, seed = 1, variation = 0.03)
        assertEquals(1411, bar.size) // 32 ms
        assertTrue(abs(bar.maxOf { abs(it.toInt()) } - 19660) <= 2) // pic à 60 % de la pleine échelle
        assertReference(bar, listOf(0, 488, 2234, 3042, 5891, 9378), sumAbs = 2214951, max = 15968, min = -19660)
    }

    @Test
    fun `le son des boutons IA reproduit la reference Python`() {
        val ai = SwitchClickSynth.render(SwitchClickSynth.AI, seed = 1, variation = 0.03)
        assertEquals(2116, ai.size) // 48 ms
        assertTrue(abs(ai.maxOf { abs(it.toInt()) } - 19660) <= 2) // même pic que le son de la barre
        assertReference(ai, listOf(0, 405, 1969, 2475, 5318, 9295), sumAbs = 3478231, max = 19660, min = -19545)
    }

    @Test
    fun `les sons de debut et de fin de saisie vocale reproduisent la reference Python`() {
        val start = SwitchClickSynth.render(SwitchClickSynth.VOICE_START, seed = 1, variation = 0.03)
        val end = SwitchClickSynth.render(SwitchClickSynth.VOICE_END, seed = 1, variation = 0.03)
        assertEquals(3969, start.size) // 90 ms
        assertEquals(3969, end.size)
        assertReference(start, listOf(0, 488, 2234, 3042, 5891, 9378), sumAbs = 2532307, max = 18695, min = -19660)
        assertReference(end, listOf(0, 576, 2477, 3466, 5989, 8568), sumAbs = 2126598, max = 16700, min = -19660)
    }

    @Test
    fun `les sons de la transcription IA reproduisent la reference Python`() {
        fun render(preset: SwitchClickSynth.Preset) = SwitchClickSynth.render(preset, seed = 1, variation = 0.03)
        val start = render(SwitchClickSynth.TRANSCRIPTION_START)
        val listenEnd = render(SwitchClickSynth.TRANSCRIPTION_LISTEN_END)
        val done = render(SwitchClickSynth.TRANSCRIPTION_DONE)
        for (samples in listOf(start, listenEnd, done)) assertEquals(4233, samples.size) // 96 ms
        assertReference(start, listOf(0, 395, 1918, 2410, 5178, 9050), sumAbs = 3756304, max = 19660, min = -19031)
        assertReference(listenEnd, listOf(0, 498, 2257, 3100, 5892, 9217), sumAbs = 3243793, max = 17546, min = -19660)
        assertReference(done, listOf(0, 391, 1898, 2386, 5126, 8959), sumAbs = 3767855, max = 19516, min = -19660)
    }

    @Test
    fun `chaque son de bouton a un rendu non vide et deterministe`() {
        val sounds = listOf(
            KeySound.BAR_BUTTON, KeySound.AI_BUTTON, KeySound.VOICE_START, KeySound.VOICE_END,
            KeySound.TRANSCRIPTION_START, KeySound.TRANSCRIPTION_LISTEN_END, KeySound.TRANSCRIPTION_DONE,
        )
        for (sound in sounds) {
            val first = sound.render(seed = 2, variation = 0.04)
            assertTrue(first.isNotEmpty())
            assertTrue(first.contentEquals(sound.render(seed = 2, variation = 0.04)))
        }
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
    fun `les touches de commande utilisent leur propre son`() {
        assertEquals(KeySound.CONTROL, KeySound.forAction(KeyAction.Backspace))
        assertEquals(KeySound.CONTROL, KeySound.forAction(KeyAction.Enter))
        assertEquals(KeySound.CONTROL, KeySound.forAction(KeyAction.Shift))
        assertEquals(KeySound.CONTROL, KeySound.forAction(KeyAction.ToggleLayout))
        assertEquals(KeySound.CONTROL, KeySound.forAction(KeyAction.SymbolPage))
        assertEquals(KeySound.CONTROL, KeySound.forAction(KeyAction.Emoji))
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
