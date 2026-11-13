package fr.junade.taipo.backdrop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Fonds du clavier : clés d'enregistrement, réservation au pack (Cœurs et Fleurs ajoutés le 13/11/2026). */
class KeyboardBackdropTest {

    @Test
    fun `chaque fond se relit depuis sa cle`() {
        KeyboardBackdrop.entries.forEach { assertEquals(it, KeyboardBackdrop.fromStorageKey(it.storageKey)) }
        assertEquals(KeyboardBackdrop.entries.size, KeyboardBackdrop.entries.map { it.storageKey }.toSet().size)
    }

    @Test
    fun `coeurs et fleurs sont des motifs du pack premium`() {
        listOf(KeyboardBackdrop.HEARTS, KeyboardBackdrop.FLOWERS).forEach {
            assertTrue(it.isPattern)
            assertFalse(it.isAvailable(premium = false))
            assertEquals(KeyboardBackdrop.PLAIN, KeyboardBackdrop.effective(it, premium = false))
            assertEquals(it, KeyboardBackdrop.effective(it, premium = true))
        }
    }
}
