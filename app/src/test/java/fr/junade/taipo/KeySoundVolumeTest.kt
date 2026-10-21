package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Volume des sons du clavier : conversion du pourcentage du curseur en gain de lecture. */
class KeySoundVolumeTest {

    @Test
    fun `le reglage par defaut redonne le volume d avant le curseur`() {
        assertEquals(0.2857f, KeySoundVolume.gain(KeySoundVolume.DEFAULT_PERCENT), 0.0005f)
    }

    @Test
    fun `0 pour cent est muet et 100 pour cent donne le gain maximal`() {
        assertEquals(0f, KeySoundVolume.gain(0), 0f)
        assertEquals(KeySoundVolume.MAX_GAIN, KeySoundVolume.gain(100), 0f)
    }

    @Test
    fun `le gain croit avec le curseur`() {
        val gains = (0..100 step 10).map { KeySoundVolume.gain(it) }
        assertTrue(gains.zipWithNext().all { (low, high) -> low < high })
    }

    @Test
    fun `une valeur hors bornes est ramenee entre 0 et 100`() {
        assertEquals(0, KeySoundVolume.clamp(-5))
        assertEquals(100, KeySoundVolume.clamp(250))
        assertEquals(KeySoundVolume.MAX_GAIN, KeySoundVolume.gain(250), 0f)
        assertEquals(0f, KeySoundVolume.gain(-5), 0f)
    }

    @Test
    fun `le gain maximal reste sous la pleine echelle avec des echantillons plafonnes a 0,7`() {
        assertTrue(KeySoundVolume.MAX_GAIN * 0.7f < 1f)
    }
}
