package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Liste complète : seuil de fréquence de lecture (`parseFrequencyLines`) et seuil des candidats
 * (`withFrequencies`). Un mot rare reste valide mais n'est jamais proposé.
 */
class DictionaryCandidateFrequencyTest {

    private val entries = mapOf("chat" to 1_000L, "zebre" to 5L)

    @Test
    fun `parseFrequencyLines ecarte les mots sous le seuil`() {
        val lines = sequenceOf("de 900", "rare 4", "limite 5", "sans-frequence")
        assertEquals(setOf("de", "limite"), Dictionary.parseFrequencyLines(lines, minFrequency = 5L).keys)
    }

    @Test
    fun `parseFrequencyLines sans seuil garde toutes les lignes`() {
        val lines = sequenceOf("de 900", "rare 4", "sans-frequence")
        assertEquals(3, Dictionary.parseFrequencyLines(lines).size)
    }

    @Test
    fun `un mot rare est valide donc jamais corrige`() {
        val d = Dictionary.withFrequencies(entries, candidateMinFrequency = 100L)
        assertTrue(d.contains("zebre"))
        assertNull(d.correctionFor("zebre"))
    }

    @Test
    fun `un mot rare n est pas propose comme correction`() {
        val sansSeuil = Dictionary.withFrequencies(entries)
        val avecSeuil = Dictionary.withFrequencies(entries, candidateMinFrequency = 100L)
        assertEquals("zebre", sansSeuil.correctionFor("zebra"))
        assertNull(avecSeuil.correctionFor("zebra"))
    }

    @Test
    fun `un mot rare n est pas propose comme completion`() {
        val sansSeuil = Dictionary.withFrequencies(entries)
        val avecSeuil = Dictionary.withFrequencies(entries, candidateMinFrequency = 100L)
        assertEquals(listOf("zebre"), sansSeuil.suggestionsFor("zeb"))
        assertTrue(avecSeuil.suggestionsFor("zeb").isEmpty())
    }

    @Test
    fun `un mot frequent reste corrige avec le seuil`() {
        val d = Dictionary.withFrequencies(entries, candidateMinFrequency = 100L)
        assertEquals("chat", d.correctionFor("chta"))
        assertFalse(d.suggestionsFor("ch").isEmpty())
    }
}
