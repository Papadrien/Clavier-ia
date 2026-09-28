package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DictionaryTest {

    private val dictionary = Dictionary(listOf("bonjour", "merci", "salut", "chat", "chien", "vraiment"))

    @Test
    fun `contains est insensible a la casse`() {
        assertTrue(dictionary.contains("bonjour"))
        assertTrue(dictionary.contains("Bonjour"))
        assertTrue(dictionary.contains("BONJOUR"))
        assertFalse(dictionary.contains("bonjoure"))
    }

    @Test
    fun `correctionFor renvoie null si le mot est deja correct`() {
        assertNull(dictionary.correctionFor("bonjour"))
    }

    @Test
    fun `correctionFor corrige une faute de frappe proche`() {
        assertEquals("bonjour", dictionary.correctionFor("bnojour"))
        assertEquals("merci", dictionary.correctionFor("mreci"))
    }

    @Test
    fun `correctionFor reapplique la majuscule du mot d origine`() {
        assertEquals("Bonjour", dictionary.correctionFor("Bnojour"))
    }

    @Test
    fun `correctionFor renvoie null pour un mot trop court`() {
        assertNull(dictionary.correctionFor("a"))
    }

    @Test
    fun `correctionFor renvoie null pour un mot contenant un chiffre`() {
        assertNull(dictionary.correctionFor("ch4t"))
    }

    @Test
    fun `correctionFor renvoie null pour un acronyme tout en majuscules`() {
        assertNull(dictionary.correctionFor("SVP"))
    }

    @Test
    fun `correctionFor renvoie null si aucun candidat n est assez proche`() {
        assertNull(dictionary.correctionFor("ordinateur"))
    }

    @Test
    fun `correctionFor renvoie null en cas d ambiguite entre deux candidats aussi proches`() {
        // "chXt" est à distance 1 aussi bien de "chat" que de "chien" ? Non — on
        // construit plutôt un cas explicite avec deux mots équidistants du mot tapé.
        val ambiguousDictionary = Dictionary(listOf("chat", "chah"))
        assertNull(ambiguousDictionary.correctionFor("cha"))
    }

    @Test
    fun `correctionFor ne corrige pas un mot du dictionnaire personnel`() {
        assertNull(dictionary.correctionFor("chatt", personalWords = listOf("chatt")))
        assertNull(dictionary.correctionFor("Chatt", personalWords = listOf("chatt")))
    }

    @Test
    fun `correctionFor utilise un mot personnel comme candidat`() {
        assertEquals("Taipo", dictionary.correctionFor("Taipoo", personalWords = listOf("Taipo")))
    }

    @Test
    fun `correctionFor conserve la casse enregistree d un mot personnel`() {
        assertEquals("iPhone", dictionary.correctionFor("iPhoen", personalWords = listOf("iPhone")))
    }

    @Test
    fun `correctionFor reapplique la majuscule pour un mot personnel en minuscules`() {
        assertEquals("Zorglub", dictionary.correctionFor("Zroglub", personalWords = listOf("zorglub")))
    }

    @Test
    fun `correctionFor renvoie null si un mot personnel et un mot du dictionnaire sont a egalite`() {
        val base = Dictionary(listOf("chat"))
        assertNull(base.correctionFor("chit", personalWords = listOf("chot")))
    }

    @Test
    fun `levenshtein calcule la distance d edition classique`() {
        assertEquals(0, Dictionary.levenshtein("chat", "chat"))
        assertEquals(1, Dictionary.levenshtein("chat", "chats"))
        assertEquals(1, Dictionary.levenshtein("chat", "chot"))
        assertEquals(3, Dictionary.levenshtein("kitten", "sitting"))
    }

    @Test
    fun `applyOriginalCasing majuscule la correction si l original en avait une`() {
        assertEquals("Merci", Dictionary.applyOriginalCasing("Mreci", "merci"))
        assertEquals("merci", Dictionary.applyOriginalCasing("mreci", "merci"))
    }
}
