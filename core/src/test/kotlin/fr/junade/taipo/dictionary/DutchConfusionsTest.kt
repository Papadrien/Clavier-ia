package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Règles de contexte du néerlandais ([DutchConfusions]) : corrections attendues et phrases justes jamais touchées. */
class DutchConfusionsTest {

    private fun typed(sentence: String) =
        typeWithContext(sentence) { DutchContextCorrector.correct(it, textMayBeTruncated = false, allowRetro = true) { 0L } }

    private fun assertFixed(expected: String, typed: String) = assertEquals(expected, typed(typed))

    private fun assertUntouched(sentence: String) = assertEquals(sentence, typed(sentence))

    @Test
    fun `d ou dt selon le sujet`() {
        assertFixed("Hij wordt morgen dertig.", "Hij word morgen dertig.")
        assertFixed("Het wordt koud.", "Het word koud.")
        assertFixed("Ik word morgen dertig.", "Ik wordt morgen dertig.")
        assertFixed("Word jij ook moe?", "Wordt jij ook moe?")
        assertFixed("Zij vindt dat niet.", "Zij vind dat niet.")
        assertFixed("Ik houd van jou.", "Ik houdt van jou.")
        assertUntouched("Word jij ook zo moe van dit weer?")
        assertUntouched("Wordt het niet eens tijd?")
        assertUntouched("Vind jij dat ook?")
        assertUntouched("Dat woord ken ik niet.")
    }

    @Test
    fun `jou hun et apostrophes`() {
        assertFixed("Dit is voor jou, denk ik.", "Dit is voor jouw, denk ik.")
        assertFixed("Zij hebben het gedaan.", "Hun hebben het gedaan.")
        assertFixed("'s avonds kijk ik tv.", "s'avonds kijk ik tv.")
        assertFixed("Zo'n mooie dag.", "Zon mooie dag.")
        assertUntouched("Is dit jouw fiets?")
        assertUntouched("Hun huis is erg groot.")
        assertUntouched("De zon schijnt.")
    }
}
