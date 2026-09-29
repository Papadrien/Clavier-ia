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
}
