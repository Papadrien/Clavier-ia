package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeyboardHeightTest {

    @Test
    fun `normale est la hauteur par defaut et vaut 100 pour cent`() {
        assertEquals(KeyboardHeight.NORMAL, KeyboardHeight.DEFAULT)
        assertEquals(1.0f, KeyboardHeight.DEFAULT.scale)
    }

    @Test
    fun `chaque cle de stockage retrouve son niveau`() {
        KeyboardHeight.entries.forEach {
            assertEquals(it, KeyboardHeight.fromStorageKey(it.storageKey))
        }
    }

    @Test
    fun `une cle absente ou inconnue retombe sur le niveau par defaut`() {
        assertEquals(KeyboardHeight.DEFAULT, KeyboardHeight.fromStorageKey(null))
        assertEquals(KeyboardHeight.DEFAULT, KeyboardHeight.fromStorageKey("inconnu"))
    }

    @Test
    fun `les coefficients croissent avec le niveau et restent dans une plage raisonnable`() {
        val scales = KeyboardHeight.entries.map { it.scale }
        assertEquals(scales.sorted(), scales)
        assertTrue(scales.all { it in 0.7f..1.3f })
    }

    @Test
    fun `les cles de stockage sont uniques`() {
        val keys = KeyboardHeight.entries.map { it.storageKey }
        assertEquals(keys.size, keys.toSet().size)
    }
}
