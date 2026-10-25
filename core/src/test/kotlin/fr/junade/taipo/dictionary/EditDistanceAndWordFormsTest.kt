package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Audit 2.1, étape 6 : les relais de [Dictionary] donnent les mêmes résultats que [EditDistance] et [WordForms]. */
class EditDistanceAndWordFormsTest {

    @Test
    fun `les relais de Dictionary donnent les memes distances`() {
        assertEquals(EditDistance.levenshtein("kitten", "sitting"), Dictionary.levenshtein("kitten", "sitting"))
        assertEquals(1, EditDistance.damerauLevenshtein("teh", "the"))
        assertEquals(1, Dictionary.damerauLevenshtein("teh", "the"))
        assertEquals(3, EditDistance.damerauLevenshteinBounded("abcdef", "uvwxyz", 2))
    }

    @Test
    fun `inversion de deux lettres voisines`() {
        assertTrue(EditDistance.isAdjacentSwap("teh", "the"))
        assertFalse(EditDistance.isAdjacentSwap("tea", "the"))
    }

    @Test
    fun `masque de lettres identique via le relais`() {
        assertEquals(EditDistance.letterMask("l'été"), Dictionary.letterMask("l'été"))
    }

    @Test
    fun `accents et majuscule initiale`() {
        assertEquals("duree", WordForms.foldAccents("durée"))
        assertEquals("oeuvre", Dictionary.foldAccents("œuvre"))
        assertEquals("Bonjour", WordForms.applyOriginalCasing("Bnojour", "bonjour"))
        assertEquals("bonjour", Dictionary.applyOriginalCasing("bnojour", "bonjour"))
    }

    @Test
    fun `lecture des lignes de frequences`() {
        val parsed = WordForms.parseFrequencyLines(sequenceOf("chat 10", "chien", "", "ours x"), minFrequency = 0L)
        assertEquals(10L, parsed["chat"])
        assertEquals(1L, parsed["chien"])
        assertEquals(1L, parsed["ours x"])
    }
}
