package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Décision de déclenchement (phase 3) vue de [Dictionary.correctionFor] : marge, mots longs, élisions absurdes. */
class DictionaryConfidenceTest {

    private fun dictionary(confidence: CorrectionConfidence, vararg entries: Pair<String, Long>) =
        Dictionary.withFrequencies(entries.toMap(), InflectionRules.NONE, confidence = confidence)

    @Test
    fun `une quasi-egalite ne corrige plus, une nette difference oui`() {
        val nearTie = arrayOf("chat" to 1_000L, "chut" to 1_100L)
        assertEquals("chut", dictionary(CorrectionConfidence.NONE, *nearTie).correctionFor("chxt"))
        assertNull(dictionary(CorrectionConfidence.STANDARD, *nearTie).correctionFor("chxt"))

        val clear = arrayOf("chat" to 1_000L, "chut" to 100_000L)
        assertEquals("chut", dictionary(CorrectionConfidence.NONE, *clear).correctionFor("chxt"))
        assertEquals("chut", dictionary(CorrectionConfidence.STANDARD, *clear).correctionFor("chxt"))
    }

    @Test
    fun `une egalite stricte ne corrige pas, avec ou sans seuil`() {
        val tie = arrayOf("chat" to 1_000L, "chut" to 1_000L)
        assertNull(dictionary(CorrectionConfidence.NONE, *tie).correctionFor("chxt"))
        assertNull(dictionary(CorrectionConfidence.STANDARD, *tie).correctionFor("chxt"))
    }

    @Test
    fun `un candidat sans rival est corrige`() {
        assertEquals("chat", dictionary(CorrectionConfidence.STANDARD, "chat" to 1_000L).correctionFor("chxt"))
    }

    @Test
    fun `un mot personnel passe devant une quasi-egalite`() {
        val d = dictionary(CorrectionConfidence.STANDARD, "chat" to 1_000L, "chut" to 1_100L)
        assertEquals("chyt", d.correctionFor("chxt", personalWords = listOf("chyt")))
    }

    @Test
    fun `un mot long a trois erreurs est corrige seulement avec la decision standard`() {
        val entries = arrayOf("ordinateur" to 50_000L, "ordinaire" to 20_000L)
        assertNull(dictionary(CorrectionConfidence.NONE, *entries).correctionFor("orbinatxxr"))
        assertEquals("ordinateur", dictionary(CorrectionConfidence.STANDARD, *entries).correctionFor("orbinatxxr"))
    }

    @Test
    fun `trois erreurs ne sont pas tentees si l'appelant limite la distance`() {
        val d = dictionary(CorrectionConfidence.STANDARD, "ordinateur" to 50_000L)
        assertNull(d.correctionFor("orbinatxxr", maxDistance = 1))
    }

    @Test
    fun `trois erreurs ne sont pas tentees sur un mot court`() {
        // « abcdefg » : 7 lettres, sous le minimum des mots longs.
        val d = dictionary(CorrectionConfidence.STANDARD, "abcdefg" to 50_000L)
        assertNull(d.correctionFor("axyzefg"))
    }

    @Test
    fun `un candidat plus proche passe devant les autres sans rien changer a la decision`() {
        val d = dictionary(CorrectionConfidence.STANDARD, "ordinateur" to 50_000L, "ordinateurs" to 10L)
        assertEquals("ordinateur", d.correctionFor("ordinatexr"))
    }

    @Test
    fun `un mot long a trois erreurs avec deux rivaux a egalite n'est pas corrige`() {
        val d = dictionary(CorrectionConfidence.STANDARD, "ordinateur" to 50_000L, "orginateur" to 50_000L)
        assertNull(d.correctionFor("orbinatxxr"))
    }

    @Test
    fun `une elision absurde n'est plus retablie`() {
        val entries = arrayOf("our" to 1_000L, "pour" to 100_000L)
        assertEquals("m'our", dictionary(CorrectionConfidence.NONE, *entries).correctionFor("mour"))
        val standard = dictionary(CorrectionConfidence.STANDARD, *entries).correctionFor("mour")
        assertNotEquals("m'our", standard)
        assertEquals("pour", standard)
    }

    @Test
    fun `c' ne s'elide que devant e`() {
        val entries = arrayOf("avait" to 5_000L, "était" to 90_000L)
        assertEquals("c'avait", dictionary(CorrectionConfidence.NONE, *entries).correctionFor("cavait"))
        assertNotEquals("c'avait", dictionary(CorrectionConfidence.STANDARD, *entries).correctionFor("cavait"))
        assertEquals("c'était", dictionary(CorrectionConfidence.STANDARD, *entries).correctionFor("cetait"))
    }

    @Test
    fun `les vraies elisions restent retablies`() {
        val d = dictionary(CorrectionConfidence.STANDARD, "inquiète" to 44_000L, "ordinateur" to 17_000L)
        assertEquals("t'inquiète", d.correctionFor("tinquiete"))
        assertEquals("l'ordinateur", d.correctionFor("lordinateur"))
    }

    @Test
    fun `un mot deja valide n'est jamais touche par la decision`() {
        val d = dictionary(CorrectionConfidence.STANDARD, "chat" to 1_000L, "chut" to 1_100L)
        assertNull(d.correctionFor("chat"))
        assertNull(d.correctionFor("chut"))
    }
}
