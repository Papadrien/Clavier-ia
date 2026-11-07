package fr.junade.taipo.dictionary

import fr.junade.taipo.suggestion.WordText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Allemand, phase 4 : mots composés (validité par découpage en parties connues, proposition de réunion de deux noms). */
class GermanCompoundsTest {

    private val frequencies = mapOf(
        "haus" to 70_000L, "tür" to 40_000L, "haustür" to 500L, "katze" to 9_000L, "arbeit" to 80_000L, "zimmer" to 60_000L,
        "abend" to 50_000L, "kleid" to 7_000L, "krankenhaus" to 3_000L, "arbeitszimmer" to 800L, "schnell" to 30_000L, "ich" to 5_000_000L,
    )

    private val dictionary = Dictionary.withFrequencies(
        frequencies,
        InflectionRules.GERMAN,
        41L,
        KeyProximity.NONE,
        properNames = listOf("Haus", "Tür", "Katze", "Arbeit", "Zimmer", "Krankenhaus", "Abend", "Kleid"),
        accentPolicy = AccentPolicy.GERMAN,
    )

    @Test
    fun `un compose valide absent des listes n est pas remplace`() {
        assertNull(dictionary.correctionFor("abendkleid"))
        assertNull(dictionary.correctionFor("hauskatze"))
    }

    @Test
    fun `un compose avec liaison est valide`() {
        // « arbeit » + « s » + « kleid » : absent des listes, la liaison « s » est permise.
        assertNull(dictionary.correctionFor("arbeitskleid"))
    }

    @Test
    fun `une faute de frappe d un mot frequent n est pas validee par le decoupage`() {
        // « arbeitzimmer » se découpe en « arbeit » + « zimmer » mais reste à une faute de « arbeitszimmer » (mot des listes) : corrigé.
        assertEquals("arbeitszimmer", dictionary.correctionFor("arbeitzimmer"))
        // Pas un composé : « krankenhauss » est une faute de « krankenhaus » (la partie après la liaison serait trop courte).
        assertEquals("Krankenhaus", dictionary.correctionFor("krankenhauss"))
    }

    @Test
    fun `sans regles de composes rien ne change`() {
        val plain = Dictionary.withFrequencies(frequencies, InflectionRules.NONE, 41L, KeyProximity.NONE, accentPolicy = AccentPolicy.GERMAN)
        assertNull(plain.compoundMergeFor("Haus", "tür"))
    }

    @Test
    fun `deux noms qui forment un mot sont proposes reunis`() {
        assertEquals("Haustür", dictionary.compoundMergeFor("Haus", "tür"))
        assertEquals("Hauskatze", dictionary.compoundMergeFor("Haus", "Katze"))
        assertEquals("Arbeitszimmer", dictionary.compoundMergeFor("Arbeit", "zimmer"))
    }

    @Test
    fun `rien n est propose si un des mots n est pas un nom ou si la reunion n est pas un mot`() {
        assertNull(dictionary.compoundMergeFor("Haus", "schnell"))
        assertNull(dictionary.compoundMergeFor("Tür", "Abend"))
    }

    @Test
    fun `le compose propose est un nom avec majuscule`() {
        assertEquals("Haustür", dictionary.compoundMergeFor("haus", "tür"))
    }

    @Test
    fun `le premier mot de la paire est celui qui precede par une seule espace`() {
        assertEquals("Haus", WordText.wordBeforeSingleSpace("Das Haus "))
        assertEquals("", WordText.wordBeforeSingleSpace("Das Haus, "))
        assertEquals("", WordText.wordBeforeSingleSpace("Das Haus  "))
        assertEquals("", WordText.wordBeforeSingleSpace("Das Haus"))
        assertEquals("", WordText.wordBeforeSingleSpace(""))
    }
}
