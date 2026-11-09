package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Quand l'autocorrection s'applique : espace ou ponctuation juste après la frappe d'un mot, jamais à Entrée (comme Gboard). */
class AutocorrectionMemoryTest {

    private val memory = AutocorrectionMemory()

    private fun char(c: Char) = Key("k_$c", c.toString(), KeyAction.TypeChar(c))
    private val space = Key("space", " ", KeyAction.Space)
    private val enter = Key("enter", "", KeyAction.Enter)
    private val backspace = Key("del", "", KeyAction.Backspace)
    private val shift = Key("shift", "", KeyAction.Shift)

    private fun type(word: String, at: Long) = word.forEach { memory.onKey(char(it), at) }

    @Test
    fun `l'espace juste après un mot le corrige`() {
        type("bonjuor", at = 1_000)
        assertTrue(memory.onKey(space, 1_300))
    }

    @Test
    fun `la ponctuation juste après un mot le corrige`() {
        type("teh", at = 1_000)
        assertTrue(memory.onKey(char(','), 1_200))
    }

    @Test
    fun `Entrée ne corrige jamais`() {
        type("bonjuor", at = 1_000)
        assertFalse(memory.onKey(enter, 1_100))
        // Ni l'espace tapée ensuite sur la nouvelle ligne.
        assertFalse(memory.onKey(space, 1_200))
    }

    @Test
    fun `après une pause trop longue, l'espace ne corrige pas`() {
        type("bonjuor", at = 1_000)
        assertFalse(memory.onKey(space, 1_000 + AutocorrectionMemory.WORD_WINDOW_MS + 1))
    }

    @Test
    fun `curseur posé ailleurs, l'espace ne corrige pas`() {
        type("bonjuor", at = 1_000)
        memory.onCursorPlaced()
        assertFalse(memory.onKey(space, 1_100))
    }

    @Test
    fun `une deuxième espace ne revoit pas le mot`() {
        type("bonjuor", at = 1_000)
        assertTrue(memory.onKey(space, 1_100))
        assertFalse(memory.onKey(space, 1_200))
    }

    @Test
    fun `une suppression garde le mot en cours de frappe, Maj ne compte pas`() {
        type("bonjuo", at = 1_000)
        memory.onKey(backspace, 4_000)
        memory.onKey(shift, 8_500)
        assertTrue(memory.onKey(space, 8_900))
    }

    @Test
    fun `apostrophe et trait d'union ne terminent pas le mot`() {
        assertFalse(TypingRules.isWordBoundaryKey(char('\'')))
        assertFalse(TypingRules.isWordBoundaryKey(char('-')))
        assertFalse(TypingRules.isWordBoundaryKey(enter))
        assertTrue(TypingRules.isWordBoundaryKey(space))
        assertTrue(TypingRules.isWordBoundaryKey(char('.')))
    }
}
