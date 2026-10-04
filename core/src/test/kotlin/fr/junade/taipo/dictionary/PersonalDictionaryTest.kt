package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PersonalDictionaryTest {

    @Test
    fun `normalize accepte un mot valide`() {
        assertEquals("Adrien", PersonalDictionary.normalize("Adrien"))
    }

    @Test
    fun `normalize retire les espaces et remplace l apostrophe typographique`() {
        assertEquals("l'ami", PersonalDictionary.normalize("  l\u2019ami "))
    }

    @Test
    fun `normalize accepte accents apostrophe et trait d union`() {
        assertNotNull(PersonalDictionary.normalize("Éloïse"))
        assertNotNull(PersonalDictionary.normalize("jean-luc"))
        assertNotNull(PersonalDictionary.normalize("O'Neil"))
    }

    @Test
    fun `normalize refuse les mots invalides`() {
        assertNull(PersonalDictionary.normalize(""))
        assertNull(PersonalDictionary.normalize("   "))
        assertNull(PersonalDictionary.normalize("a"))
        assertNull(PersonalDictionary.normalize("deux mots"))
        assertNull(PersonalDictionary.normalize("abc123"))
        assertNull(PersonalDictionary.normalize("--"))
        assertNull(PersonalDictionary.normalize("a".repeat(PersonalDictionary.MAX_LENGTH + 1)))
    }

    @Test
    fun `normalize accepte la longueur maximale`() {
        assertNotNull(PersonalDictionary.normalize("a".repeat(PersonalDictionary.MAX_LENGTH)))
    }

    @Test
    fun `keyOf est insensible a la casse`() {
        assertEquals(PersonalDictionary.keyOf("iPhone"), PersonalDictionary.keyOf("IPHONE"))
        assertEquals("iphone", PersonalDictionary.keyOf("iPhone"))
    }

    @Test
    fun `sortForDisplay trie sans tenir compte de la casse`() {
        assertEquals(
            listOf("Adrien", "bob", "zoe"),
            PersonalDictionary.sortForDisplay(listOf("zoe", "Adrien", "bob")),
        )
    }
}
