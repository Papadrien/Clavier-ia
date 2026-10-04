package fr.junade.taipo.suggestion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WordTextTest {

    @Test
    fun `lettres apostrophe et trait d'union composent un mot`() {
        assertTrue(WordText.isWordChar('a'))
        assertTrue(WordText.isWordChar('é'))
        assertTrue(WordText.isWordChar('\''))
        assertTrue(WordText.isWordChar('-'))
    }

    @Test
    fun `chiffres espaces et ponctuation ne composent pas un mot`() {
        assertFalse(WordText.isWordChar('1'))
        assertFalse(WordText.isWordChar(' '))
        assertFalse(WordText.isWordChar('.'))
        assertFalse(WordText.isWordChar(','))
    }

    @Test
    fun `le dernier mot est celui qui touche le curseur`() {
        assertEquals("monde", WordText.trailingWord("bonjour le monde"))
        assertEquals("l'ami", WordText.trailingWord("voici l'ami"))
        assertEquals("peut-être", WordText.trailingWord("c'est peut-être"))
    }

    @Test
    fun `aucun mot apres une espace ou une ponctuation`() {
        assertEquals("", WordText.trailingWord("bonjour "))
        assertEquals("", WordText.trailingWord("bonjour."))
        assertEquals("", WordText.trailingWord(""))
    }

    @Test
    fun `un texte sans separateur est un seul mot`() {
        assertEquals("bonjour", WordText.trailingWord("bonjour"))
    }
}
