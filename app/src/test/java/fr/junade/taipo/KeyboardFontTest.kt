package fr.junade.taipo

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeyboardFontTest {

    private val fontDir: File = listOf(File("src/main/res/font"), File("app/src/main/res/font")).first { it.isDirectory }

    @Test
    fun `open sans reste gratuite, les autres polices demandent le pass premium`() {
        assertFalse(KeyboardFont.OPEN_SANS.requiresPremium)
        KeyboardFont.entries.filter { it != KeyboardFont.OPEN_SANS }.forEach { assertTrue(it.requiresPremium, it.name) }
        assertTrue(KeyboardFont.INTER.isAvailable(premium = true))
        assertFalse(KeyboardFont.INTER.isAvailable(premium = false))
    }

    @Test
    fun `sans pass le clavier reprend open sans, le choix reste garde`() {
        assertEquals(KeyboardFont.OPEN_SANS, KeyboardFont.effective(KeyboardFont.LEXEND, premium = false))
        assertEquals(KeyboardFont.LEXEND, KeyboardFont.effective(KeyboardFont.LEXEND, premium = true))
    }

    @Test
    fun `les cles d'enregistrement sont uniques et relues telles quelles`() {
        val keys = KeyboardFont.entries.map { it.storageKey }
        assertEquals(keys.size, keys.toSet().size)
        KeyboardFont.entries.forEach { assertEquals(it, KeyboardFont.fromStorageKey(it.storageKey)) }
        assertEquals(KeyboardFont.DEFAULT, KeyboardFont.fromStorageKey("inconnue"))
        assertEquals(KeyboardFont.DEFAULT, KeyboardFont.fromStorageKey(null))
    }

    @Test
    fun `chaque police embarquee a ses trois graisses, sauf la police du telephone`() {
        KeyboardFont.entries.filter { it != KeyboardFont.SYSTEM }.forEach { font ->
            TaipoType.Weight.entries.forEach { assertTrue(font.fontRes(it) != null, "${font.name} ${it.name}") }
        }
        TaipoType.Weight.entries.forEach { assertEquals(null, KeyboardFont.SYSTEM.fontRes(it)) }
        val files = fontDir.list().orEmpty().map { it.substringBefore('.') }.toSet()
        listOf("inter", "atkinson", "lexend", "nunito", "manrope", "ibmplexsans", "literata").forEach { name ->
            listOf("regular", "medium", "bold").forEach { assertTrue("${name}_$it" in files, "${name}_$it") }
        }
        assertTrue("opendyslexic_regular" in files && "opendyslexic_bold" in files)
    }
}
