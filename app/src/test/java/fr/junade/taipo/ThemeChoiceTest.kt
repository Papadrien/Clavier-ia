package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThemeChoiceTest {

    @Test
    fun `le theme par defaut suit le mode de l appareil`() {
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.DEFAULT)
    }

    @Test
    fun `systeme suit le mode de l appareil`() {
        assertEquals(KeyboardTheme.Mode.DARK, ThemeChoice.SYSTEM.resolve(systemNight = true))
        assertEquals(KeyboardTheme.Mode.LIGHT, ThemeChoice.SYSTEM.resolve(systemNight = false))
    }

    @Test
    fun `clair et sombre ignorent le mode de l appareil`() {
        for (night in listOf(true, false)) {
            assertEquals(KeyboardTheme.Mode.LIGHT, ThemeChoice.LIGHT.resolve(night))
            assertEquals(KeyboardTheme.Mode.DARK, ThemeChoice.DARK.resolve(night))
        }
    }

    @Test
    fun `couleur dynamique suit le mode de l appareil comme systeme`() {
        assertEquals(KeyboardTheme.Mode.DARK, ThemeChoice.DYNAMIC.resolve(systemNight = true))
        assertEquals(KeyboardTheme.Mode.LIGHT, ThemeChoice.DYNAMIC.resolve(systemNight = false))
    }

    @Test
    fun `couleur dynamique n est proposee qu a partir d Android 12`() {
        assertFalse(ThemeChoice.DYNAMIC.isAvailable(30))
        assertTrue(ThemeChoice.DYNAMIC.isAvailable(31))
        for (choice in listOf(ThemeChoice.SYSTEM, ThemeChoice.LIGHT, ThemeChoice.DARK)) {
            assertTrue(choice.isAvailable(26), "$choice")
        }
    }

    @Test
    fun `seule couleur dynamique sur Android 12 ou plus utilise les couleurs du fond d ecran`() {
        assertFalse(ThemeChoice.DYNAMIC.usesDynamicColors(30))
        assertTrue(ThemeChoice.DYNAMIC.usesDynamicColors(34))
        for (choice in listOf(ThemeChoice.SYSTEM, ThemeChoice.LIGHT, ThemeChoice.DARK)) {
            assertFalse(choice.usesDynamicColors(34), "$choice")
        }
    }

    @Test
    fun `chaque cle de stockage retrouve son theme`() {
        for (choice in ThemeChoice.entries) assertEquals(choice, ThemeChoice.fromStorageKey(choice.storageKey))
    }

    @Test
    fun `une cle absente ou inconnue retombe sur le theme par defaut`() {
        assertEquals(ThemeChoice.DEFAULT, ThemeChoice.fromStorageKey(null))
        assertEquals(ThemeChoice.DEFAULT, ThemeChoice.fromStorageKey("inconnu"))
    }
}
