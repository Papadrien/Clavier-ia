package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HapticIntensityTest {

    @Test
    fun `moyen est le niveau par defaut`() {
        assertEquals(HapticIntensity.MEDIUM, HapticIntensity.DEFAULT)
    }

    @Test
    fun `chaque cle de stockage retrouve son niveau`() {
        assertEquals(HapticIntensity.OFF, HapticIntensity.fromStorageKey("off"))
        assertEquals(HapticIntensity.LIGHT, HapticIntensity.fromStorageKey("light"))
        assertEquals(HapticIntensity.MEDIUM, HapticIntensity.fromStorageKey("medium"))
        assertEquals(HapticIntensity.STRONG, HapticIntensity.fromStorageKey("strong"))
    }

    @Test
    fun `une cle absente ou inconnue retombe sur le niveau par defaut`() {
        assertEquals(HapticIntensity.DEFAULT, HapticIntensity.fromStorageKey(null))
        assertEquals(HapticIntensity.DEFAULT, HapticIntensity.fromStorageKey("inconnu"))
    }

    @Test
    fun `la duree de l impulsion est de 6 ms`() {
        assertEquals(6L, HapticIntensity.CLICK_DURATION_MS)
    }

    @Test
    fun `le deplacement du curseur reste faible ou absent`() {
        assertEquals(HapticIntensity.LIGHT, HapticIntensity.LIGHT.cursorMoveFeedback())
        assertEquals(HapticIntensity.LIGHT, HapticIntensity.MEDIUM.cursorMoveFeedback())
        assertEquals(HapticIntensity.LIGHT, HapticIntensity.STRONG.cursorMoveFeedback())
        assertEquals(HapticIntensity.OFF, HapticIntensity.OFF.cursorMoveFeedback())
    }
}
