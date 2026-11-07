package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** L'allemand n'élide pas : aucune apostrophe française rétablie (« danne » -> « d'anne », « sins » -> « s'ins », banc du 07/11/2026). */
class GermanNoElisionTest {

    private fun dictionary(confidence: CorrectionConfidence) = Dictionary.withFrequencies(
        mapOf("anne" to 5_000L, "danke" to 900_000L, "ins" to 400_000L, "sind" to 2_000_000L, "einer" to 600_000L, "meiner" to 90_000L),
        InflectionRules.GERMAN,
        41L,
        KeyProximity.NONE,
        confidence = confidence,
        accentPolicy = AccentPolicy.GERMAN,
    )

    @Test
    fun `le profil allemand ne retablit aucune elision`() {
        val de = dictionary(CorrectionConfidence.GERMAN)
        for (typed in listOf("danne", "sins", "leiner")) {
            val result = de.correctionFor(typed)
            assertFalse(result != null && '\'' in result, "$typed -> $result")
        }
        assertEquals("sind", de.correctionFor("sins"))
    }

    @Test
    fun `sans le profil allemand l elision etait retablie`() {
        // Garde-fou du test : le cas reproduit bien le défaut d'avant (profil NONE, élisions actives).
        assertEquals("s'ins", dictionary(CorrectionConfidence.NONE).correctionFor("sins"))
    }
}
