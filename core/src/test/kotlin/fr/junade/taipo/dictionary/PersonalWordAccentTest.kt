package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Mot personnel accentué tapé sans son accent (« Nae » pour « Naé ») : il reste proposé à chaque lettre et il est appliqué à
 * l'espace, même quand la forme sans accent est un mot rare de la liste (« nae », mot écossais de la liste anglaise).
 */
class PersonalWordAccentTest {

    private val personal = listOf("Naé")

    private val dictionary = Dictionary.withFrequencies(
        mapOf("you" to 1_000_000L, "come" to 50_000L, "name" to 900L, "nae" to 500L, "naevia" to 300L),
    )

    @Test
    fun `le mot personnel est propose a chaque lettre tapee sans accent`() {
        for (typed in listOf("N", "Na", "Nae", "nae")) {
            val shown = dictionary.suggestionSlotsFor(typed, personalWords = personal).mapNotNull { it?.text }
            assertTrue("Naé" in shown, "$typed : $shown")
        }
        assertTrue("Naé" in dictionary.suggestionsFor("Nae", personalWords = personal))
    }

    @Test
    fun `le mot personnel remplace une forme sans accent rare de la liste`() {
        assertEquals("Naé", dictionary.correctionFor("Nae", personalWords = personal))
        assertEquals("Naé", dictionary.correctionFor("nae", personalWords = personal))
        val slots = dictionary.suggestionSlotsFor("Nae", personalWords = personal)
        assertEquals(WordSuggestion("Naé", WordSuggestion.Kind.AUTOCORRECTION), slots[1])
    }

    @Test
    fun `un mot courant n est pas remplace par un mot personnel accentue`() {
        assertNull(dictionary.correctionFor("come", personalWords = listOf("Côme")))
        // Il reste proposé dans la bande.
        val shown = dictionary.suggestionSlotsFor("come", personalWords = listOf("Côme")).mapNotNull { it?.text }
        assertTrue("Côme" in shown, shown.toString())
    }

    @Test
    fun `le mot personnel est propose apres une elision tapee sans accent`() {
        val shown = dictionary.suggestionSlotsFor("l'Elo", personalWords = listOf("Éloïse")).mapNotNull { it?.text }
        assertTrue("l'Éloïse" in shown, shown.toString())
    }
}
