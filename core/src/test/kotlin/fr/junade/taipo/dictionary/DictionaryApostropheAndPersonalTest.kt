package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Autocorrection : mots avec apostrophe (« t'inscrire » ne doit pas devenir « inscrire ») et mots
 * personnels tapés sans accent (« Nae » pour « Naé » ne doit pas devenir « ane »).
 */
class DictionaryApostropheAndPersonalTest {

    private val dictionary = Dictionary(
        listOf("inscrire", "ordinateur", "ami", "today", "ane", "nue", "née", "bonjour"),
    )

    @Test
    fun `une elision devant un mot correct n est pas retiree`() {
        assertNull(dictionary.correctionFor("t'inscrire"))
        assertNull(dictionary.correctionFor("l'ordinateur"))
        assertNull(dictionary.correctionFor("L'ordinateur"))
        assertNull(dictionary.correctionFor("d'ami"))
    }

    @Test
    fun `une elision est conservee quand le mot qui suit est corrige`() {
        assertEquals("t'inscrire", dictionary.correctionFor("t'inscrir"))
        assertEquals("l'ordinateur", dictionary.correctionFor("l'ordniateur"))
        assertEquals("L'ordinateur", dictionary.correctionFor("L'ordniateur"))
    }

    @Test
    fun `un possessif anglais sur un mot connu n est pas corrige`() {
        assertNull(dictionary.correctionFor("today's"))
    }

    @Test
    fun `un mot personnel tape sans accent est retabli avec son accent`() {
        val personal = listOf("Naé")
        assertEquals("Naé", dictionary.correctionFor("Nae", personalWords = personal))
        assertEquals("Naé", dictionary.correctionFor("nae", personalWords = personal))
    }

    @Test
    fun `un mot personnel exact n est jamais corrige`() {
        assertNull(dictionary.correctionFor("Naé", personalWords = listOf("Naé")))
        assertNull(dictionary.correctionFor("naé", personalWords = listOf("Naé")))
    }

    @Test
    fun `a distance egale un mot personnel l emporte sur une inversion de lettres`() {
        // « nae » -> « ane » est une inversion (distance 1), « nad » une substitution (distance 1).
        assertEquals("Nad", dictionary.correctionFor("Nae", personalWords = listOf("Nad")))
    }

    @Test
    fun `une faute de frappe ordinaire est toujours corrigee`() {
        assertEquals("bonjour", dictionary.correctionFor("bnojour"))
    }

    @Test
    fun `les suggestions completent le mot apres une elision`() {
        val d = Dictionary.withFrequencies(mapOf("inscrire" to 500L, "inscription" to 300L, "ordinateur" to 200L, "chat" to 900L))
        assertEquals(listOf("t'inscrire", "t'inscription"), d.suggestionsFor("t'insc"))
        assertEquals(listOf("L'ordinateur"), d.suggestionsFor("L'ord"))
    }

    @Test
    fun `pas de completion apres une elision devant une consonne`() {
        val d = Dictionary.withFrequencies(mapOf("chaton" to 100L))
        assertEquals(emptyList<String>(), d.suggestionsFor("l'cha"))
    }
}
