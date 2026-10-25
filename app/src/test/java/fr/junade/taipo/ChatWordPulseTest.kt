package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatWordPulseTest {

    @Test
    fun `un morceau qui contient une espace apres un mot termine un mot`() {
        assertTrue(ChatWordPulse.completesWord("", "Bonjour "))
        assertTrue(ChatWordPulse.completesWord("Bon", "jour tout"))
    }

    @Test
    fun `un morceau sans espace ne termine pas de mot`() {
        assertFalse(ChatWordPulse.completesWord("", "Bon"))
        assertFalse(ChatWordPulse.completesWord("Bon", "jour"))
    }

    @Test
    fun `une espace en tete de morceau termine le mot precedent`() {
        assertTrue(ChatWordPulse.completesWord("Bonjour", " tout"))
    }

    @Test
    fun `des espaces sans mot avant ne comptent pas`() {
        assertFalse(ChatWordPulse.completesWord("Bonjour ", " "))
        assertFalse(ChatWordPulse.completesWord("", "  "))
        assertFalse(ChatWordPulse.completesWord("Bonjour\n", "\n"))
    }

    @Test
    fun `un mot commence apres une espace sans etre termine`() {
        assertFalse(ChatWordPulse.completesWord("Bonjour ", "tout"))
    }
}
