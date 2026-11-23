package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Mots bloqués (23/11/2026) : retirés de la bande, jamais introduits par l'autocorrection ; le mot tapé reste. */
class BlockedWordFilterTest {

    private val blocked = setOf("bonsoir", "ok")

    @Test
    fun `les mots bloques quittent la bande, le mot tape reste`() {
        val slots = listOf(
            WordSuggestion("Bonsoir", WordSuggestion.Kind.TYPED),
            WordSuggestion("Bonsoir", WordSuggestion.Kind.AUTOCORRECTION),
            WordSuggestion("bonjour", WordSuggestion.Kind.COMPLETION),
        )
        assertEquals(listOf(slots[0], null, slots[2]), BlockedWordFilter.slots(slots, blocked))
        assertEquals(slots, BlockedWordFilter.slots(slots, emptySet()))
    }

    @Test
    fun `une correction qui introduit un mot bloque est refusee`() {
        assertTrue(BlockedWordFilter.blocksCorrection("bonsoit", "bonsoir", blocked))
        assertFalse(BlockedWordFilter.blocksCorrection("bonjoue", "bonjour", blocked))
        // Le mot bloqué était déjà là (tapé par l'utilisateur) : la correction d'un autre mot n'est pas gênée.
        assertFalse(BlockedWordFilter.blocksCorrection("ok bonjoue", "ok bonjour", blocked))
    }

    @Test
    fun `cle sans casse ni espaces, accents gardes`() {
        assertEquals("été", BlockedWords.keyOf("  Été "))
    }
}
